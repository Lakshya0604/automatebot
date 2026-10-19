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
}
