package com.dlwangsan.tapread

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dlwangsan.tapread.clipboard.ClipboardMonitorService
import com.dlwangsan.tapread.databinding.ActivityMainBinding
import com.dlwangsan.tapread.overlay.OverlayService
import com.google.android.material.slider.Slider
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TtsManager.init(this)
        setupModeSections()
        setupTtsControls()
        setupModeControls()

        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        when (TtsManager.status) {
            TtsManager.Status.UNINITIALIZED,
            TtsManager.Status.NO_ENGINE,
            TtsManager.Status.ERROR -> TtsManager.reinit(this)
            TtsManager.Status.LANG_MISSING -> {
                // Keep current engine; just refresh labels after user may have installed data.
                refreshTtsUi(TtsManager.status)
            }
            else -> Unit
        }
        refreshUi()
        refreshTtsUi(TtsManager.status)
    }

    override fun onDestroy() {
        TtsManager.setStatusListener(null)
        TtsManager.setSpeakingListener(null)
        super.onDestroy()
    }

    private fun setupModeSections() {
        val legacy = DeviceCapabilities.canBackgroundClipboardRead
        binding.sectionLegacyAutoRead.visibility = if (legacy) View.VISIBLE else View.GONE
        binding.sectionOverlay.visibility = if (legacy) View.GONE else View.VISIBLE
        binding.sectionAccessibility.visibility = if (legacy) View.GONE else View.VISIBLE
        binding.taglineText.setText(
            if (legacy) R.string.tagline_legacy else R.string.tagline
        )
    }

    private fun setupModeControls() {
        binding.switchAutoRead.isChecked = Prefs.autoReadEnabled
        binding.switchLegacyAutoRead.isChecked = Prefs.autoReadEnabled

        binding.btnOverlayPermission.setOnClickListener {
            startActivity(PermissionHelper.overlayPermissionIntent(this))
        }
        binding.btnToggleOverlay.setOnClickListener { toggleOverlay() }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
        }
        binding.btnToggleClipboardMonitor.setOnClickListener { toggleClipboardMonitor() }

        binding.switchAutoRead.setOnCheckedChangeListener { _, checked ->
            Prefs.autoReadEnabled = checked
            binding.switchLegacyAutoRead.isChecked = checked
        }
        binding.switchLegacyAutoRead.setOnCheckedChangeListener { _, checked ->
            Prefs.autoReadEnabled = checked
            binding.switchAutoRead.isChecked = checked
        }
    }

    private fun setupTtsControls() {
        val rate = Prefs.speechRate
        val pitch = Prefs.speechPitch
        binding.speechRateSlider.value = rate
        binding.speechPitchSlider.value = pitch
        updateSpeechRateLabel(rate)
        updateSpeechPitchLabel(pitch)

        binding.speechRateSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                TtsManager.applySpeechRate(value)
                updateSpeechRateLabel(value)
            }
        }
        binding.speechPitchSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                TtsManager.applyPitch(value)
                updateSpeechPitchLabel(value)
            }
        }

        binding.btnTestSpeak.setOnClickListener { testSpeak() }
        binding.btnStopSpeak.setOnClickListener { TtsManager.stop() }

        binding.btnOpenTtsSettings.setOnClickListener {
            if (!TtsManager.openSystemTtsSettings(this)) {
                Toast.makeText(this, R.string.tts_settings_missing, Toast.LENGTH_LONG).show()
            }
        }
        binding.btnInstallTtsData.setOnClickListener {
            if (!TtsManager.openInstallTtsData(this)) {
                TtsManager.openSystemTtsSettings(this)
            }
        }
        binding.btnRefreshTts.setOnClickListener {
            TtsManager.reinit(this)
            refreshTtsUi(TtsManager.status)
        }

        TtsManager.setStatusListener { status ->
            runOnUiThread { refreshTtsUi(status) }
        }
        TtsManager.setSpeakingListener { speaking ->
            runOnUiThread {
                binding.btnTestSpeak.isEnabled = !speaking
            }
        }
        refreshTtsUi(TtsManager.status)
    }

    private fun testSpeak() {
        val sample = if (DeviceCapabilities.canBackgroundClipboardRead) {
            getString(R.string.test_sample_legacy)
        } else {
            getString(R.string.test_sample)
        }
        val ok = TtsManager.speak(sample)
        if (!ok) {
            Toast.makeText(this, R.string.tts_speak_failed, Toast.LENGTH_LONG).show()
            refreshTtsUi(TtsManager.status)
        }
    }

    private fun refreshTtsUi(status: TtsManager.Status) {
        val engines = TtsManager.listEngines(this)
        val engineName = when {
            TtsManager.engineLabel.isNotBlank() &&
                TtsManager.engineLabel != "未初始化" -> TtsManager.engineLabel
            engines.isNotEmpty() -> {
                val preferred = engines.firstOrNull { it.isDefault } ?: engines.first()
                preferred.label
            }
            else -> "未检测到"
        }

        binding.ttsEngineText.text = getString(R.string.tts_engine_label, engineName)
        binding.ttsLangText.text = buildString {
            append(getString(R.string.tts_lang_label, TtsManager.languageLabel))
            if (engines.isNotEmpty()) {
                append("  ·  ")
                append(getString(R.string.engine_count, engines.size))
            }
        }

        val warn = when (status) {
            TtsManager.Status.READY -> null
            TtsManager.Status.INITIALIZING,
            TtsManager.Status.UNINITIALIZED -> getString(R.string.tts_status_initializing)
            TtsManager.Status.NO_ENGINE -> getString(R.string.tts_status_no_engine)
            TtsManager.Status.LANG_MISSING -> getString(R.string.tts_status_lang_missing)
            TtsManager.Status.ERROR -> getString(R.string.tts_status_error)
        }

        if (warn == null) {
            binding.ttsWarnText.visibility = View.GONE
        } else {
            binding.ttsWarnText.visibility = View.VISIBLE
            binding.ttsWarnText.text = warn
        }

        val canSpeak = status == TtsManager.Status.READY ||
            status == TtsManager.Status.INITIALIZING
        binding.btnTestSpeak.isEnabled = canSpeak || status == TtsManager.Status.UNINITIALIZED
    }

    private fun maybeRequestNotificationPermission() {
        if (!PermissionHelper.notificationPermissionNeeded()) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            refreshUi()
            return
        }
        if (!PermissionHelper.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_LONG).show()
            startActivity(PermissionHelper.overlayPermissionIntent(this))
            return
        }
        if (PermissionHelper.notificationPermissionNeeded() &&
            !PermissionHelper.areNotificationsEnabled(this)
        ) {
            Toast.makeText(this, R.string.need_notification_permission, Toast.LENGTH_LONG).show()
            maybeRequestNotificationPermission()
            return
        }
        OverlayService.start(this)
        binding.root.postDelayed({ refreshUi() }, 400)
    }

    private fun toggleClipboardMonitor() {
        if (ClipboardMonitorService.isRunning) {
            ClipboardMonitorService.stop(this)
            refreshUi()
            return
        }
        ClipboardMonitorService.start(this)
        binding.root.postDelayed({ refreshUi() }, 400)
    }

    private fun refreshUi() {
        val legacy = DeviceCapabilities.canBackgroundClipboardRead
        val overlayOn = OverlayService.isRunning
        val monitorOn = ClipboardMonitorService.isRunning
        val a11yOn = PermissionHelper.isAccessibilityEnabled(this)

        binding.statusTitle.text = if (legacy) {
            if (monitorOn) {
                getString(R.string.status_clipboard_monitor_on)
            } else {
                getString(R.string.status_clipboard_monitor_off)
            }
        } else {
            if (overlayOn) {
                getString(R.string.status_overlay_on)
            } else {
                getString(R.string.status_overlay_off)
            }
        }

        binding.statusText.text = buildString {
            append("Android ${android.os.Build.VERSION.RELEASE}")
            append("  ·  ")
            append(
                if (legacy) {
                    getString(R.string.status_mode_legacy)
                } else {
                    getString(R.string.status_mode_modern)
                }
            )
            if (!legacy) {
                append('\n')
                append(
                    if (a11yOn) {
                        getString(R.string.status_a11y_on)
                    } else {
                        getString(R.string.status_a11y_off)
                    }
                )
                append("  ·  ")
                append(
                    if (PermissionHelper.canDrawOverlays(this@MainActivity)) {
                        "悬浮窗已授权"
                    } else {
                        "悬浮窗未授权"
                    }
                )
            }
        }

        binding.btnToggleOverlay.text =
            if (overlayOn) getString(R.string.stop_overlay) else getString(R.string.start_overlay)
        binding.btnToggleClipboardMonitor.text =
            if (monitorOn) {
                getString(R.string.stop_clipboard_monitor)
            } else {
                getString(R.string.start_clipboard_monitor)
            }
    }

    private fun updateSpeechRateLabel(rate: Float) {
        binding.speechRateLabel.text =
            getString(R.string.speech_rate) + "  " + String.format(Locale.US, "%.1fx", rate)
    }

    private fun updateSpeechPitchLabel(pitch: Float) {
        binding.speechPitchLabel.text =
            getString(R.string.speech_pitch) + "  " + String.format(Locale.US, "%.1fx", pitch)
    }
}
