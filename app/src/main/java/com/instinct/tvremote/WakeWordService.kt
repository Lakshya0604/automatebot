package com.instinct.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.Model
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

/**
 * Always-listening foreground service:
 *   1. wake mode  - grammar-limited recognizer that only fires on the wake word
 *   2. command mode (a few seconds after the beep) - full recognizer, the
 *      phrase is parsed and routed to the TV (ADB) or the ESP8266 (IR / relay)
 * then back to wake mode.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.instinct.tvremote.START"
        const val ACTION_STOP = "com.instinct.tvremote.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_ESP32 = "esp32"
        const val EXTRA_NODE = "node"
        const val EXTRA_GEMINI = "gemini"
        const val EXTRA_DB = "db"
        const val EXTRA_ROOM = "room"
        const val DEFAULT_WAKE = "bot one"
        private const val CHANNEL_ID = "listening"
        private const val COMMAND_WINDOW_MS = 8000L
    }

    private enum class Mode { WAKE, COMMAND }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var mode = Mode.WAKE

    private var host = ""
    private var port = 5555
    private var esp32Host = ""
    private var nodeHost = ""
    private var wakePhrase = DEFAULT_WAKE
    private var geminiKey = ""
    private var relay: RemoteRelay? = null
    private lateinit var devices: DeviceStore
    private var cloud: SpeechRecognizer? = null
    private val wakeAliases = setOf("bot one", "bot won", "boat one", "bought one", "bot 1", "but one", "bott one")

    private lateinit var adb: AdbController
    private lateinit var irRouter: IrRouter

    private val commandTimeout = Runnable {
        if (mode == Mode.COMMAND) {
            Logger.log("No command heard, back to sleep")
            tone.startTone(ToneGenerator.TONE_PROP_NACK)
            startWakeMode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        adb = AdbController(this)
        irRouter = IrRouter(this)
        devices = DeviceStore(this)
        Logger.log(irRouter.describeBackend())
        createChannel()
        startForegroundCompat()

        executor.execute {
            try {
                val dir = ModelManager.ensureModel(this) { m -> Logger.log(m) }
                model = Model(dir.absolutePath)
                handler.post {
                    Logger.log("Listening for \"$wakePhrase\"...")
                    startWakeMode()
                }
            } catch (e: Exception) {
                Logger.log("Model failed: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                host = intent.getStringExtra(EXTRA_HOST) ?: host
                port = intent.getIntExtra(EXTRA_PORT, port)
                esp32Host = intent.getStringExtra(EXTRA_ESP32) ?: esp32Host
                nodeHost = intent.getStringExtra(EXTRA_NODE) ?: nodeHost
                wakePhrase = (intent.getStringExtra(EXTRA_WAKE) ?: wakePhrase)
                    .trim().lowercase().ifEmpty { DEFAULT_WAKE }
                geminiKey = intent.getStringExtra(EXTRA_GEMINI) ?: geminiKey
                relay?.stop()
                relay = RemoteRelay(intent.getStringExtra(EXTRA_DB) ?: "", intent.getStringExtra(EXTRA_ROOM) ?: "") { text ->
                    handler.post { handleRemoteText(text) }
                }.also { it.start() }
                RemoteRelay.publishDevices(intent.getStringExtra(EXTRA_DB) ?: "", intent.getStringExtra(EXTRA_ROOM) ?: "", devices)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- recognizer modes ----

    private fun startWakeMode() {
        mode = Mode.WAKE
        handler.removeCallbacks(commandTimeout)
        val phrases = (if (wakePhrase == DEFAULT_WAKE) wakeAliases else setOf(wakePhrase))
        val grammar = phrases.joinToString(",", "[", ",\"[unk]\"]") { "\"" + it.replace("\"", "") + "\"" }
        switchRecognizer(Recognizer(model ?: return, 16000.0f, grammar))
    }

    private fun startCommandMode() {
        mode = Mode.COMMAND
        handler.removeCallbacks(commandTimeout)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
            speech = null
            handler.postDelayed({ startCloudListen() }, 250)
        } else {
            startVoskCommand()
        }
    }

    private fun startVoskCommand() {
        mode = Mode.COMMAND
        switchRecognizer(Recognizer(model ?: return, 16000.0f))
        Logger.log("Yes? Say a command (English only, offline)...")
        handler.removeCallbacks(commandTimeout)
        handler.postDelayed(commandTimeout, COMMAND_WINDOW_MS)
    }

    /** Hindi / Hinglish / English via the phone's Google speech service (free). */
    private fun startCloudListen() {
        try {
            cloud?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            cloud = r
            r.setRecognitionListener(object : AndroidRecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { Logger.log("Yes? Say a command (Hindi / English)...") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) {
                    Logger.log("Speech service error $error")
                    cloud?.destroy(); cloud = null
                    if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT) {
                        Logger.log("Falling back to offline English")
                        startVoskCommand()
                    } else {
                        tone.startTone(ToneGenerator.TONE_PROP_NACK)
                        startWakeMode()
                    }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    cloud?.destroy(); cloud = null
                    runCommandAlternatives(list)
                }
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                .putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf("en-IN"))
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            r.startListening(i)
        } catch (e: Exception) {
            Logger.log("Cloud speech failed: ${e.message}")
            startVoskCommand()
        }
    }

    private fun runCommandAlternatives(alts: List<String>) {
        if (alts.isEmpty()) {
            tone.startTone(ToneGenerator.TONE_PROP_NACK); startWakeMode(); return
        }
        Logger.log("Heard: \"${alts.first()}\"")
        for (a in alts) {
            val p = resolve(a)
            if (p != null) { execute(p); return }
        }
        if (geminiKey.isBlank()) { unknown(alts.first()); return }
        executor.execute {
            val id = GeminiIntent.resolve(geminiKey, alts.first())
            val p = id?.let { CommandParser.fromIntent(it) }
            handler.post { if (p != null) execute(p) else unknown(alts.first()) }
        }
    }

    /** Learned IR devices first (user-named), then built-in commands. */
    private fun resolve(text: String): CommandParser.Parsed? {
        val m = devices.match(text)
        if (m != null) return CommandParser.Parsed("${m.device}: ${m.buttons.joinToString(" ")}", CommandParser.Target.IrLearned(m.device, m.buttons))
        return CommandParser.parse(text)
    }

    /** Command typed or spoken on the website. "intent:vol_up" = button press, else free text. */
    private fun handleRemoteText(text: String) {
        Logger.log("Web: \"$text\"")
        val p = if (text.startsWith("intent:")) CommandParser.fromIntent(text.removePrefix("intent:")) else resolve(text)
        if (p != null) { execute(p); relay?.reportStatus("OK: ${p.description}"); return }
        if (geminiKey.isNotBlank()) {
            executor.execute {
                val id = GeminiIntent.resolve(geminiKey, text)
                val q = id?.let { CommandParser.fromIntent(it) }
                handler.post {
                    if (q != null) { execute(q); relay?.reportStatus("OK: ${q.description}") }
                    else relay?.reportStatus("Did not understand: $text")
                }
            }
        } else relay?.reportStatus("Did not understand: $text")
    }

    private fun unknown(text: String) {
        Logger.log("Unknown command: \"$text\"")
        tone.startTone(ToneGenerator.TONE_PROP_NACK)
        startWakeMode()
    }

    private fun switchRecognizer(recognizer: Recognizer) {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        try {
            speech = SpeechService(recognizer, 16000.0f).also { it.startListening(this) }
        } catch (e: Exception) {
            Logger.log("Mic error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- RecognitionListener ----

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        handleText(extractText(hypothesis))
    }

    override fun onFinalResult(hypothesis: String?) {
        handleText(extractText(hypothesis))
    }

    override fun onError(exception: Exception?) {
        Logger.log("Recognizer error: ${exception?.message ?: "unknown"}")
    }

    override fun onTimeout() {
        if (mode == Mode.COMMAND) startWakeMode()
    }

    private fun extractText(hypothesis: String?): String {
        if (hypothesis == null) return ""
        return try {
            JSONObject(hypothesis).optString("text", "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun handleText(text: String) {
        if (text.isBlank()) return
        when (mode) {
            Mode.WAKE -> {
                val heard = text.trim().lowercase()
                if (heard == wakePhrase || (wakePhrase == DEFAULT_WAKE && heard in wakeAliases)) {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK)
                    startCommandMode()
                }
            }
            Mode.COMMAND -> {
                handler.removeCallbacks(commandTimeout)
                runCommand(text)
            }
        }
    }

    private fun runCommand(text: String) {
        val parsed = resolve(text)
        if (parsed == null) { unknown(text); return }
        execute(parsed)
    }

    private fun execute(parsed: CommandParser.Parsed) {
        Logger.log("Command: ${parsed.description}")
        executor.execute {
            val ok = when (val target = parsed.target) {
                is CommandParser.Target.Adb -> adb.execute(host, port, target.shell)
                is CommandParser.Target.Ir -> irRouter.send(esp32Host, nodeHost, target.name)
                is CommandParser.Target.IrLearned -> irRouter.sendLearned(esp32Host, nodeHost, target.device, target.buttons)
            }
            handler.post {
                tone.startTone(if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK)
                startWakeMode()
            }
        }
    }

    // ---- lifecycle helpers ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice control", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setContentTitle("Automatebot")
            .setContentText("Voice control is listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(commandTimeout)
        relay?.stop()
        try { speech?.shutdown() } catch (_: Exception) {}
        try { cloud?.destroy() } catch (_: Exception) {}
        executor.shutdownNow()
        tone.release()
        adb.disconnect()
        Logger.log("Voice control stopped")
        super.onDestroy()
    }
}
