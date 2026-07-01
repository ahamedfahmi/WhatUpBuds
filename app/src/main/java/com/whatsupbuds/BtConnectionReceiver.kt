package com.whatsupbuds

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manifest-registered receiver for Bluetooth ACL connect/disconnect.
 *
 * On connect of an audio device: start the foreground service, passing the
 * BluetoothDevice. On disconnect: tell the (already-running) service to shut
 * down and flip the notification to "Disconnected".
 */
class BtConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BtConnectionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val device = extractDevice(intent) ?: return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!isAudioDevice(device)) return
                Log.i(TAG, "Audio device connected: ${device.address}")
                val svc = Intent(context, BudsService::class.java)
                    .putExtra(BudsService.EXTRA_DEVICE, device)
                // ACL_CONNECTED is on the allowlist for background FGS starts.
                ContextCompat.startForegroundService(context, svc)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "Device disconnected: ${device.address}")
                val svc = Intent(context, BudsService::class.java)
                    .setAction(BudsService.ACTION_DISCONNECTED)
                try {
                    // Delivering an action to an already-running FGS is allowed.
                    context.startService(svc)
                } catch (e: IllegalStateException) {
                    // Service wasn't running — nothing to disconnect.
                    Log.d(TAG, "No running service to stop", e)
                }
            }
        }
    }

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        return try {
            device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } catch (e: SecurityException) {
            // Without BLUETOOTH_CONNECT we can't inspect the class; assume yes
            // and let the service sort it out.
            true
        }
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
}
