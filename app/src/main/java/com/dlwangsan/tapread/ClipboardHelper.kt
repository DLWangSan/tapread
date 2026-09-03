package com.dlwangsan.tapread

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {
    fun readText(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        val description = clip.description
        if (description != null &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) {
            // Still try coerceToText for mixed clips.
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        return text?.takeIf { it.isNotBlank() }
    }
}
