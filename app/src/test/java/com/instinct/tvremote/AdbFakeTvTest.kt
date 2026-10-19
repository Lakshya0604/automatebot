package com.instinct.tvremote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cgutman.adblib.FakeAdbServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * End-to-end test of the TV path without a TV: the app's real AdbController
 * (with the vendored AdbLib) connects over a real TCP socket to FakeAdbServer,
 * which plays the TV's adbd - including the RSA auth handshake a real Android
 * TV does when it shows "Allow debugging?".
 *
 * What this proves: the app generates/loads its ADB key, completes the
 * handshake, opens "shell:<command>" streams and ships the exact command
 * bytes a TV would run. What it cannot prove: a specific TV model's adbd
 * quirks (tested on the user's TV at first connect).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdbFakeTvTest {

    private var tv: FakeAdbServer? = null

    @After
    fun tearDown() {
        tv?.stop()
    }

    private fun runAgainst(secure: Boolean, command: String) {
        val server = FakeAdbServer(secure)
        tv = server
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val adb = AdbController(ctx)
        try {
            assertTrue("execute() should succeed against a TV that accepts", adb.execute("127.0.0.1", server.port, command))
            assertTrue("TV never received the command", server.awaitCommand(10, TimeUnit.SECONDS))
            assertEquals("shell:$command", server.lastOpenDestination)
            assertTrue("TV never finished the shell stream", server.awaitStreamClose(10, TimeUnit.SECONDS))
            if (secure) {
                assertTrue(server.authChallengeSent)
                assertTrue("client never answered the auth challenge", server.authSignatureReceived)
            } else {
                assertFalse(server.authChallengeSent)
            }
            assertTrue(server.connectCompleted)
        } finally {
            adb.disconnect()
        }
    }

    @Test
    fun keyeventReachesSecureTv() = runAgainst(secure = true, command = "input keyevent 26")

    @Test
    fun keyeventReachesOpenTv() = runAgainst(secure = false, command = "input keyevent 24")

    @Test
    fun appLaunchReachesTv() = runAgainst(
        secure = true,
        command = "monkey -p com.google.android.youtube.tv -c android.intent.category.LAUNCHER 1"
    )

    @Test
    fun failureAgainstDeadTvReturnsFalse() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val adb = AdbController(ctx)
        assertFalse(adb.execute("127.0.0.1", 1, "input keyevent 26"))
        adb.disconnect()
    }
}
