package com.example.safewalknav.navigation

import platform.Foundation.NSLog

actual object Logger {
    actual fun d(tag: String, message: String) {
        NSLog("[$tag] D: $message")
    }

    actual fun w(tag: String, message: String) {
        NSLog("[$tag] W: $message")
    }
}