package com.instinct.tvremote

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Turns THIS phone into a WiFi IR node for the other phone.
 *
 * The Redmi 8A has no IR blaster; the second phone has one. Both phones run
 * Automatebot on the same house WiFi. This phone runs IrNodeServer; the other
 * phone sends it the exact same HTTP calls it would send to the ESP8266
 * (IrBlasterController), and this phone fires its built-in IR blaster.
 *
 * Protocol (same as esp8266/ir_blaster firmware, so one client serves both):
 *   GET  /health              -> "ok ir" or "ok no-ir"
 *   GET  /ir?name=<name>      -> named command from IrCodes (e.g. tv_power)
 *   POST /send  body "freq,us,us,..."  -> raw transmit (learned buttons)
 *   GET  /learn               -> 501 (a phone has no IR receiver; learn on the ESP8266)
 *
 * Pure JVM (no Android classes) so the unit test can run the real server and
 * the real client against each other on localhost.
 */
class IrNodeServer(
    private val port: Int,
    private val hasIr: () -> Boolean,
    private val transmit: (freq: Int, pattern: IntArray) -> Boolean,
    private val log: (String) -> Unit = {}
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                log("IR node listening on port $port")
                while (running) {
                    val s = try { ss.accept() } catch (e: Exception) { break }
                    Thread { handle(s) }.also { it.isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (running) log("IR node error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            val request = readRequestHead(input) ?: run { socket.close(); return }
            val method = request.first
            val path = request.second
            val contentLength = request.third

            when {
                method == "GET" && path.startsWith("/health") ->
                    respond(out, 200, if (hasIr()) "ok ir" else "ok no-ir")

                method == "GET" && path.startsWith("/ir") -> {
                    val name = queryParam(path, "name")
                    if (name == null) {
                        respond(out, 400, "missing name")
                    } else {
                        val signal = IrCodes.table[name]
                        val encoded = signal?.let { IrCodes.patternFor(it) }
                        if (encoded == null) {
                            respond(out, 404, "unknown command: $name")
                        } else if (transmit(encoded.first, encoded.second)) {
                            log("IR node <- '$name'")
                            respond(out, 200, "sent $name")
                        } else {
                            respond(out, 500, "transmit failed")
                        }
                    }
                }

                method == "POST" && path.startsWith("/send") -> {
                    val body = ByteArray(contentLength.coerceAtMost(64 * 1024))
                    var read = 0
                    while (read < body.size) {
                        val n = input.read(body, read, body.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val parts = String(body, 0, read).trim().split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                    if (parts.size < 8) {
                        respond(out, 400, "empty")
                    } else if (transmit(parts[0], parts.drop(1).toIntArray())) {
                        log("IR node <- raw (${parts.size - 1} pulses)")
                        respond(out, 200, "sent ${parts.size - 1}")
                    } else {
                        respond(out, 500, "transmit failed")
                    }
                }

                method == "GET" && path.startsWith("/learn") ->
                    respond(out, 501, "a phone has no IR receiver - learn with the ESP8266 instead")

                else -> respond(out, 404, "unknown path")
            }
        } catch (e: Exception) {
            log("IR node client error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Returns (method, path-with-query, content-length). */
    private fun readRequestHead(input: java.io.InputStream): Triple<String, String, Int>? {
        val head = StringBuilder()
        var prev = -1
        var cur: Int
        var contentLength = 0
        while (true) {
            cur = input.read()
            if (cur < 0) return null
            head.append(cur.toChar())
            if (head.length > 16384) return null
            if (prev == '\r'.code && cur == '\n'.code && head.endsWith("\r\n\r\n")) break
            prev = cur
        }
        val lines = head.toString().split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null
        for (line in lines.drop(1)) {
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        return Triple(requestLine[0], requestLine[1], contentLength)
    }

    private fun queryParam(path: String, key: String): String? {
        val q = path.substringAfter('?', "")
        for (pair in q.split("&")) {
            val k = pair.substringBefore('=')
            if (k == key) return URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        return null
    }

    private fun respond(out: java.io.OutputStream, code: Int, text: String) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found";
            500 -> "Internal Server Error"; 501 -> "Not Implemented"; else -> "?"
        }
        val body = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}
