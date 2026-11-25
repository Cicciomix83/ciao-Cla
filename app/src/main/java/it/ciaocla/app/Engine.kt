package it.ciaocla.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
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

    // Motori di riconoscimento: prima Google, poi gli altri installati (su Samsung il predefinito e' spesso Bixby)
    private var engines: List<ComponentName?> = emptyList()
    private var engineIdx = 0
    private var badInARow = 0

    private fun findEngines(): List<ComponentName?> {
        val found = app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        val pref = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        val sorted = found.sortedBy { c -> pref.indexOf(c.packageName).let { if (it < 0) 99 else it } }
        return sorted + listOf<ComponentName?>(null) // null = predefinito del telefono
    }

    private fun engineLabel(c: ComponentName?): String = when {
        c == null -> "predefinito del telefono"
        c.packageName.startsWith("com.google.android.googlequicksearchbox") -> "Google"
        c.packageName == "com.google.android.tts" -> "Servizi vocali Google"
        c.packageName.contains("samsung") || c.packageName.contains("bixby") -> "Samsung"
        else -> c.packageName
    }
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
            js("window.onNativeError && window.onNativeError('norec')")
            return
        }
        listening = true
        if (engines.isEmpty()) { engines = findEngines(); engineIdx = 0 }
        badInARow = 0
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
        val comp = engines.getOrNull(engineIdx)
        val r = try {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(app, comp) else SpeechRecognizer.createSpeechRecognizer(app)
        } catch (e: Exception) {
            nextEngine(); main.postDelayed({ listenOnce() }, 500); return
        }
        js("window.onNativeEngine && window.onNativeEngine(" + JSONObject.quote(engineLabel(comp)) + ")")
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                badInARow = 0
                if (!text.isNullOrBlank()) {
                    js("window.onNativeSpeech && window.onNativeSpeech(" + JSONObject.quote(text) + ")")
                }
                again()
            }
            override fun onError(error: Int) {
                js("window.onNativeStatus && window.onNativeStatus($error)")
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) badInARow = 0
                else if (++badInARow >= 3) { nextEngine() }
                val wait = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1200L else 300L
                if (listening) main.postDelayed({ listenOnce() }, wait)
            }
            override fun onReadyForSpeech(params: Bundle?) { js("window.onNativeReady && window.onNativeReady()") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) js("window.onNativePartial && window.onNativePartial(" + JSONObject.quote(t) + ")")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    private fun nextEngine() {
        badInARow = 0
        if (engines.size > 1) engineIdx = (engineIdx + 1) % engines.size
    }

    private fun again() {
        if (listening) main.postDelayed({ listenOnce() }, 300)
    }
}
