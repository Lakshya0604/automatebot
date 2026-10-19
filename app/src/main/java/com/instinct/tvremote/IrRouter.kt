package com.instinct.tvremote

import android.content.Context

/**
 * Routes IR commands to the best available backend:
 *   1. the phone's own IR blaster, if it has one and the code is known
 *   2. the ESP8266 over WiFi (named commands, relay or IR LED)
 */
class IrRouter(context: Context) {
    private val store = DeviceStore(context)

    private val phoneIr = PhoneIrController(context)
    private val wifi = IrBlasterController()

    fun describeBackend(): String = when {
        phoneIr.available() -> "IR backend: this phone's built-in IR blaster (ESP8266 as fallback)"
        else -> "IR backend: ESP8266 over WiFi (this phone has no IR blaster)"
    }

    fun send(esp32Host: String, name: String): Boolean {
        if (phoneIr.available() && IrCodes.table.containsKey(name)) {
            if (phoneIr.send(name)) return true
            Logger.log("Phone IR failed, trying ESP8266...")
        }
        return wifi.send(esp32Host, name)
    }

    /** Replay learned buttons (e.g. digits of a channel number) via phone IR or the ESP8266. */
    fun sendLearned(esp32Host: String, device: String, buttons: List<String>): Boolean {
        var ok = true
        for ((i, b) in buttons.withIndex()) {
            val sig = store.get(device, b)
            if (sig == null) { Logger.log("No learned button '$b' on '$device'"); return false }
            val sent = if (phoneIr.available()) phoneIr.sendRaw(sig.freq, sig.raw) else wifi.sendRaw(esp32Host, store.serialize(sig))
            if (!sent && phoneIr.available()) { ok = wifi.sendRaw(esp32Host, store.serialize(sig)) && ok } else ok = sent && ok
            if (i < buttons.size - 1) Thread.sleep(400)
        }
        return ok
    }
}
