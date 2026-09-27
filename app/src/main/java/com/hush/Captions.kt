package com.hush

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Live captions for a deaf user (persona C): Android's own speech recogniser, on-device when the phone has the
 * offline model (the intent asks for offline first), restarted after every phrase so it runs continuously.
 * The recogniser lives in another app's process and Android gives the microphone to one app at a time, so while
 * captions run our own listening is paused (Engine.setCaptions). Main thread only (the recogniser requires it).
 * Nothing leaves the phone when the offline model is present; if the phone falls back to online recognition the
 * status line says so and the person can stop it.
 */
class Captions(private val context: Context, private val onText: (String, Boolean) -> Unit, private val onStatus: (String) -> Unit,
               private val onStopped: () -> Unit = {}) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    var running = false
        private set
    private var restarts = 0
    /** Languages tried in order when the offline model refuses one (27 Sep 05:29: en-IN "not available offline" on the I2501; its on-device model is en-US). */
    private val languages = listOf("en-IN", "en-US", "")
    private var languageIndex = 0

    fun start(): Boolean {
        if (running) return true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("No speech recogniser on this phone (Google's Speech Services is missing)"); HLog.d("CAPTIONS: no recogniser"); return false
        }
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) { onStatus("Could not start the recogniser: $e"); HLog.d("CAPTIONS: create failed $e"); return false }
        recognizer?.setRecognitionListener(listener)
        running = true; restarts = 0; languageIndex = 0
        HLog.d("CAPTIONS: started (onDevice=$onDevice)")
        onStatus(if (onDevice) "Captions on · on-device recogniser" else "Captions on · phone's speech service (may need the offline English pack: Settings → Google → Speech)")
        listen()
        return true
    }

    private fun listen() {
        if (!running) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        val lang = languages[languageIndex]
        if (lang.isNotEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        try { recognizer?.startListening(i) } catch (e: Exception) { HLog.d("CAPTIONS: startListening failed $e"); onStatus("Recogniser error: $e") }
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        restarts++
        main.postDelayed({ listen() }, delayMs)
    }

    fun stop() {
        if (!running) return
        running = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (e: Exception) { HLog.d("CAPTIONS: stop ignored $e") }
        recognizer = null
        HLog.d("CAPTIONS: stopped after $restarts restarts")
        onStatus("Captions off")
        onStopped()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (t.isNotBlank()) onText(t, false)
        }
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) { onText(t, true); HLog.d("CAPTIONS: '$t'") }
            restart(100)
        }
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(1000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onStatus("Microphone permission missing for captions"); stop() }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (languageIndex < languages.size - 1) {
                        val tried = languages[languageIndex]
                        languageIndex++
                        val next = languages[languageIndex].ifEmpty { "the phone's default language" }
                        HLog.d("CAPTIONS: $tried not available offline, trying $next")
                        onStatus("Captions on · $next")
                        restart(100)
                    } else { onStatus("No offline English on this phone; download it in Settings → Google → Speech → Offline"); stop() }
                }
                SpeechRecognizer.ERROR_CLIENT -> restart(500)
                else -> { HLog.d("CAPTIONS: error $error, restarting"); restart(1000) }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
