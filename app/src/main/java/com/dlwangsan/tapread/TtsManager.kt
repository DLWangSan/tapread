package com.dlwangsan.tapread

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object TtsManager : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var speakingListener: ((Boolean) -> Unit)? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            ready.set(false)
            return
        }
        val result = engine.setLanguage(Locale.CHINA)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.CHINESE)
        }
        engine.setSpeechRate(Prefs.speechRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speakingListener?.invoke(true)
            }

            override fun onDone(utteranceId: String?) {
                speakingListener?.invoke(false)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speakingListener?.invoke(false)
            }
        })
        ready.set(true)
    }

    fun setSpeakingListener(listener: ((Boolean) -> Unit)?) {
        speakingListener = listener
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun applySpeechRate(rate: Float) {
        Prefs.speechRate = rate
        tts?.setSpeechRate(rate)
    }

    fun speak(raw: String, flush: Boolean = true): Boolean {
        val text = TextCleaner.clean(raw)
        if (text.isBlank()) return false
        val engine = tts ?: return false
        if (!ready.get()) return false
        engine.setSpeechRate(Prefs.speechRate)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = engine.speak(text, mode, null, "tapread-${System.currentTimeMillis()}")
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        tts?.stop()
        speakingListener?.invoke(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }
}
