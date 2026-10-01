package com.example.utils

import java.util.Locale

object TimeFormatter {
    fun formatMsToTimecode(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val minutes = safeMs / 60000
        val seconds = (safeMs % 60000) / 1000
        val millis = safeMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    fun formatSecondsToTimecode(seconds: Float): String {
        return formatMsToTimecode((seconds * 1000f).toLong())
    }
}
