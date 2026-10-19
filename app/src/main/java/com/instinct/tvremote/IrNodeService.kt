package com.instinct.tvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs the WiFi IR node on the phone that HAS an IR blaster.
 * Start it from the app's "Run IR node on this phone" button and leave it
 * running; the notification shows the address to type into the other phone.
 */
class IrNodeService : Service() {

    companion object {
        const val ACTION_START = "com.instinct.tvremote.NODE_START"
        const val ACTION_STOP = "com.instinct.tvremote.NODE_STOP"
        const val PORT = 8765
        private const val CHANNEL_ID = "irnode"

        @Volatile
        var server: IrNodeServer? = null
            private set

        fun isRunning() = server != null

        /** This phone's WiFi IPv4 address, e.g. "192.168.1.7". */
        fun wifiIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private var ir: ConsumerIrManager? = null

    override fun onCreate() {
        super.onCreate()
        ir = if (packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR))
            getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopNode()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (ir?.hasIrEmitter() != true) {
                    Logger.log("This phone has no IR blaster - the node cannot run here")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (server != null) return START_STICKY
                createChannel()
                startForegroundCompat()
                val s = IrNodeServer(
                    port = PORT,
                    hasIr = { true },
                    transmit = { freq, pattern ->
                        try {
                            ir?.transmit(freq, pattern)
                            true
                        } catch (e: Exception) {
                            Logger.log("IR transmit error: ${e.message ?: e.javaClass.simpleName}")
                            false
                        }
                    },
                    log = { Logger.log(it) }
                )
                s.start()
                server = s
                Logger.log("IR node running - type this on the other phone: ${wifiIp() ?: "?"}:$PORT")
            }
        }
        return START_STICKY
    }

    private fun stopNode() {
        server?.stop()
        server = null
    }

    override fun onDestroy() {
        stopNode()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "IR node", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setContentTitle("Automatebot IR node")
            .setContentText("Other phones can fire this phone's IR: ${wifiIp() ?: "?"}:$PORT")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, notification)
        }
    }
}
