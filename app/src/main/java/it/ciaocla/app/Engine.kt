package it.ciaocla.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/** Tiene in vita pagina, riconoscimento vocale e voce anche quando la schermata è chiusa. */
object Engine : TextToSpeech.OnInitListener {
    private lateinit var app: Context
    private var wrapper: MutableContextWrapper? = null
    private var web: WebView? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    var activity: MainActivity? = null
    var listening = false
        private set

    fun webFor(act: Activity): WebView {
        app = act.applicationContext
        var w = web
        if (w == null) {
            val wrap = MutableContextWrapper(act)
            wrapper = wrap
            w = WebView(wrap)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.addJavascriptInterface(Bridge(), "AndroidBridge")
            w.loadUrl("file:///android_asset/index.html")
            web = w
            tts = TextToSpeech(app, this)
        } else {
            wrapper?.setBaseContext(act)
        }
        (w.parent as? ViewGroup)?.removeView(w)
        return w
    }

    fun detach(act: Activity) {
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        if (wrapper?.baseContext === act) wrapper?.setBaseContext(app)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.ITALIAN
    }

    fun js(code: String) = main.post { web?.evaluateJavascript(code, null) }

    fun startService(ctx: Context) {
        val i = Intent(ctx, ListenService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    fun stopService(ctx: Context) {
        ctx.stopService(Intent(ctx, ListenService::class.java))
    }

    class Bridge {
        @JavascriptInterface fun start() {
            Engine.main.post {
                val a = Engine.activity
                if (a != null) a.askPermissionsAndStart() else Engine.startService(Engine.app)
            }
        }
        @JavascriptInterface fun stop() { Engine.main.post { Engine.stopService(Engine.app) } }
        @JavascriptInterface fun speak(text: String) {
            Engine.main.post { Engine.tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cla") }
        }
        @JavascriptInterface fun share(text: String) {
            Engine.main.post {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Report Ciao Cla")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(send, "Condividi il report")
                val a = Engine.activity
                if (a != null) a.startActivity(chooser)
                else { chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); Engine.app.startActivity(chooser) }
            }
        }
    }

    fun startListening(ctx: Context) {
        app = ctx.applicationContext
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            js("window.onNativeError && window.onNativeError()")
            return
        }
        listening = true
        js("window.onNativeStarted && window.onNativeStarted()")
        listenOnce()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        js("window.onNativeStopped && window.onNativeStopped()")
    }

    private fun listenOnce() {
        if (!listening) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    js("window.onNativeSpeech && window.onNativeSpeech(" + JSONObject.quote(text) + ")")
                }
                again()
            }
            override fun onError(error: Int) { again() }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    private fun again() {
        if (listening) main.postDelayed({ listenOnce() }, 300)
    }
}
