package com.instinct.tvremote

import android.content.Context
import android.util.Base64
import com.cgutman.adblib.AdbBase64
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to the Android TV over WiFi using the ADB protocol (via AdbLib).
 *
 * First connection makes the TV show an "Allow debugging?" prompt - accept it
 * on the TV (tick "always allow") and the key pair stored here is trusted
 * from then on.
 */
class AdbController(private val context: Context) {

    @Volatile
    private var conn: AdbConnection? = null

    private val base64 = object : AdbBase64 {
        override fun encodeToString(data: ByteArray): String =
            Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun crypto(): AdbCrypto {
        val priv = File(context.filesDir, "adb_key")
        val pub = File(context.filesDir, "adb_key.pub")
        return if (priv.exists() && pub.exists()) {
            AdbCrypto.loadAdbKeyPair(base64, priv, pub)
        } else {
            val c = AdbCrypto.generateAdbKeyPair(base64)
            c.saveAdbKeyPair(priv, pub)
            c
        }
    }

    /** Runs one shell command on the TV. Connects lazily. Returns false on failure. */
    @Synchronized
    fun execute(host: String, port: Int, command: String): Boolean {
        return try {
            if (conn == null) connect(host, port)
            val stream = conn!!.open("shell:$command")
            // Drain the stream in the background so the remote shell can finish.
            Thread {
                try {
                    while (!stream.isClosed) stream.read()
                } catch (_: Exception) {
                } finally {
                    try { stream.close() } catch (_: Exception) {}
                }
            }.start()
            Logger.log("TV <- $command")
            true
        } catch (e: Exception) {
            Logger.log("TV error: ${e.message ?: e.javaClass.simpleName}")
            disconnect()
            false
        }
    }

    fun connect(host: String, port: Int) {
        disconnect()
        Logger.log("Connecting to TV at $host:$port (first time: accept the prompt on the TV)...")
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 8000)
        socket.tcpNoDelay = true
        val c = AdbConnection.create(socket, crypto())
        c.connect()
        conn = c
        Logger.log("TV connected")
    }

    @Synchronized
    fun disconnect() {
        try { conn?.close() } catch (_: Exception) {}
        conn = null
    }
}
