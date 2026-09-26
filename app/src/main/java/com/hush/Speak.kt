package com.hush

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Type to speak (persona C): the phone says what the deaf user types, so they can answer the door or a caller.
 * On-device text-to-speech. While it speaks, the household alerts treat the sound as our own (Engine reads [speaking]).
 */
object Speak {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    @Volatile var speaking = false
        private set

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            HLog.d("SPEAK: tts ready=$ready")
            if (ready) {
                val r = tts?.setLanguage(Locale("en", "IN"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.ENGLISH)
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { speaking = false }
                })
                pending?.let { say(context, it) }; pending = null
            }
        }
    }

    fun say(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (tts == null) init(context)
        if (!ready) { pending = t; return }
        HLog.d("SPEAK: '$t'")
        speaking = true
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "hush-${System.currentTimeMillis()}")
    }

    fun shutdown() { try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { HLog.d("SPEAK: shutdown ignored $e") }; tts = null; ready = false; speaking = false }
}
