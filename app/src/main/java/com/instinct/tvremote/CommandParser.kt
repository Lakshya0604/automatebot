package com.instinct.tvremote

/**
 * Turns recognized speech (English, Hindi in Devanagari, or Hinglish in Roman letters)
 * into a device action.
 *
 * Each rule is a list of groups. A group is "a|b|c" alternatives; every group must match
 * at least one token of the sentence. A pattern matches a token by prefix, or exactly when
 * written as "=word". Nukta marks are stripped so "बढ़" and "बढ" are the same.
 * Anything not understood here can go to [GeminiIntent] (optional, free API key).
 */
object CommandParser {

    sealed class Target {
        data class Adb(val shell: String) : Target()
        data class Ir(val name: String) : Target()
        data class IrLearned(val device: String, val buttons: List<String>) : Target()
    }

    data class Parsed(val description: String, val target: Target)

    private class Rule(val intent: String, vararg val groups: String)

    private const val VOL = "vol|वॉल्यूम|वाल्यूम|वोल्यूम|awaz|awaaz|aawaz|avaz|आवाज|आवाज़|sound|साउंड"
    private const val UP = "up|=high|=badh|badha|बढ|zyada|zyaada|ज्यादा|ज़्यादा|increase|tez|तेज|louder|ऊपर|upar"
    private const val DOWN = "down|kam|कम|ghata|घटा|decrease|=low|lower|dheere|dheeraj|धीरे|नीचे|neeche|quiet"
    private const val OFF = "=off|band|bandh|बंद|बन्द|ऑफ|आफ|=of|बंध|stop|shutdown|shut"
    private const val ON = "=on|chalu|chaalu|चालू|ऑन|आन|shuru|शुरू|=start|jala|जला"
    private const val TV = "tv|टीवी|टी वी|ti vi|television|टेलीविजन|=power|पावर"
    private const val AC = "=ac|एसी|ए सी|air|एयर|conditioner|कंडीशनर"
    private const val FAN = "fan|फैन|pankha|पंखा|पंखे"
    private const val OPENV = "open|launch|=start|chalu|chaalu|chala|चला|चालू|खोल|khol|lagao|लगा|dikha|दिखा|=play|=on|ऑन|=run"

    private val rules = listOf(
        Rule("ch_up", "channel|चैनल|चेनल|chanel", "up|next|agla|अगला|aage|आगे|badh|बढ"),
        Rule("ch_down", "channel|चैनल|चेनल|chanel", "down|previous|prev|pichla|पिछला|peeche|पीछे|back"),
        Rule("vol_up", VOL, UP),
        Rule("vol_down", VOL, DOWN),
        Rule("vol_up", "louder|=badhao|बढ़ाओ|बढाओ"),
        Rule("vol_down", "quieter|=kam|=कम"),
        Rule("mute", "mute|unmute|म्यूट|चुप|chup|khamosh|खामोश|silent"),
        Rule("ac_off", AC, OFF), Rule("ac_on", AC, ON),
        Rule("fan_off", FAN, OFF), Rule("fan_on", FAN, ON),
        Rule("open_youtube", "youtube|you|यूट्यूब|यू|यूटयूब|utube|ट्यूब|tube"),
        Rule("open_netflix", "netflix|नेटफ्लिक्स|नेटफ्लिकस|netflics"),
        Rule("open_prime", "prime|प्राइम|amazon|अमेज़न|अमेजन"),
        Rule("open_hotstar", "hotstar|हॉटस्टार|होटस्टार|हॉट|hot"),
        Rule("open_playstore", "store|स्टोर|playstore"),
        Rule("open_spotify", "spotify|स्पॉटिफाई|स्पोटिफाई|spotifi"),
        Rule("settings", "settings|setting|सेटिंग|सेटींग"),
        Rule("power_off", OFF),
        Rule("power_on", ON),
        Rule("home", "home|होम|=ghar|घर"),
        Rule("back", "=back|वापस|wapas|vapas|बैक|piche|पीछे"),
        Rule("select", "=ok|okay|ओके|ओके|select|सेलेक्ट|enter|चुनो|chuno|=haan|हाँ|हां"),
        Rule("play_pause", "pause|=play|रुक|ruk|रोक|rok|प्ले|पॉज|पोज|चला|chala|resume")
    )

    private fun norm(s: String): String = s.lowercase()
        .replace("\u093C", "")
        .replace("ड़", "ड").replace("ढ़", "ढ")
        .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ")
        .trim()

    private fun matchesGroup(tokens: List<String>, group: String): Boolean =
        group.split("|").any { p ->
            val q = norm(p.removePrefix("="))
            if (q.isEmpty()) false
            else if (p.startsWith("=")) tokens.any { it == q }
            else tokens.any { it.startsWith(q) } || (q.contains(' ') && tokens.joinToString(" ").contains(q))
        }

    private fun key(code: Int, desc: String) = Parsed(desc, Target.Adb("input keyevent $code"))

    private fun app(pkg: String, name: String) = Parsed(
        "Open $name",
        Target.Adb("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    )

    /** Intent ids the parser and Gemini share. */
    val intentIds = listOf(
        "vol_up", "vol_down", "mute", "ch_up", "ch_down", "power_off", "power_on", "home", "back",
        "select", "play_pause", "open_youtube", "open_netflix", "open_prime", "open_hotstar",
        "open_playstore", "open_spotify", "settings", "ac_on", "ac_off", "fan_on", "fan_off"
    )

    fun fromIntent(id: String): Parsed? = when (id) {
        "vol_up" -> key(24, "Volume up")
        "vol_down" -> key(25, "Volume down")
        "mute" -> key(164, "Mute / unmute")
        "ch_up" -> key(166, "Channel up")
        "ch_down" -> key(167, "Channel down")
        "power_off" -> key(26, "TV power (toggle)")
        "power_on" -> key(224, "TV wake")
        "home" -> key(3, "Home")
        "back" -> key(4, "Back")
        "select" -> key(23, "Select / OK")
        "play_pause" -> key(85, "Play / pause")
        "open_youtube" -> app("com.google.android.youtube.tv", "YouTube")
        "open_netflix" -> app("com.netflix.ninja", "Netflix")
        "open_prime" -> app("com.amazon.amazonvideo.livingroom", "Prime Video")
        "open_hotstar" -> app("in.startv.hotstar", "Hotstar")
        "open_playstore" -> app("com.android.vending", "Play Store")
        "open_spotify" -> app("com.spotify.tv.android", "Spotify")
        "settings" -> Parsed("Open TV settings", Target.Adb("am start -a android.settings.SETTINGS"))
        "ac_on", "ac_off" -> Parsed("IR: AC power", Target.Ir("ac_power"))
        "fan_on", "fan_off" -> Parsed("IR: fan power", Target.Ir("fan_power"))
        else -> null
    }

    fun parse(raw: String): Parsed? {
        val tokens = norm(raw).split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (r in rules) {
            if (r.groups.all { matchesGroup(tokens, it) }) return fromIntent(r.intent)
        }
        return null
    }
}
