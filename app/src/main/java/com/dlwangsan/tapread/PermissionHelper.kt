package com.dlwangsan.tapread

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.dlwangsan.tapread.accessibility.TapReadAccessibilityService

object PermissionHelper {
    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun overlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun notificationPermissionNeeded(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun accessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (TapReadAccessibilityService.isRunning()) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val pkg = context.packageName
        val className = TapReadAccessibilityService::class.java.name
        return enabled.any { info ->
            val id = info.id.orEmpty()
            id.equals("$pkg/$className", ignoreCase = true) ||
                id.equals("$pkg/.accessibility.TapReadAccessibilityService", ignoreCase = true) ||
                id.endsWith("/.accessibility.TapReadAccessibilityService") ||
                id.endsWith("/TapReadAccessibilityService") ||
                info.resolveInfo?.serviceInfo?.name == className
        }
    }
}
