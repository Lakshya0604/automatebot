package com.instinct.tvremote

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stage 2: sends a named command to the ESP8266 (WeMos D1) over WiFi.
 * The board runs the firmware in /esp8266 of this repo and exposes:
 *   GET http://<board-ip>/ir?name=<command-name>
 */
class IrBlasterController {

    fun send(esp32Host: String, name: String): Boolean {
        if (esp32Host.isBlank()) {
            Logger.log("ESP8266 not set up yet (enter its IP in the app)")
            return false
        }
        return try {
            val url = "http://$esp32Host/ir?name=" + URLEncoder.encode(name, "UTF-8")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                Logger.log("ESP8266 <- '$name'")
                true
            } else {
                Logger.log("ESP8266 returned HTTP $code")
                false
            }
        } catch (e: Exception) {
            Logger.log("ESP8266 error: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun sendRaw(host: String, body: String): Boolean {
        if (host.isBlank()) { Logger.log("ESP8266 / IR-node address not set"); return false }
        return try {
            val c = URL("http://$host/send").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 5000
            // the ESP8266 web server (and the phone node) need a known body length,
            // not HttpURLConnection's default chunked streaming
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val ok = c.responseCode in 200..299
            c.disconnect(); ok
        } catch (e: Exception) { Logger.log("IR send error: ${e.message}"); false }
    }

    /** Ask the board to listen for a remote button. Blocks up to ~12 s. Returns "freq,us,us,..." or null. */
    fun learn(host: String): String? {
        if (host.isBlank()) { Logger.log("ESP8266 IP not set (learning needs the ESP8266 receiver)") ; return null }
        return try {
            val c = URL("http://$host/learn?timeout=10").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 14000
            val text = if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
            c.disconnect(); text
        } catch (e: Exception) { Logger.log("Learn error: ${e.message}"); null }
    }
}
