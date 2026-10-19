package com.instinct.tvremote

/**
 * Raw IR codes for the phone's built-in IR blaster.
 *
 * Android's ConsumerIrManager needs timing patterns, not hex codes, so the
 * NEC encoder below converts standard NEC hex codes (the most common TV
 * protocol) into patterns on the fly.
 *
 * ADD YOUR OWN DEVICES: find your device's codes on sites like irdb.tk or
 * remotecentral.com, or capture them with the ESP8266 + VS1838B receiver
 * (see esp8266/README.md), then add a line here. Samsung/LG codes below are
 * the standard ones published for those brands.
 */
object IrCodes {

    data class IrSignal(val protocol: String, val code: Long, val bits: Int)

    /** name -> signal. Names must match CommandParser's IR command names. */
    val table = mapOf(
        "tv_power" to IrSignal("NEC", 0xE0E040BF, 32),   // Samsung TV power (standard)
        "ac_power" to IrSignal("NEC", 0x20DF10EF, 32),   // placeholder - replace with your AC remote's code
        "fan_power" to IrSignal("NEC", 0x20DF10EF, 32)   // placeholder - replace with your fan remote's code
    )

    private const val NEC_FREQ = 38028 // Hz
    private const val HDR_MARK = 9000
    private const val HDR_SPACE = 4500
    private const val BIT_MARK = 560
    private const val ONE_SPACE = 1690
    private const val ZERO_SPACE = 560

    /** Encodes an NEC hex code into a ConsumerIrManager timing pattern (microseconds). */
    fun necPattern(code: Long, bits: Int): IntArray {
        val pattern = ArrayList<Int>(bits * 2 + 4)
        pattern.add(HDR_MARK)
        pattern.add(HDR_SPACE)
        for (i in bits - 1 downTo 0) {
            pattern.add(BIT_MARK)
            pattern.add(if ((code shr i) and 1L == 1L) ONE_SPACE else ZERO_SPACE)
        }
        pattern.add(BIT_MARK)
        return pattern.toIntArray()
    }

    fun patternFor(signal: IrSignal): Pair<Int, IntArray>? {
        return when (signal.protocol.uppercase()) {
            "NEC" -> NEC_FREQ to necPattern(signal.code, signal.bits)
            else -> null
        }
    }
}
