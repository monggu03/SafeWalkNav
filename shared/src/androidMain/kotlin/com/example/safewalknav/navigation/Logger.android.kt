package com.example.safewalknav.navigation

import android.util.Log as AndroidLog

/**
 * Android 측 Logger 구현 — `android.util.Log` 에 위임.
 */
actual object Logger {
    actual fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
    }

    actual fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
    }
}
