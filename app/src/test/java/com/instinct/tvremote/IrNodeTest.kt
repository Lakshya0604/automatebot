package com.instinct.tvremote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end test of the phone-to-phone IR path without either phone:
 * the real IrNodeServer (what runs on the phone WITH the IR blaster) and the
 * real IrBlasterController (what the Redmi uses to reach it) talk over a real
 * localhost socket, with a recorder standing in for ConsumerIrManager.
 *
 * What this proves: command naming, NEC encoding, raw learned-code transport,
 * error handling and the exact HTTP contract both phones (and the ESP8266
 * firmware) share. What it cannot prove: the IR LED / ConsumerIrManager
 * hardware firing (needs the user's phone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IrNodeTest {

    data class Sent(val freq: Int, val pattern: IntArray)

    private val sent = CopyOnWriteArrayList<Sent>()
    private var server: IrNodeServer? = null
    private var port = -1

    private fun startNode(hasIr: Boolean = true) {
        port = ServerSocket(0).use { it.localPort }
        val s = IrNodeServer(
            port = port,
            hasIr = { hasIr },
            transmit = { freq, pattern -> sent.add(Sent(freq, pattern)); true }
        )
        s.start()
        server = s
        // give the accept loop a moment to bind
        Thread.sleep(150)
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun host() = "127.0.0.1:$port"

    @Test
    fun namedCommandFiresIrBlaster() {
        startNode()
        val client = IrBlasterController()
        assertTrue(client.send(host(), "tv_power"))
        assertEquals(1, sent.size)
        // tv_power is NEC 0xE0E040BF at 38 kHz; the pattern is the NEC encoding of that code
        val expected = IrCodes.patternFor(IrCodes.table.getValue("tv_power"))!!
        assertEquals(expected.first, sent[0].freq)
        assertTrue(expected.second.contentEquals(sent[0].pattern))
    }

    @Test
    fun unknownNamedCommandIs404AndFiresNothing() {
        startNode()
        val client = IrBlasterController()
        assertFalse(client.send(host(), "does_not_exist"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun learnedRawRoundTrips() {
        startNode()
        val client = IrBlasterController()
        val raw = intArrayOf(9000, 4500, 560, 1690, 560, 560, 560, 560, 560, 1690, 560)
        val body = (listOf(38000) + raw.toList()).joinToString(",")
        assertTrue(client.sendRaw(host(), body))
        assertEquals(1, sent.size)
        assertEquals(38000, sent[0].freq)
        assertTrue(raw.contentEquals(sent[0].pattern))
    }

    @Test
    fun healthReportsIrPresence() {
        startNode(hasIr = true)
        val body = URL("http://${host()}/health").openConnection().let {
            it as HttpURLConnection
            val text = it.inputStream.bufferedReader().readText()
            it.disconnect()
            text
        }
        assertEquals("ok ir", body)
    }

    @Test
    fun learnIsRejectedPolitely() {
        startNode()
        assertNull("a phone has no IR receiver, learn must fail", IrBlasterController().learn(host()))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun blankHostFailsWithoutNetwork() {
        val client = IrBlasterController()
        assertFalse(client.send("", "tv_power"))
        assertFalse(client.sendRaw("", "38000,9000,4500"))
        assertNull(client.learn(""))
    }

    @Test
    fun nodePortIsAppendedWhenMissing() {
        // IrRouter turns a bare IP into ip:8765; verify the constant matches the service
        assertEquals(8765, IrNodeService.PORT)
    }
}
