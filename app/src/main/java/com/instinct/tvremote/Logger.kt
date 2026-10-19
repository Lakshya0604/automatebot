package com.instinct.tvremote

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Simple in-app log bus: everything the app does lands on the main screen. */
object Logger {
    @Volatile
    var listener: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    fun log(msg: String) {
        Log.i("InstinctTV", msg)
        handler.post { listener?.invoke(msg) }
    }
}
