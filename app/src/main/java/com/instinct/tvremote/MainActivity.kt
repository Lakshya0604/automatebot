package com.instinct.tvremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var ipEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var wakeEdit: EditText
    private lateinit var esp32Edit: EditText
    private lateinit var geminiEdit: EditText
    private lateinit var dbEdit: EditText
    private lateinit var roomEdit: EditText
    private lateinit var devEdit: EditText
    private lateinit var btnEdit: EditText
    private val store by lazy { DeviceStore(this) }

    private val executor = Executors.newSingleThreadExecutor()
    private val adb by lazy { AdbController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        statusView = findViewById(R.id.statusView)
        ipEdit = findViewById(R.id.ipEdit)
        portEdit = findViewById(R.id.portEdit)
        wakeEdit = findViewById(R.id.wakeEdit)
        esp32Edit = findViewById(R.id.esp32Edit)
        geminiEdit = findViewById(R.id.geminiEdit)
        dbEdit = findViewById(R.id.dbEdit)
        roomEdit = findViewById(R.id.roomEdit)
        devEdit = findViewById(R.id.devEdit)
        btnEdit = findViewById(R.id.btnEdit)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        ipEdit.setText(prefs.getString("ip", ""))
        portEdit.setText(prefs.getString("port", "5555"))
        wakeEdit.setText(prefs.getString("wake_v2", "bot one"))
        geminiEdit.setText(prefs.getString("gemini", ""))
        dbEdit.setText(prefs.getString("db", "https://lakshya-studio-3d-default-rtdb.firebaseio.com"))
        var room = prefs.getString("room", "") ?: ""
        if (room.length < 16) {
            val b = ByteArray(16); java.security.SecureRandom().nextBytes(b)
            room = b.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("room", room).apply()
        }
        roomEdit.setText(room)
        esp32Edit.setText(prefs.getString("esp32", ""))

        findViewById<Button>(R.id.connectBtn).setOnClickListener {
            savePrefs()
            executor.execute { adb.execute(host(), port(), "echo connected") }
        }
        findViewById<Button>(R.id.webBtn).setOnClickListener {
            savePrefs()
            if (dbEdit.text.isBlank()) { Logger.log("Enter the Firebase database URL first"); return@setOnClickListener }
            val url = "https://lakshya0604.github.io/automatebot/#room=" + roomEdit.text.toString().trim() +
                "&db=" + java.net.URLEncoder.encode(dbEdit.text.toString().trim(), "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }
        findViewById<Button>(R.id.learnBtn).setOnClickListener {
            savePrefs()
            val d = devEdit.text.toString().trim().lowercase(); val b = btnEdit.text.toString().trim().lowercase()
            if (d.isEmpty() || b.isEmpty()) { Logger.log("Enter device and button name first"); return@setOnClickListener }
            Logger.log("Press '$b' on the $d remote now, pointing at the ESP8266 receiver (10 s)...")
            executor.execute {
                val text = IrBlasterController().learn(esp32())
                val sig = text?.let { store.parseLearned(it) }
                if (sig == null) Logger.log("Nothing learned. Check receiver wiring and ESP8266 IP.")
                else { store.put(d, b, sig); RemoteRelay.publishDevices(dbEdit.text.toString().trim(), roomEdit.text.toString().trim(), store)
                    Logger.log("Learned $d / $b (${sig.raw.size} pulses). Say: \"$d $b\"") }
            }
        }
        findViewById<Button>(R.id.testBtn).setOnClickListener {
            savePrefs()
            val d = devEdit.text.toString().trim().lowercase(); val b = btnEdit.text.toString().trim().lowercase()
            executor.execute { Logger.log(if (IrRouter(this).sendLearned(esp32(), d, listOf(b))) "Sent" else "Failed") }
        }
        findViewById<Button>(R.id.delBtn).setOnClickListener {
            val d = devEdit.text.toString().trim().lowercase(); val b = btnEdit.text.toString().trim().lowercase()
            store.delete(d, b.ifEmpty { null }); Logger.log("Deleted")
            RemoteRelay.publishDevices(dbEdit.text.toString().trim(), roomEdit.text.toString().trim(), store)
        }
        findViewById<Button>(R.id.listBtn).setOnClickListener {
            val names = store.deviceNames()
            if (names.isEmpty()) Logger.log("No learned devices yet")
            names.forEach { Logger.log("$it: ${store.buttonNames(it).joinToString(", ")}") }
        }
        findViewById<Button>(R.id.powerBtn).setOnClickListener { quick("input keyevent 26") }
        findViewById<Button>(R.id.volUpBtn).setOnClickListener { quick("input keyevent 24") }
        findViewById<Button>(R.id.volDownBtn).setOnClickListener { quick("input keyevent 25") }
        findViewById<Button>(R.id.homeBtn).setOnClickListener { quick("input keyevent 3") }
        findViewById<Button>(R.id.youtubeBtn).setOnClickListener {
            quick("monkey -p com.google.android.youtube.tv -c android.intent.category.LAUNCHER 1")
        }
        findViewById<Button>(R.id.startBtn).setOnClickListener {
            savePrefs()
            startListening()
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
        }

        Logger.log("1) Enter TV IP, tap Connect (accept the prompt on the TV)")
        Logger.log("2) Tap Start listening, allow the mic")
        Logger.log("3) Say the wake word, then a command")
    }

    override fun onResume() {
        super.onResume()
        Logger.listener = { msg -> runOnUiThread { append(msg) } }
    }

    override fun onPause() {
        super.onPause()
        Logger.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun append(msg: String) {
        statusView.append(msg + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun quick(cmd: String) {
        savePrefs()
        executor.execute { adb.execute(host(), port(), cmd) }
    }

    private fun startListening() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
            Logger.log("Allow the microphone, then tap Start listening again")
            return
        }
        val i = Intent(this, WakeWordService::class.java)
            .setAction(WakeWordService.ACTION_START)
            .putExtra(WakeWordService.EXTRA_HOST, host())
            .putExtra(WakeWordService.EXTRA_PORT, port())
            .putExtra(WakeWordService.EXTRA_WAKE, wake())
            .putExtra(WakeWordService.EXTRA_ESP32, esp32())
            .putExtra(WakeWordService.EXTRA_DB, dbEdit.text.toString().trim())
            .putExtra(WakeWordService.EXTRA_ROOM, roomEdit.text.toString().trim())
            .putExtra(WakeWordService.EXTRA_GEMINI, geminiEdit.text.toString().trim())
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun savePrefs() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("ip", host())
            .putString("port", portEdit.text.toString().trim())
            .putString("wake_v2", wake())
            .putString("gemini", geminiEdit.text.toString().trim())
            .putString("db", dbEdit.text.toString().trim())
            .putString("room", roomEdit.text.toString().trim())
            .putString("esp32", esp32())
            .apply()
    }

    private fun host() = ipEdit.text.toString().trim()
    private fun port() = portEdit.text.toString().trim().toIntOrNull() ?: 5555
    private fun wake() = wakeEdit.text.toString().trim().lowercase().ifEmpty { "bot one" }
    private fun esp32() = esp32Edit.text.toString().trim()
}
