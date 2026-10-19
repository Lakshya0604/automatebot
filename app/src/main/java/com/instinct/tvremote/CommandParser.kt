package com.instinct.tvremote

/**
 * Turns recognized speech into a device action.
 *
 * Every command resolves to a [Target]: an ADB shell command for the Android TV,
 * or an IR/relay action routed through the ESP8266 (stage 2). Adding a new command means
 * adding a rule here and (optionally) a named command in the ESP8266 firmware.
 */
object CommandParser {

    sealed class Target {
        data class Adb(val shell: String) : Target()
        data class Ir(val name: String) : Target()
    }

    data class Parsed(val description: String, val target: Target)

    /** Android TV package names for "open <app>" commands. */
    private val tvApps = linkedMapOf(
        "you tube" to "com.google.android.youtube.tv",
        "youtube" to "com.google.android.youtube.tv",
        "netflix" to "com.netflix.ninja",
        "prime video" to "com.amazon.amazonvideo.livingroom",
        "prime" to "com.amazon.amazonvideo.livingroom",
        "hotstar" to "in.startv.hotstar",
        "play store" to "com.android.vending",
        "spotify" to "com.spotify.tv.android"
    )

    /** Named IR signals the ESP8266 firmware knows how to send (stage 2). */
    private val irCommands = linkedMapOf(
        "ac on" to "ac_power",
        "ac off" to "ac_power",
        "air conditioner on" to "ac_power",
        "air conditioner off" to "ac_power",
        "fan on" to "fan_power",
        "fan off" to "fan_power"
    )

    private fun key(code: Int, desc: String) =
        Parsed(desc, Target.Adb("input keyevent $code"))

    fun parse(raw: String): Parsed? {
        val t = " " + raw.lowercase().trim().replace(Regex("\\s+"), " ") + " "
        if (t.trim().isEmpty()) return null

        // "open youtube", "launch netflix", ...
        if (t.contains(" open ") || t.contains(" launch ") || t.contains(" start ")) {
            for ((name, pkg) in tvApps) {
                if (t.contains(" $name ")) {
                    return Parsed(
                        "Open ${name.trim()}",
                        Target.Adb("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                    )
                }
            }
            if (t.contains(" settings ")) {
                return Parsed("Open TV settings", Target.Adb("am start -a android.settings.SETTINGS"))
            }
        }

        // IR / relay devices (ESP8266, stage 2)
        for ((phrase, irName) in irCommands) {
            if (t.contains(" $phrase ")) {
                return Parsed("IR: $phrase", Target.Ir(irName))
            }
        }

        return when {
            t.contains(" volume up ") || t.contains(" louder ") -> key(24, "Volume up")
            t.contains(" volume down ") || t.contains(" quieter ") -> key(25, "Volume down")
            t.contains(" mute ") || t.contains(" unmute ") -> key(164, "Mute / unmute")
            t.contains(" channel up ") || t.contains(" next channel ") -> key(166, "Channel up")
            t.contains(" channel down ") || t.contains(" previous channel ") -> key(167, "Channel down")
            t.contains(" turn off ") || t.contains(" switch off ") || t.contains(" tv off ") || t.contains(" power off ") ->
                key(26, "TV power (toggle)")
            t.contains(" turn on ") || t.contains(" switch on ") || t.contains(" tv on ") || t.contains(" power on ") ->
                key(224, "TV wake")
            t.contains(" home ") -> key(3, "Home")
            t.contains(" back ") -> key(4, "Back")
            t.contains(" select ") || t.contains(" okay ") || t.contains(" ok ") || t.contains(" enter ") ->
                key(23, "Select / OK")
            t.contains(" pause ") || t.contains(" play ") -> key(85, "Play / pause")
            else -> null
        }
    }
}
