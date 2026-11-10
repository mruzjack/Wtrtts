package com.paw.wtrtts

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

// ---------------------------------------------------------------------------
// Saved reading position per chapter URL, so reading can resume where you left
// off. Keeps the most recent 300 chapters.
// ---------------------------------------------------------------------------
object Progress {
    private const val PREF = "wtr_progress"
    private const val KEY = "map"
    private const val MAX = 300

    fun norm(u: String): String = u.substringBefore('#').trimEnd('/')

    private fun load(c: Context): JSONObject {
        val s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
        return try {
            if (s == null) JSONObject() else JSONObject(s)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun save(c: Context, o: JSONObject) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun get(c: Context, url: String): Int? {
        val o = load(c)
        val k = norm(url)
        return if (o.has(k)) o.optInt(k, 0) else null
    }

    fun put(c: Context, url: String, idx: Int) {
        val o = load(c)
        val k = norm(url)
        o.remove(k)
        o.put(k, idx)
        while (o.length() > MAX) {
            val first = o.keys().next()
            o.remove(first)
        }
        save(c, o)
    }

    fun clear(c: Context, url: String) {
        val o = load(c)
        if (o.remove(norm(url)) != null) save(c, o)
    }
}

// ---------------------------------------------------------------------------
// JavaScript used on the chapter page. The visible page and the hidden reader
// page both use the same paragraph finder, so paragraph numbers match.
// ---------------------------------------------------------------------------
object WtrJs {
    // Finds the block with the most paragraph text; its children are the paragraphs.
    val CORE = """
(function(){
  if (window.__wtrCore) return;
  window.__wtrCore = 1;
  function txt(e){ return (e.innerText || '').trim(); }
  function findBest(){
    var best = null, bl = 0;
    var els = document.querySelectorAll('div,article,section,main');
    for (var i = 0; i < els.length; i++) {
      var e = els[i], ps = 0, len = 0;
      for (var c = e.firstElementChild; c; c = c.nextElementSibling) {
        if (c.tagName === 'P' || (c.tagName === 'DIV' && c.children.length == 0)) { len += txt(c).length; ps++; }
      }
      if (ps >= 3 && len > bl) { bl = len; best = e; }
    }
    return best;
  }
  window.__wtrParas = function(){
    var b = findBest();
    if (!b) return [];
    return Array.prototype.filter.call(b.children, function(c){ return txt(c).length > 0; });
  };
})();
"""

    // Highlight, scroll, "where am I" and tap-a-line for the visible page.
    val UI = CORE + """
(function(){
  if (window.__wtrUi) return;
  window.__wtrUi = 1;
  window.__wtrCur = -1;
  window.__wtrCurUrl = '';
  window.__wtrUserTs = 0;
  var st = document.createElement('style');
  st.textContent = '[data-wtr-hl]{background:rgba(255,193,7,0.35) !important;box-shadow:0 0 0 4px rgba(255,193,7,0.35);border-radius:4px;scroll-margin-top:30vh;}';
  (document.head || document.documentElement).appendChild(st);
  document.addEventListener('touchstart', function(){ window.__wtrUserTs = Date.now(); }, {passive: true, capture: true});

  // mode 0 = only mark, 1 = scroll when needed (not while you are scrolling), 2 = always scroll
  window.__wtrHi = function(i, mode){
    var ps = window.__wtrParas();
    if (i < 0 || i >= ps.length) return false;
    var old = document.querySelectorAll('[data-wtr-hl]');
    for (var k = 0; k < old.length; k++) old[k].removeAttribute('data-wtr-hl');
    var e = ps[i];
    e.setAttribute('data-wtr-hl', '1');
    window.__wtrCur = i;
    window.__wtrCurUrl = location.href;
    if (mode > 0) {
      var r = e.getBoundingClientRect();
      var vh = window.innerHeight;
      var off = (r.top < vh * 0.08 || r.bottom > vh * 0.85);
      var busy = (Date.now() - window.__wtrUserTs) < 6000;
      if (mode == 2 || (off && !busy)) e.scrollIntoView({block: 'start', behavior: 'smooth'});
    }
    return true;
  };

  // Where to start reading: the marked line if it is on screen, otherwise the first line on screen.
  window.__wtrStart = function(){
    var ps = window.__wtrParas();
    if (!ps.length) return -1;
    var vh = window.innerHeight, r, i;
    var c = window.__wtrCur;
    if (c >= 0 && c < ps.length && window.__wtrCurUrl === location.href) {
      r = ps[c].getBoundingClientRect();
      if (r.bottom > 0 && r.top < vh) return c;
    }
    for (i = 0; i < ps.length; i++) {
      r = ps[i].getBoundingClientRect();
      if (r.top >= 0 && r.top < vh) return i;
    }
    for (i = 0; i < ps.length; i++) {
      r = ps[i].getBoundingClientRect();
      if (r.bottom > 0) return i;
    }
    return 0;
  };

  document.addEventListener('click', function(ev){
    try {
      var t = ev.target;
      if (!t || !t.closest) return;
      if (t.closest('a,button,input,select,textarea,label')) return;
      var ps = window.__wtrParas();
      if (!ps.length) return;
      var par = ps[0].parentNode;
      var el = t;
      while (el && el.parentNode !== par) el = el.parentNode;
      if (!el) return;
      var i = ps.indexOf(el);
      if (i >= 0 && window.WtrApp) window.WtrApp.tap(i);
    } catch (err) {}
  }, true);
})();
"""

    // Used by the hidden reader page: paragraph texts, next-chapter link, title.
    val EXTRACT = CORE + """
(function(){
  var ps = window.__wtrParas();
  var texts = [];
  for (var i = 0; i < ps.length; i++) texts.push((ps[i].innerText || '').trim());
  var a = document.querySelector('a[rel=next]');
  if (!a) {
    var as = document.querySelectorAll('a');
    for (var j = 0; j < as.length; j++) {
      if (/^\s*next/i.test(as[j].innerText || '')) { a = as[j]; break; }
    }
  }
  var nx = (a && /^http/.test(a.href)) ? a.href : '';
  return JSON.stringify({paras: texts, next: nx, title: document.title || ''});
})()
"""
}

// ---------------------------------------------------------------------------
// Screen: a simple browser. Log in / browse WTR Lab, open a chapter, tap Read.
// Tap a line to mark it (or jump to it while reading). The spoken line is
// highlighted and the page scrolls along.
// ---------------------------------------------------------------------------
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
    private var speedIdx = 0

    private var hiToken = 0

    // Which reader chapter URL the visible page is showing (and the page's own URL).
    private var attSvc: String? = null
    private var attWeb: String? = null
    private var followLoad = false
    private var followSvc: String? = null
    private var listenerRef: ((String, Int) -> Unit)? = null

    inner class Bridge {
        @JavascriptInterface
        fun tap(i: Int) {
            runOnUiThread { onTap(i) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        urlBar = EditText(this).apply {
            setSingleLine(true)
            setText("https://wtr-lab.com")
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ ->
                var u = text.toString().trim()
                if (!u.startsWith("http")) u = "https://$u"
                web.loadUrl(u)
                true
            }
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "WtrApp")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    urlBar.setText(url)
                    view.evaluateJavascript(WtrJs.UI, null)
                    val prev = attWeb
                    if (followLoad && !(prev != null && same(url, prev))) {
                        // Reader moved on to the next chapter and we followed it.
                        followLoad = false
                        attSvc = followSvc
                        attWeb = url
                        tryHilite(TtsService.curPara, 2)
                    } else {
                        // Opening a chapter you read before: jump to where you left off.
                        val saved = Progress.get(this@MainActivity, url)
                        if (saved != null) tryHilite(saved, 2)
                    }
                }
            }
            loadUrl("https://wtr-lab.com")
        }

        fun btn(label: String, onClick: (Button) -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick(this) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row = LinearLayout(this)
        row.addView(btn("▶ Read") { readFromHere() })
        row.addView(btn("⏯") { send(TtsService.A_TOGGLE) })
        row.addView(btn("⏭") { send(TtsService.A_NEXT) })
        row.addView(btn("1.0x") { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            b.text = "${speeds[speedIdx]}x"
            send(TtsService.A_RATE, rate = speeds[speedIdx])
        })

        root.addView(
            urlBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        val cb: (String, Int) -> Unit = { u, i -> onSvcProgress(u, i, false) }
        listenerRef = cb
        TtsService.onProgress = cb
        // Coming back while it is reading: show the line being read right now.
        val cu = TtsService.chapterUrl
        if (cu.isNotEmpty() && TtsService.playingNow) {
            onSvcProgress(cu, TtsService.curPara, true)
        }
    }

    override fun onStop() {
        if (TtsService.onProgress === listenerRef) TtsService.onProgress = null
        listenerRef = null
        super.onStop()
    }

    private fun same(a: String, b: String): Boolean = Progress.norm(a) == Progress.norm(b)

    // Is the visible page the chapter the reader is working on?
    private fun showing(svcUrl: String): Boolean {
        val cur = web.url ?: return false
        if (same(cur, svcUrl)) return true
        val s = attSvc
        val w = attWeb
        return s != null && w != null && same(s, svcUrl) && same(cur, w)
    }

    // Mark a paragraph on the page; retries for a few seconds while the page is still rendering.
    private fun tryHilite(i: Int, mode: Int, tries: Int = 8) {
        val t = ++hiToken
        fun go(left: Int) {
            if (t != hiToken) return
            web.evaluateJavascript("window.__wtrHi ? window.__wtrHi($i,$mode) : false") { r ->
                if (t == hiToken && r != "true" && left > 0) {
                    ui.postDelayed({ go(left - 1) }, 900)
                }
            }
        }
        go(tries)
    }

    // Reader reports the paragraph it is speaking.
    private fun onSvcProgress(url: String, idx: Int, force: Boolean) {
        if (followLoad) return
        val cur = web.url ?: return
        if (showing(url)) {
            attSvc = url
            attWeb = cur
            tryHilite(idx, if (force) 2 else 1)
            return
        }
        val w = attWeb
        val s = attSvc
        if (w != null && s != null && same(cur, w) && !same(s, url)) {
            // You were following the reading and it moved to the next chapter: follow it.
            followLoad = true
            followSvc = url
            ui.postDelayed({ followLoad = false }, 20000)
            web.loadUrl(url)
        }
    }

    private fun onTap(i: Int) {
        tryHilite(i, 0)
        val cu = TtsService.chapterUrl
        if (cu.isNotEmpty() && showing(cu)) {
            send(TtsService.A_SEEK, start = i)
        }
    }

    // Start from the marked line if it is on screen, otherwise from the first line on screen.
    private fun readFromHere() {
        val u = web.url ?: return
        web.evaluateJavascript("window.__wtrStart ? window.__wtrStart() : -1") { r ->
            val s = (r ?: "-1").trim().toIntOrNull() ?: -1
            val start = if (s < 0) 0 else s
            attSvc = u
            attWeb = u
            if (s >= 0) tryHilite(start, 0)
            send(TtsService.A_LOAD, url = u, rate = speeds[speedIdx], start = start)
        }
    }

    private fun send(action: String, url: String? = null, rate: Float? = null, start: Int? = null) {
        val i = Intent(this, TtsService::class.java).setAction(action)
        if (url != null) i.putExtra("url", url)
        if (rate != null) i.putExtra("rate", rate)
        if (start != null) i.putExtra("start", start)
        startForegroundService(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

// ---------------------------------------------------------------------------
// Foreground service: loads chapters in a hidden WebView, reads them with
// Android TextToSpeech, auto-advances to the next chapter. Keeps running with
// the screen off thanks to the foreground service + partial wake lock.
// ---------------------------------------------------------------------------
class TtsService : Service() {
    companion object {
        const val A_LOAD = "LOAD"
        const val A_TOGGLE = "TOGGLE"
        const val A_NEXT = "NEXT"
        const val A_STOP = "STOP"
        const val A_RATE = "RATE"
        const val A_SEEK = "SEEK"
        private const val CH = "wtr_tts"
        private const val NID = 1
        private const val MAXLEN = 3500

        // Read by the screen (same process).
        @Volatile var playingNow = false
        @Volatile var chapterUrl = ""
        @Volatile var curPara = 0
        var onProgress: ((String, Int) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var web: WebView
    private lateinit var session: MediaSessionCompat
    private lateinit var wl: PowerManager.WakeLock
    private lateinit var am: AudioManager

    // One audio-focus request, reused. (A new request each time made Android tell
    // the old one it lost focus, which paused playback right after pressing play.)
    private var focusReq: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnGain = false

    // One chunk = one paragraph (very long paragraphs are split); chunkPara maps chunk -> paragraph number.
    @Volatile private var chunks: List<String> = emptyList()
    @Volatile private var chunkPara: List<Int> = emptyList()
    @Volatile private var idx = 0
    private var pendingStart = 0
    private var playing = false
        set(v) {
            field = v
            playingNow = v
        }
    private var curUrl = ""
    private var nextUrl: String? = null
    private var title = "WTR TTS"
    private var rate = 1f
    private var loadToken = 0

    @Volatile
    private var gen = 0

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Reading", NotificationManager.IMPORTANCE_LOW)
        )
        am = getSystemService(AudioManager::class.java)
        wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wtrtts:read")

        session = MediaSessionCompat(this, "wtrtts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (chunks.isNotEmpty()) play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { goNext(false) }
            })
            isActive = true
        }

        web = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Give the hidden page a phone-sized layout so it matches the visible page.
            val dm = resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, dm.widthPixels, dm.heightPixels)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val t = loadToken
                    main.postDelayed({ extract(t, 0) }, 2000)
                }
            }
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale.US
                tts?.setAudioAttributes(audioAttrs)
                tts?.setSpeechRate(rate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val p = utteranceId?.split("_") ?: return
                        if (p.size == 2 && p[0].toIntOrNull() == gen) {
                            val c = p[1].toIntOrNull() ?: return
                            idx = c
                            main.post { publishProgress() }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        val p = utteranceId?.split("_") ?: return
                        if (p.size != 2) return
                        if (p[0].toIntOrNull() == gen &&
                            p[1].toIntOrNull() == chunks.size - 1 && playing
                        ) {
                            main.post { goNext(true) }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (i?.action) {
            A_LOAD -> {
                rate = i.getFloatExtra("rate", rate)
                tts?.setSpeechRate(rate)
                val url = i.getStringExtra("url")
                val start = i.getIntExtra("start", 0)
                if (url != null) {
                    if (chunks.isNotEmpty() && Progress.norm(url) == Progress.norm(chapterUrl)) {
                        // Same chapter already loaded: just jump to the line, no reload.
                        idx = chunkFor(start)
                        play()
                    } else {
                        loadChapter(url, start)
                    }
                }
            }
            A_TOGGLE -> if (playing) pause() else if (chunks.isNotEmpty()) play()
            A_NEXT -> goNext(false)
            A_RATE -> {
                rate = i.getFloatExtra("rate", 1f)
                tts?.setSpeechRate(rate)
                if (playing && chunks.isNotEmpty()) speakFrom(idx)
            }
            A_SEEK -> {
                val p = i.getIntExtra("start", 0)
                if (chunks.isNotEmpty()) {
                    idx = chunkFor(p)
                    if (playing) speakFrom(idx) else savePos()
                } else if (!playing) {
                    stopAll()
                    return START_NOT_STICKY
                }
            }
            A_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // ---- chapter loading ---------------------------------------------------

    private fun loadChapter(url: String, start: Int) {
        gen++
        tts?.stop()
        chunks = emptyList()
        chunkPara = emptyList()
        idx = 0
        pendingStart = start
        curUrl = url
        chapterUrl = ""
        nextUrl = null
        title = "Loading chapter…"
        playing = true
        if (!wl.isHeld) wl.acquire(10 * 60 * 60 * 1000L)
        loadToken++
        update()
        web.loadUrl(url)
    }

    private fun extract(t: Int, tries: Int) {
        if (t != loadToken) return
        web.evaluateJavascript(WtrJs.EXTRACT) { r ->
            if (t != loadToken) return@evaluateJavascript
            var paras: List<String> = emptyList()
            var nx: String? = null
            var ttl = "WTR TTS"
            try {
                val s = JSONTokener(r).nextValue() as? String ?: ""
                val o = JSONObject(s)
                val arr = o.getJSONArray("paras")
                val l = ArrayList<String>(arr.length())
                for (k in 0 until arr.length()) l.add(arr.getString(k))
                paras = l
                nx = o.optString("next").ifEmpty { null }
                ttl = o.optString("title").ifEmpty { "WTR TTS" }
            } catch (e: Exception) {
            }
            if (paras.joinToString("\n").length >= 200) {
                loadToken++
                nextUrl = nx
                title = ttl
                buildChunks(paras)
                idx = chunkFor(pendingStart)
                pendingStart = 0
                chapterUrl = curUrl
                play()
            } else if (tries < 12) {
                main.postDelayed({ extract(t, tries + 1) }, 1500)
            } else {
                title = "Couldn't find chapter text"
                pause()
            }
        }
    }

    private fun buildChunks(paras: List<String>) {
        val c = ArrayList<String>()
        val m = ArrayList<Int>()
        for ((pi, raw) in paras.withIndex()) {
            val p = raw.trim()
            if (p.none { it.isLetterOrDigit() }) continue
            if (p.length <= MAXLEN) {
                c.add(p)
                m.add(pi)
            } else {
                for (piece in splitLong(p)) {
                    c.add(piece)
                    m.add(pi)
                }
            }
        }
        chunkPara = m
        chunks = c
    }

    private fun splitLong(p: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        val rx = Regex("(?<=[.!?。！？])\\s+")
        for (s in p.split(rx)) {
            var x = s.trim()
            if (x.isEmpty()) continue
            while (x.length > MAXLEN) {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                out.add(x.substring(0, MAXLEN))
                x = x.substring(MAXLEN)
            }
            if (cur.isNotEmpty() && cur.length + 1 + x.length > MAXLEN) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(x)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    // First chunk at or after the given paragraph number (0 if there is none).
    private fun chunkFor(startPara: Int): Int {
        val m = chunkPara
        for (i in m.indices) {
            if (m[i] >= startPara) return i
        }
        return 0
    }

    private fun guessNext(url: String): String? {
        val m = Regex("(chapter[-_]?)(\\d+)", RegexOption.IGNORE_CASE).find(url) ?: return null
        val g = m.groups[2] ?: return null
        return url.replaceRange(g.range, (g.value.toInt() + 1).toString())
    }

    private fun goNext(finished: Boolean) {
        if (finished) Progress.clear(this, curUrl)
        val n = nextUrl ?: guessNext(curUrl)
        if (n == null) {
            title = "No next chapter found"
            pause()
            return
        }
        loadChapter(n, 0)
    }

    // ---- position ----------------------------------------------------------

    private fun savePos() {
        val cl = chunkPara
        val i = idx
        if (i < 0 || i >= cl.size) return
        curPara = cl[i]
        Progress.put(this, curUrl, curPara)
    }

    private fun publishProgress() {
        if (!playing) return
        savePos()
        onProgress?.invoke(curUrl, curPara)
    }

    // ---- playback ----------------------------------------------------------

    private fun speakFrom(start: Int) {
        val t = tts ?: return
        if (!ready) {
            main.postDelayed({ if (playing) speakFrom(start) }, 500)
            return
        }
        t.stop()
        gen++
        val list = chunks
        for (i in start until list.size) {
            t.speak(list[i], TextToSpeech.QUEUE_ADD, null, "${gen}_$i")
        }
    }

    private fun play() {
        if (chunks.isEmpty()) return
        requestFocus()
        if (!wl.isHeld) wl.acquire(10 * 60 * 60 * 1000L)
        playing = true
        speakFrom(idx)
        update()
    }

    private fun pause(abandon: Boolean = true) {
        playing = false
        gen++
        tts?.stop()
        if (wl.isHeld) wl.release()
        if (abandon) abandonFocus()
        update()
    }

    private fun stopAll() {
        pause()
        chapterUrl = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestFocus() {
        if (hasFocus) return
        val r = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener { c -> main.post { onFocusChange(c) } }
            .build()
            .also { focusReq = it }
        hasFocus = am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val r = focusReq
        if (hasFocus && r != null) am.abandonAudioFocusRequest(r)
        hasFocus = false
        resumeOnGain = false
    }

    private fun onFocusChange(c: Int) {
        when (c) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                if (playing) pause(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playing) {
                    resumeOnGain = true
                    pause(false)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain) {
                    resumeOnGain = false
                    play()
                }
            }
        }
    }

    // ---- notification / media session -------------------------------------

    private fun startFg() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NID, n)
        }
    }

    private fun update() {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NID, buildNotif())
    }

    private fun buildNotif(): Notification {
        fun pi(a: String, rc: Int) = PendingIntent.getService(
            this, rc, Intent(this, TtsService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val status = when {
            playing && chunks.isEmpty() -> "Loading…"
            playing -> "Reading…"
            else -> "Paused"
        }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                pi(A_TOGGLE, 1)
            )
            .addAction(android.R.drawable.ic_media_next, "Next", pi(A_NEXT, 2))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pi(A_STOP, 3))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onDestroy() {
        chapterUrl = ""
        playingNow = false
        onProgress = null
        tts?.shutdown()
        web.destroy()
        session.release()
        if (wl.isHeld) wl.release()
        super.onDestroy()
    }
}
