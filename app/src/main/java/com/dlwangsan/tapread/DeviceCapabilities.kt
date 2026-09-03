package com.dlwangsan.tapread

import android.os.Build

object DeviceCapabilities {
    /** Android 10 (API 29) 起后台普通 App 无法读取剪贴板。 */
    val needsFocusOrAccessibilityForClipboard: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 9 及以下可直接后台监听剪贴板并朗读。 */
    val canBackgroundClipboardRead: Boolean
        get() = !needsFocusOrAccessibilityForClipboard
}
