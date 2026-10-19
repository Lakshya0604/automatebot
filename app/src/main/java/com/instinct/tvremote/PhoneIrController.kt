package com.instinct.tvremote

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager

/** Uses the phone's built-in IR blaster (ConsumerIrManager), when it has one. */
class PhoneIrController(context: Context) {

    private val irManager: ConsumerIrManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR))
            context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        else null

    fun available(): Boolean = irManager?.hasIrEmitter() == true

    fun send(name: String): Boolean {
        val ir = irManager
        if (ir == null || !ir.hasIrEmitter()) return false
        val signal = IrCodes.table[name]
        if (signal == null) {
            Logger.log("No IR code for '$name' in the app's code table yet")
            return false
        }
        val (freq, pattern) = IrCodes.patternFor(signal) ?: run {
            Logger.log("Protocol ${signal.protocol} not supported by the built-in encoder yet")
            return false
        }
        return try {
            ir.transmit(freq, pattern)
            Logger.log("Phone IR <- '$name'")
            true
        } catch (e: Exception) {
            Logger.log("Phone IR error: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun sendRaw(freq: Int, raw: IntArray): Boolean {
        val ir = irManager ?: return false
        return try { ir.transmit(freq, raw); true } catch (e: Exception) {
            Logger.log("Phone IR error: ${e.message}"); false
        }
    }
}
