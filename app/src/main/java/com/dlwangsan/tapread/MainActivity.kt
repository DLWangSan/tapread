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

        setupModeSections()
        val rate = Prefs.speechRate
        binding.speechRateSlider.value = rate
        updateSpeechRateLabel(rate)

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

        binding.speechRateSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (fromUser) {
                TtsManager.applySpeechRate(value)
                updateSpeechRateLabel(value)
            }
        }

        binding.btnTestSpeak.setOnClickListener {
            val sample = if (DeviceCapabilities.canBackgroundClipboardRead) {
                getString(R.string.test_sample_legacy)
            } else {
                getString(R.string.test_sample)
            }
            TtsManager.speak(sample)
        }
        binding.btnStopSpeak.setOnClickListener { TtsManager.stop() }

        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
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

        binding.statusText.text = buildString {
            append("系统：Android ${android.os.Build.VERSION.RELEASE}")
            append('\n')
            if (legacy) {
                append(
                    if (monitorOn) {
                        getString(R.string.status_clipboard_monitor_on)
                    } else {
                        getString(R.string.status_clipboard_monitor_off)
                    }
                )
                append('\n')
                append(getString(R.string.status_mode_legacy))
            } else {
                append(
                    if (overlayOn) {
                        getString(R.string.status_overlay_on)
                    } else {
                        getString(R.string.status_overlay_off)
                    }
                )
                append('\n')
                append(
                    if (a11yOn) {
                        getString(R.string.status_a11y_on)
                    } else {
                        getString(R.string.status_a11y_off)
                    }
                )
                append('\n')
                append(
                    if (PermissionHelper.canDrawOverlays(this@MainActivity)) {
                        "悬浮窗权限：已授予"
                    } else {
                        "悬浮窗权限：未授予"
                    }
                )
                append('\n')
                append(getString(R.string.status_mode_modern))
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
}
