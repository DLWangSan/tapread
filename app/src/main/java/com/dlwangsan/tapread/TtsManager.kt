package com.dlwangsan.tapread

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object TtsManager {
    private const val TAG = "TapReadTTS"

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private val pending = CopyOnWriteArrayList<String>()
    private var speakingListener: ((Boolean) -> Unit)? = null
    private var statusListener: ((Status) -> Unit)? = null
    private val initGeneration = AtomicInteger(0)
    private var fallbackEngineQueue: ArrayDeque<String> = ArrayDeque()
    private var currentEnginePackage: String? = null

    @Volatile
    var status: Status = Status.UNINITIALIZED
        private set(value) {
            field = value
            mainHandler.post { statusListener?.invoke(value) }
        }

    @Volatile
    var engineLabel: String = "未初始化"
        private set

    @Volatile
    var languageLabel: String = "-"
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (ready.get() && tts != null) return
        if (status == Status.INITIALIZING && tts != null) return
        startBind(context.applicationContext, Prefs.preferredEngine)
    }

    fun reinit(context: Context, enginePackage: String? = null) {
        if (!enginePackage.isNullOrBlank()) {
            Prefs.preferredEngine = enginePackage
        }
        shutdownInternal(keepStatus = Status.INITIALIZING)
        startBind(context.applicationContext, Prefs.preferredEngine)
    }

    private fun startBind(context: Context, preferred: String?) {
        appContext = context.applicationContext
        status = Status.INITIALIZING
        ready.set(false)
        languageLabel = "-"

        val engines = listEngines(context)
        fallbackEngineQueue.clear()

        val ordered = LinkedHashSet<String>()
        if (!preferred.isNullOrBlank()) ordered += preferred
        engines.firstOrNull { it.isDefault }?.name?.let { ordered += it }
        engines.forEach { ordered += it.name }
        // Common engines as last-resort package names even if query is empty.
        ordered += "com.google.android.tts"
        ordered += "com.iflytek.speechcloud"
        ordered += "com.baidu.duersdk.opensdk"
        ordered += "com.svox.pico"

        fallbackEngineQueue.addAll(ordered)
        Log.i(TAG, "startBind preferred=$preferred engines=${engines.map { it.name }} queue=$fallbackEngineQueue")

        // First try system default constructor (no package), then explicit packages.
        bindNext(context, tryDefaultConstructorFirst = true)
    }

    private fun bindNext(context: Context, tryDefaultConstructorFirst: Boolean) {
        val generation = initGeneration.incrementAndGet()
        shutdownTtsInstanceOnly()

        val listener = TextToSpeech.OnInitListener { resultCode ->
            mainHandler.post { onEngineInit(generation, resultCode) }
        }

        tts = try {
            if (tryDefaultConstructorFirst) {
                currentEnginePackage = null
                TextToSpeech(context.applicationContext, listener)
            } else {
                val next = fallbackEngineQueue.removeFirstOrNull()
                if (next == null) {
                    ready.set(false)
                    engineLabel = "未检测到可用语音引擎"
                    status = Status.NO_ENGINE
                    return
                }
                currentEnginePackage = next
                Log.i(TAG, "Trying engine package=$next")
                TextToSpeech(context.applicationContext, listener, next)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed creating TextToSpeech", t)
            // Continue fallback.
            bindNext(context, tryDefaultConstructorFirst = false)
            return
        }
    }

    private fun onEngineInit(generation: Int, resultCode: Int) {
        if (generation != initGeneration.get()) {
            Log.w(TAG, "Ignore stale onInit gen=$generation current=${initGeneration.get()}")
            return
        }
        val engine = tts
        if (engine == null) {
            status = Status.ERROR
            return
        }

        if (resultCode != TextToSpeech.SUCCESS) {
            Log.w(TAG, "onInit failed code=$resultCode package=$currentEnginePackage")
            val ctx = appContext
            if (ctx != null) {
                // If default constructor failed, continue with explicit packages.
                bindNext(ctx, tryDefaultConstructorFirst = false)
            } else {
                ready.set(false)
                status = Status.NO_ENGINE
                engineLabel = "未检测到可用语音引擎"
            }
            return
        }

        engineLabel = resolveEngineLabel(engine, currentEnginePackage)
        currentEnginePackage?.let { Prefs.preferredEngine = it }
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
                ready.set(true) // still allow speak attempts; some OEMs mis-report
                status = Status.LANG_MISSING
            }
            else -> {
                ready.set(true)
                status = Status.READY
            }
        }

        Log.i(TAG, "TTS ready engine=$engineLabel lang=$languageLabel status=$status")
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
        val services = runCatching {
            pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        val defaultEngine = android.provider.Settings.Secure.getString(
            context.contentResolver,
            SettingsSecureTts.DEFAULT_SYNTH
        ) ?: runCatching { tts?.defaultEngine }.getOrNull()

        val fromQuery = services.mapNotNull {
            val pkg = it.serviceInfo?.packageName ?: return@mapNotNull null
            EngineInfo(
                name = pkg,
                label = it.loadLabel(pm)?.toString() ?: pkg,
                isDefault = pkg == defaultEngine
            )
        }

        if (fromQuery.isNotEmpty()) return fromQuery.distinctBy { it.name }

        val live = tts?.engines.orEmpty().map {
            EngineInfo(
                name = it.name,
                label = it.label?.toString() ?: it.name,
                isDefault = it.name == defaultEngine
            )
        }
        return live.distinctBy { it.name }
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

        if (tts == null || status == Status.UNINITIALIZED) {
            appContext?.let { init(it) }
        }

        if (!ready.get()) {
            if (status == Status.INITIALIZING || status == Status.UNINITIALIZED) {
                pending += text
                return true
            }
            // LANG_MISSING: still try
            if (status != Status.LANG_MISSING) return false
        }

        val engine = tts ?: return false
        return runCatching {
            engine.setSpeechRate(Prefs.speechRate)
            engine.setPitch(Prefs.speechPitch)
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = "tapread-${System.currentTimeMillis()}"
            val params = Bundle()
            val result = engine.speak(text, mode, params, utteranceId)
            result == TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    fun stop() {
        pending.clear()
        tts?.stop()
        speakingListener?.invoke(false)
    }

    fun shutdown() {
        shutdownInternal(keepStatus = Status.UNINITIALIZED)
    }

    private fun shutdownInternal(keepStatus: Status) {
        initGeneration.incrementAndGet()
        pending.clear()
        ready.set(false)
        shutdownTtsInstanceOnly()
        status = keepStatus
        if (keepStatus == Status.UNINITIALIZED) {
            engineLabel = "未初始化"
            languageLabel = "-"
        }
    }

    private fun shutdownTtsInstanceOnly() {
        val old = tts
        tts = null
        runCatching {
            old?.stop()
            old?.shutdown()
        }
    }

    fun openSystemTtsSettings(context: Context): Boolean {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_SETTINGS)
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
        runCatching { engine.setAudioAttributes(attrs) }
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
            val result = runCatching { engine.isLanguageAvailable(locale) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                runCatching { engine.language = locale }
                preferChineseVoice(engine, locale)
                return result
            }
            if (result > best) best = result
        }
        runCatching { engine.language = Locale.SIMPLIFIED_CHINESE }
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
            runCatching { engine.voice = match }
        }
    }

    private fun resolveEngineLabel(engine: TextToSpeech, packageName: String?): String {
        val engines = runCatching { engine.engines }.getOrNull().orEmpty()
        val current = packageName
            ?: runCatching { engine.defaultEngine }.getOrNull()
        val info = engines.firstOrNull { it.name == current } ?: engines.firstOrNull()
        val label = info?.label?.toString()
        return when {
            !label.isNullOrBlank() -> label
            !current.isNullOrBlank() -> current
            else -> "系统默认引擎"
        }
    }

    private object SettingsSecureTts {
        const val DEFAULT_SYNTH = "tts_default_synth"
    }
}
