package com.instinct.tvremote

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Web control panel relay (Firebase Realtime Database, free tier, plain REST).
 * The website writes {text, ts} to /rooms/<room>/cmd. This listens with a server-sent-events
 * stream and hands each new command to [onCommand]. Browsers cannot talk ADB or IR, so the
 * phone executes everything locally. The room id is a long random secret: only someone who
 * has it can read or write commands.
 */
class RemoteRelay(
    private val dbUrl: String,
    private val room: String,
    private val onCommand: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var lastTs = -1L

    companion object {
        /** Publish the device registry ({name:[buttons]}) so the website can show and use it. */
        fun publishDevices(dbUrl: String, room: String, store: DeviceStore) {
            if (dbUrl.isBlank() || room.length < 16) return
            Thread {
                try {
                    val o = JSONObject()
                    for (d in store.deviceNames()) o.put(d, org.json.JSONArray(store.buttonNames(d)))
                    val c = URL("${dbUrl.trimEnd('/')}/rooms/$room/devices.json").openConnection() as HttpURLConnection
                    c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 5000
                    c.outputStream.use { it.write(o.toString().toByteArray()) }
                    c.responseCode; c.disconnect()
                } catch (_: Exception) {}
            }.start()
        }
    }

    fun start() {
        if (dbUrl.isBlank() || room.length < 16) { Logger.log("Web control off (set DB URL + room code)"); return }
        running = true
        thread = Thread {
            while (running) {
                try { listen() } catch (e: Exception) {
                    if (running) Logger.log("Web relay: ${e.message ?: e.javaClass.simpleName}, retrying")
                }
                if (running) try { Thread.sleep(5000) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true; it.start() }
        Logger.log("Web control connected (room ${room.take(4)}...)")
    }

    fun stop() { running = false; thread?.interrupt() }

    fun reportStatus(msg: String) {
        Thread {
            try {
                val c = URL("${dbUrl.trimEnd('/')}/rooms/$room/status.json").openConnection() as HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 5000
                c.outputStream.use { it.write(JSONObject().put("msg", msg).put("ts", System.currentTimeMillis()).toString().toByteArray()) }
                c.responseCode; c.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun listen() {
        val c = URL("${dbUrl.trimEnd('/')}/rooms/$room/cmd.json").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "text/event-stream")
        c.connectTimeout = 10000
        c.readTimeout = 0
        c.inputStream.bufferedReader().use { r ->
            var event = ""
            while (running) {
                val line = r.readLine() ?: break
                if (line.startsWith("event:")) event = line.substring(6).trim()
                else if (line.startsWith("data:") && (event == "put" || event == "patch")) {
                    try {
                        val d = JSONObject(line.substring(5).trim()).opt("data")
                        val o = d as? JSONObject ?: continue
                        val ts = o.optLong("ts", 0)
                        val text = o.optString("text", "")
                        if (lastTs == -1L) { lastTs = ts }          // first event = old value, ignore
                        else if (ts != lastTs && text.isNotBlank()) { lastTs = ts; onCommand(text) }
                    } catch (_: Exception) {}
                }
            }
        }
        c.disconnect()
    }
}
