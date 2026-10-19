package com.instinct.tvremote

import android.content.Context

/**
 * Routes IR commands to the best available backend, in order:
 *   1. this phone's own IR blaster, if it has one and the code is known
 *   2. the other phone running the Automatebot IR node over WiFi (nodeHost)
 *   3. the ESP8266 (WeMos D1) over WiFi
 *
 * The node and the ESP8266 speak the same HTTP protocol (see IrNodeServer),
 * so one client (IrBlasterController) talks to both.
 */
class IrRouter(context: Context) {
    private val store = DeviceStore(context)

    private val phoneIr = PhoneIrController(context)
    private val wifi = IrBlasterController()

    fun describeBackend(): String = when {
        phoneIr.available() -> "IR backend: this phone's built-in IR blaster (node/ESP8266 as fallback)"
        else -> "IR backend: phone node or ESP8266 over WiFi (this phone has no IR blaster)"
    }

    /** Adds the node port when the user typed only the phone's IP. */
    private fun nodeAddress(nodeHost: String): String =
        if (nodeHost.contains(':')) nodeHost else "$nodeHost:${IrNodeService.PORT}"

    fun send(esp32Host: String, nodeHost: String, name: String): Boolean {
        if (phoneIr.available() && IrCodes.table.containsKey(name)) {
            if (phoneIr.send(name)) return true
            Logger.log("Phone IR failed, trying node/ESP8266...")
        }
        if (nodeHost.isNotBlank() && wifi.send(nodeAddress(nodeHost), name)) return true
        return wifi.send(esp32Host, name)
    }

    /** Replay learned buttons (e.g. digits of a channel number) via phone IR, node or the ESP8266. */
    fun sendLearned(esp32Host: String, nodeHost: String, device: String, buttons: List<String>): Boolean {
        var ok = true
        for ((i, b) in buttons.withIndex()) {
            val sig = store.get(device, b)
            if (sig == null) { Logger.log("No learned button '$b' on '$device'"); return false }
            val body = store.serialize(sig)
            val sent = when {
                phoneIr.available() && phoneIr.sendRaw(sig.freq, sig.raw) -> true
                nodeHost.isNotBlank() && wifi.sendRaw(nodeAddress(nodeHost), body) -> true
                else -> wifi.sendRaw(esp32Host, body)
            }
            ok = sent && ok
            if (i < buttons.size - 1) Thread.sleep(400)
        }
        return ok
    }
}
