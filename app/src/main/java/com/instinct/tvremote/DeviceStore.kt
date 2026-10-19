package com.instinct.tvremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-named IR devices with learned buttons, stored on the phone.
 * A button is a raw IR signal: carrier frequency + mark/space timings in microseconds.
 * Raw works for any brand and for AC remotes, no protocol decoding needed.
 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("ir_devices", Context.MODE_PRIVATE)

    class Signal(val freq: Int, val raw: IntArray)

    private fun root(): JSONObject = try { JSONObject(prefs.getString("devices", "{}")!!) } catch (_: Exception) { JSONObject() }

    fun deviceNames(): List<String> = root().keys().asSequence().toList()

    fun buttonNames(device: String): List<String> =
        root().optJSONObject(device)?.keys()?.asSequence()?.toList() ?: emptyList()

    fun get(device: String, button: String): Signal? {
        val o = root().optJSONObject(device)?.optJSONObject(button) ?: return null
        val a = o.optJSONArray("raw") ?: return null
        return Signal(o.optInt("freq", 38000), IntArray(a.length()) { a.getInt(it) })
    }

    fun put(device: String, button: String, s: Signal) {
        val r = root()
        val d = r.optJSONObject(device) ?: JSONObject().also { r.put(device, it) }
        d.put(button, JSONObject().put("freq", s.freq).put("raw", JSONArray(s.raw.toList())))
        prefs.edit().putString("devices", r.toString()).apply()
    }

    fun delete(device: String, button: String?) {
        val r = root()
        if (button.isNullOrBlank()) r.remove(device) else r.optJSONObject(device)?.remove(button)
        prefs.edit().putString("devices", r.toString()).apply()
    }

    /** Parse "freq,us,us,..." from the ESP8266 /learn endpoint. */
    fun parseLearned(text: String): Signal? {
        val p = text.trim().split(",").mapNotNull { it.trim().toIntOrNull() }
        if (p.size < 8) return null
        return Signal(p[0], p.drop(1).toIntArray())
    }

    fun serialize(s: Signal): String = (listOf(s.freq) + s.raw.toList()).joinToString(",")

    // ---- voice matching ----

    data class Match(val device: String, val buttons: List<String>)

    private val onWords = listOf("on", "chalu", "चालू", "ऑन", "start", "shuru", "शुरू")
    private val offWords = listOf("off", "band", "बंद", "ऑफ", "bandh", "stop")
    private val upWords = listOf("up", "badhao", "बढ़ाओ", "बढाओ", "zyada", "ज्यादा", "increase", "tez", "तेज", "ऊपर")
    private val downWords = listOf("down", "kam", "कम", "decrease", "ghatao", "घटाओ", "dheere", "धीरे", "नीचे")

    private fun clean(s: String) = s.lowercase().replace("\u093C", "")
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    /**
     * "ac on", "set top box channel 5", "fan speed up", "एसी बंद करो".
     * Finds the device whose name appears in the sentence, then the button:
     * exact button name first, then on/off/up/down synonyms, then digits ("channel 25" -> 2, 5).
     */
    fun match(text: String): Match? {
        val t = " " + clean(text) + " "
        val tokens = clean(text).split(" ")
        val r = root()
        var best: String? = null
        for (d in r.keys()) {
            val n = clean(d)
            if (n.isNotEmpty() && t.contains(" $n ") && (best == null || n.length > clean(best).length)) best = d
        }
        val dev = best ?: return null
        val btns = r.optJSONObject(dev)?.keys()?.asSequence()?.toList() ?: return null

        // 1. longest button name that appears in the sentence
        val named = btns.filter { clean(it).isNotEmpty() && t.contains(" ${clean(it)} ") && !clean(it).all { c -> c.isDigit() } }
            .maxByOrNull { clean(it).length }
        if (named != null) return Match(dev, listOf(named))

        // 2. digits -> press each digit button
        val digits = tokens.filter { it.all { c -> c.isDigit() } && it.isNotEmpty() }.joinToString("").toList().map { it.toString() }
        if (digits.isNotEmpty() && digits.all { it in btns }) return Match(dev, digits)

        // 3. synonyms
        fun pick(words: List<String>, aliases: List<String>): String? {
            if (tokens.none { tk -> words.any { tk == clean(it) } }) return null
            return btns.firstOrNull { b -> clean(b) in aliases.map { clean(it) } }
        }
        val syn = pick(onWords, listOf("on", "power on", "power", "toggle"))
            ?: pick(offWords, listOf("off", "power off", "power", "toggle"))
            ?: pick(upWords, listOf("up", "speed up", "+", "plus", "vol up", "volume up", "temp up"))
            ?: pick(downWords, listOf("down", "speed down", "-", "minus", "vol down", "volume down", "temp down"))
        if (syn != null) return Match(dev, listOf(syn))
        return null
    }
}
