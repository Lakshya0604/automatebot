package com.cgutman.adblib

import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A fake Android TV's adbd for tests: a plain JVM socket server speaking the
 * ADB wire protocol (the same server half a real TV runs). Supports both TV
 * modes: secure (RSA AUTH challenge, what a real TV does on first connect)
 * and open (no auth). Lives in the adblib package to reuse its package-private
 * wire codec, so both sides of the test run the exact shipped protocol code.
 *
 * Records every shell destination the client opens, so tests can assert the
 * exact command bytes the app would send to the TV.
 */
class FakeAdbServer(private val secure: Boolean) {

    @Volatile
    var lastOpenDestination: String? = null
        private set

    @Volatile
    var authChallengeSent = false
        private set

    @Volatile
    var authSignatureReceived = false
        private set

    @Volatile
    var connectCompleted = false
        private set

    private val openLatch = CountDownLatch(1)
    private val closeLatch = CountDownLatch(1)

    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        val ss = ServerSocket(0)
        serverSocket = ss
        Thread {
            try {
                while (true) {
                    val s = ss.accept()
                    Thread { serve(s) }.also { it.isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    fun awaitCommand(timeout: Long, unit: TimeUnit): Boolean = openLatch.await(timeout, unit)
    fun awaitStreamClose(timeout: Long, unit: TimeUnit): Boolean = closeLatch.await(timeout, unit)

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            while (true) {
                val msg = try {
                    AdbProtocol.AdbMessage.parseAdbMessage(input)
                } catch (_: Exception) {
                    break
                }
                when (msg.command) {
                    AdbProtocol.CMD_CNXN -> {
                        if (secure) {
                            val token = ByteArray(20).also { SecureRandom().nextBytes(it) }
                            out.write(AdbProtocol.generateMessage(AdbProtocol.CMD_AUTH, AdbProtocol.AUTH_TYPE_TOKEN, 0, token))
                            authChallengeSent = true
                        } else {
                            sendCnxn(out)
                        }
                        out.flush()
                    }
                    AdbProtocol.CMD_AUTH -> {
                        if (msg.arg0 == AdbProtocol.AUTH_TYPE_SIGNATURE && msg.payload != null) {
                            authSignatureReceived = true
                            // a real TV that trusts this key answers CNXN here
                            sendCnxn(out)
                            out.flush()
                        } else if (msg.arg0 == AdbProtocol.AUTH_TYPE_RSA_PUBLIC) {
                            // "always allow" path on first pairing
                            sendCnxn(out)
                            out.flush()
                        }
                    }
                    AdbProtocol.CMD_OPEN -> {
                        val dest = msg.payload?.let { String(it, Charsets.UTF_8).removeSuffix("\u0000") }
                        lastOpenDestination = dest
                        openLatch.countDown()
                        val localId = msg.arg0
                        val remoteId = 4242
                        out.write(AdbProtocol.generateMessage(AdbProtocol.CMD_OKAY, remoteId, localId, null))
                        // pretend the shell command ran and printed something, then exited
                        out.write(AdbProtocol.generateMessage(AdbProtocol.CMD_WRTE, remoteId, localId, "OK\n".toByteArray()))
                        out.write(AdbProtocol.generateMessage(AdbProtocol.CMD_CLSE, remoteId, localId, null))
                        out.flush()
                        closeLatch.countDown()
                    }
                    AdbProtocol.CMD_WRTE -> {
                        // client wrote to the stream; ack it
                        out.write(AdbProtocol.generateMessage(AdbProtocol.CMD_OKAY, msg.arg1, msg.arg0, null))
                        out.flush()
                    }
                    AdbProtocol.CMD_CLSE -> {
                        // client closed; nothing to do
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendCnxn(out: java.io.OutputStream) {
        out.write(
            AdbProtocol.generateMessage(
                AdbProtocol.CMD_CNXN, AdbProtocol.CONNECT_VERSION, 256 * 1024,
                "host:fake-tv:1:features=shell_v2,cmd".toByteArray()
            )
        )
        connectCompleted = true
    }
}
