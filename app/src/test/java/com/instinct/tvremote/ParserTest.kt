package com.instinct.tvremote

import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    private fun d(s: String) = CommandParser.parse(s)?.description
    @Test fun phrases() {
        val cases = mapOf(
            "volume badhao" to "Volume up", "आवाज़ बढ़ाओ" to "Volume up", "awaz kam karo" to "Volume down",
            "volume up" to "Volume up", "वॉल्यूम कम करो" to "Volume down", "tv band karo" to "TV power (toggle)",
            "टीवी बंद करो" to "TV power (toggle)", "turn off the tv" to "TV power (toggle)",
            "tv chalu karo" to "TV wake", "टीवी चालू करो" to "TV wake", "youtube kholo" to "Open YouTube",
            "यूट्यूब चलाओ" to "Open YouTube", "open netflix" to "Open Netflix", "next channel" to "Channel up",
            "चैनल बदलो अगला" to "Channel up", "mute karo" to "Mute / unmute", "ghar jao" to "Home",
            "home" to "Home", "पीछे जाओ" to "Back", "pause" to "Play / pause", "ac band karo" to "IR: AC power",
            "पंखा चालू करो" to "IR: fan power", "settings kholo" to "Open TV settings", "ओके" to "Select / OK"
        )
        val bad = cases.filter { d(it.key) != it.value }.map { "${it.key} -> ${d(it.key)} (want ${it.value})" }
        assertEquals("", bad.joinToString("\n"))
    }
}
