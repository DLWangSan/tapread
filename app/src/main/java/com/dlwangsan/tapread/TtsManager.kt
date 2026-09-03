package com.dlwangsan.tapread

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object TtsManager : TextToSpeech.OnInitListener {
    enum class Status {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        NO_ENGINE,
        LANG_MISSING,
        ERROR
    }

    data class EngineInfo(
        val name: String,
        val label: String,
        val isDefault: Boolean
    )

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private val pending = CopyOnWriteArrayList<String>()
    private var speakingListener: ((Boolean) -> Unit)? = null
    private var statusListener: ((Status) -> Unit)? = null

    @Volatile
    var status: Status = Status.UNINITIALIZED
        private set(value) {
            field = value
            statusListener?.invoke(value)
        }

    @Volatile
    var engineLabel: String = "未初始化"
        private set

    @Volatile
    var languageLabel: String = "-"
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (tts != null || status == Status.INITIALIZING) return
        status = Status.INITIALIZING
        val preferred = Prefs.preferredEngine
        tts = if (preferred.isNullOrBlank()) {
            TextToSpeech(context.applicationContext, this)
        } else {
            TextToSpeech(context.applicationContext, this, preferred)
        }
    }

    fun reinit(context: Context, enginePackage: String? = null) {
        shutdown()
        if (!enginePackage.isNullOrBlank()) {
            Prefs.preferredEngine = enginePackage
        }
        init(context)
    }

    override fun onInit(resultCode: Int) {
        val engine = tts
        if (engine == null) {
            status = Status.ERROR
            return
        }
        if (resultCode != TextToSpeech.SUCCESS) {
            ready.set(false)
            status = Status.NO_ENGINE
            engineLabel = "未检测到可用语音引擎"
            return
        }

        engineLabel = resolveEngineLabel(engine)
        applyAudioAttributes(engine)
        engine.setSpeechRate(Prefs.speechRate)
        engine.setPitch(Prefs.speechPitch)

        val langResult = pickChineseLanguage(engine)
        languageLabel = when (langResult) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "中文可用"
            TextToSpeech.LANG_MISSING_DATA -> "缺少中文语音数据"
            else -> "当前引擎可能不支持中文"
        }

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

            override fun onError(utteranceId: String?, errorCode: Int) {
                speakingListener?.invoke(false)
            }
        })

        when (langResult) {
            TextToSpeech.LANG_MISSING_DATA -> {
                ready.set(false)
                status = Status.LANG_MISSING
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                // Still allow speak; some engines accept Chinese text without reporting support.
                ready.set(true)
                status = Status.READY
            }
            else -> {
                ready.set(true)
                status = Status.READY
            }
        }

        flushPending()
    }

    fun setSpeakingListener(listener: ((Boolean) -> Unit)?) {
        speakingListener = listener
    }

    fun setStatusListener(listener: ((Status) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status)
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun listEngines(context: Context): List<EngineInfo> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        val defaultEngine = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "tts_default_synth"
        )
        if (services.isNullOrEmpty()) {
            val live = tts?.engines.orEmpty()
            return live.map {
                EngineInfo(
                    name = it.name,
                    label = it.label?.toString() ?: it.name,
                    isDefault = it.name == defaultEngine || it.name == tts?.defaultEngine
                )
            }
        }
        return services.map {
            val pkg = it.serviceInfo.packageName
            EngineInfo(
                name = pkg,
                label = it.loadLabel(pm)?.toString() ?: pkg,
                isDefault = pkg == defaultEngine
            )
        }
    }

    fun applySpeechRate(rate: Float) {
        Prefs.speechRate = rate
        tts?.setSpeechRate(rate)
    }

    fun applyPitch(pitch: Float) {
        Prefs.speechPitch = pitch
        tts?.setPitch(pitch)
    }

    fun speak(raw: String, flush: Boolean = true): Boolean {
        val text = TextCleaner.clean(raw)
        if (text.isBlank()) return false

        if (tts == null) {
            appContext?.let { init(it) }
        }

        if (!ready.get()) {
            if (status == Status.INITIALIZING || status == Status.UNINITIALIZED) {
                pending += text
                return true
            }
            return false
        }

        val engine = tts ?: return false
        engine.setSpeechRate(Prefs.speechRate)
        engine.setPitch(Prefs.speechPitch)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tapread-${System.currentTimeMillis()}")
        }
        val result = engine.speak(text, mode, params, "tapread-${System.currentTimeMillis()}")
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        pending.clear()
        tts?.stop()
        speakingListener?.invoke(false)
    }

    fun shutdown() {
        pending.clear()
        ready.set(false)
        status = Status.UNINITIALIZED
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    fun openSystemTtsSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    fun openInstallTtsData(context: Context): Boolean {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    fun openCheckTtsData(context: Context): Boolean {
        val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    private fun flushPending() {
        if (!ready.get()) return
        val items = pending.toList()
        pending.clear()
        items.forEachIndexed { index, text ->
            speak(text, flush = index == 0)
        }
    }

    private fun applyAudioAttributes(engine: TextToSpeech) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(attrs)
    }

    private fun pickChineseLanguage(engine: TextToSpeech): Int {
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale.CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.getDefault()
        )
        var best = TextToSpeech.LANG_NOT_SUPPORTED
        for (locale in candidates) {
            val result = engine.isLanguageAvailable(locale)
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = locale
                preferChineseVoice(engine, locale)
                return result
            }
            if (result > best) best = result
        }
        // Last resort: set anyway.
        engine.language = Locale.SIMPLIFIED_CHINESE
        return best
    }

    private fun preferChineseVoice(engine: TextToSpeech, locale: Locale) {
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull() ?: return
        val match = voices.firstOrNull {
            !it.isNetworkConnectionRequired &&
                it.locale.language.equals(locale.language, ignoreCase = true)
        } ?: voices.firstOrNull {
            it.locale.language.equals(locale.language, ignoreCase = true)
        }
        if (match != null) {
            engine.voice = match
        }
    }

    private fun resolveEngineLabel(engine: TextToSpeech): String {
        val current = runCatching { engine.defaultEngine }.getOrNull()
        val info = engine.engines?.firstOrNull { it.name == current }
            ?: engine.engines?.firstOrNull()
        val label = info?.label?.toString()
        return when {
            !label.isNullOrBlank() -> label
            !current.isNullOrBlank() -> current
            else -> "系统默认引擎"
        }
    }
}
