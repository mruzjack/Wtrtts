package com.paw.wtrtts

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.view.inputmethod.EditorInfo
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
// Screen: a simple browser. Log in / browse WTR Lab, open a chapter, tap Read.
// ---------------------------------------------------------------------------
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
    private var speedIdx = 0

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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    urlBar.setText(url)
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
        row.addView(btn("▶ Read page") {
            send(TtsService.A_LOAD, url = web.url, rate = speeds[speedIdx])
        })
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

    private fun send(action: String, url: String? = null, rate: Float? = null) {
        val i = Intent(this, TtsService::class.java).setAction(action)
        if (url != null) i.putExtra("url", url)
        if (rate != null) i.putExtra("rate", rate)
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
        private const val CH = "wtr_tts"
        private const val NID = 1

        // Heuristic extractor: finds the block with the most paragraph text,
        // and the "next chapter" link. May need tuning for WTR Lab's markup.
        private const val JS = """
(function(){
  function txt(e){return (e.innerText||'').trim();}
  var best=null,bl=0;
  var els=document.querySelectorAll('div,article,section,main');
  for(var i=0;i<els.length;i++){
    var e=els[i],ps=0,len=0;
    for(var c=e.firstElementChild;c;c=c.nextElementSibling){
      if(c.tagName==='P'||(c.tagName==='DIV'&&c.children.length==0)){len+=txt(c).length;ps++;}
    }
    if(ps>=3&&len>bl){bl=len;best=e;}
  }
  var text=best?Array.from(best.children).map(txt).filter(function(s){return s.length>0}).join('\n'):'';
  var a=document.querySelector('a[rel=next]');
  if(!a){
    var as=document.querySelectorAll('a');
    for(var j=0;j<as.length;j++){if(/^\s*next/i.test(as[j].innerText||'')){a=as[j];break;}}
  }
  var nx=(a&&/^http/.test(a.href))?a.href:'';
  return JSON.stringify({text:text,next:nx,title:document.title||''});
})()
"""
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var web: WebView
    private lateinit var session: MediaSessionCompat
    private lateinit var wl: PowerManager.WakeLock
    private lateinit var am: AudioManager
    private var focusReq: AudioFocusRequest? = null

    private var chunks: List<String> = emptyList()
    private var idx = 0
    private var playing = false
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
                override fun onSkipToNext() { goNext() }
            })
            isActive = true
        }

        web = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
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
                            p[1].toIntOrNull()?.let { idx = it }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        val p = utteranceId?.split("_") ?: return
                        if (p.size != 2) return
                        if (p[0].toIntOrNull() == gen &&
                            p[1].toIntOrNull() == chunks.size - 1 && playing
                        ) {
                            main.post { goNext() }
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
                i.getStringExtra("url")?.let { loadChapter(it) }
            }
            A_TOGGLE -> if (playing) pause() else if (chunks.isNotEmpty()) play()
            A_NEXT -> goNext()
            A_RATE -> {
                rate = i.getFloatExtra("rate", 1f)
                tts?.setSpeechRate(rate)
                if (playing && chunks.isNotEmpty()) speakFrom(idx)
            }
            A_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // ---- chapter loading ---------------------------------------------------

    private fun loadChapter(url: String) {
        gen++
        tts?.stop()
        chunks = emptyList()
        idx = 0
        curUrl = url
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
        web.evaluateJavascript(JS) { r ->
            if (t != loadToken) return@evaluateJavascript
            var text = ""
            var nx: String? = null
            var ttl = "WTR TTS"
            try {
                val s = JSONTokener(r).nextValue() as? String ?: ""
                val o = JSONObject(s)
                text = o.optString("text")
                nx = o.optString("next").ifEmpty { null }
                ttl = o.optString("title").ifEmpty { "WTR TTS" }
            } catch (e: Exception) {
            }
            if (text.length >= 200) {
                loadToken++
                nextUrl = nx
                title = ttl
                chunks = split(text)
                idx = 0
                play()
            } else if (tries < 12) {
                main.postDelayed({ extract(t, tries + 1) }, 1500)
            } else {
                title = "Couldn't find chapter text"
                pause()
            }
        }
    }

    private fun split(t: String): List<String> {
        val out = ArrayList<String>()
        val rx = Regex("(?<=[.!?。！？])\\s+")
        for (p in t.split("\n")) {
            val s = p.trim()
            if (s.isEmpty()) continue
            for (q in s.split(rx)) {
                var x = q.trim()
                while (x.length > 3500) {
                    out.add(x.substring(0, 3500))
                    x = x.substring(3500)
                }
                if (x.isNotEmpty()) out.add(x)
            }
        }
        return out
    }

    private fun guessNext(url: String): String? {
        val m = Regex("(chapter[-_]?)(\\d+)", RegexOption.IGNORE_CASE).find(url) ?: return null
        val g = m.groups[2] ?: return null
        return url.replaceRange(g.range, (g.value.toInt() + 1).toString())
    }

    private fun goNext() {
        val n = nextUrl ?: guessNext(curUrl)
        if (n == null) {
            title = "No next chapter found"
            pause()
            return
        }
        loadChapter(n)
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
        for (i in start until chunks.size) {
            t.speak(chunks[i], TextToSpeech.QUEUE_ADD, null, "${gen}_$i")
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

    private fun pause() {
        playing = false
        gen++
        tts?.stop()
        if (wl.isHeld) wl.release()
        update()
    }

    private fun stopAll() {
        pause()
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestFocus() {
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener { c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS ||
                    c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    main.post { pause() }
                }
            }
            .build()
        focusReq = r
        am.requestAudioFocus(r)
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
            .setOngoing(playing)
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
        tts?.shutdown()
        web.destroy()
        session.release()
        if (wl.isHeld) wl.release()
        super.onDestroy()
    }
}
