package com.whatsupbuds

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Manifest-registered receiver — the authority for connection state.
 *
 * These are protected system broadcasts: the OS delivers them to a manifest
 * receiver (starting our process if needed) even when the app has been killed or
 * frozen. So we drive the notification's connected/disconnected state from the
 * real Bluetooth events here, rather than from the health of our RFCOMM socket:
 *
 *  - Connect (ACL or an audio profile reaching CONNECTED) → start the service,
 *    which opens RFCOMM and shows the live battery.
 *  - Disconnect (ACL, or all audio profiles DISCONNECTED) → post the
 *    "Disconnected" notification directly (works even if the service is dead)
 *    and tell the service to release its RFCOMM/wake lock and stop.
 *
 * Profile connection-state changes fire the instant the earbuds sleep / the lid
 * closes; ACL is a slower but always-delivered backstop.
 */
class BtConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BtConnectionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val device = extractDevice(intent)

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (device != null && isAudioDevice(device)) onConnected(context, device)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(context)

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (device != null) onConnected(context, device)
                    BluetoothProfile.STATE_DISCONNECTED ->
                        // Only a real disconnect once no audio profile is left.
                        if (!anyAudioProfileConnected(context)) onDisconnected(context)
                }
            }
        }
    }

    private fun onConnected(context: Context, device: BluetoothDevice) {
        Log.i(TAG, "Connected: ${device.address}")
        val svc = Intent(context, BudsService::class.java)
            .putExtra(BudsService.EXTRA_DEVICE, device)
        // A connect broadcast is on the allowlist for starting a FGS in the bg.
        ContextCompat.startForegroundService(context, svc)
    }

    private fun onDisconnected(context: Context) {
        Log.i(TAG, "Disconnected")
        // 1) Guaranteed display — update the notification directly. This does not
        //    depend on the service being alive, so it survives the OS killing it.
        BudsService.showDisconnectedNotification(context)
        // 2) If the service is alive, tell it to release RFCOMM + wake lock + FGS.
        try {
            context.startService(
                Intent(context, BudsService::class.java)
                    .setAction(BudsService.ACTION_DISCONNECTED)
            )
        } catch (e: Exception) {
            // Service not running / background start blocked — the direct
            // notification above already reflects the disconnect.
            Log.d(TAG, "Service not running to stop", e)
        }
    }

    /** True if A2DP or HEADSET still reports a connected device. */
    private fun anyAudioProfileConnected(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Can't query — assume gone so a disconnect is never missed.
            return false
        }
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return false
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
                BluetoothProfile.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
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
