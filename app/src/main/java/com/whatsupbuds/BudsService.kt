package com.whatsupbuds

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

/**
 * Foreground service that owns the RFCOMM connection AND the status
 * notification. The foreground-service notification IS the battery
 * notification — there is no second notification.
 */
class BudsService : Service() {

    companion object {
        private const val TAG = "BudsService"

        const val EXTRA_DEVICE = "extra_device"
        const val ACTION_DISCONNECTED = "com.whatsupbuds.action.DISCONNECTED"

        private const val CHANNEL_ID = "whats_up_buds_status"
        private const val NOTIF_ID = 1001

        // Standard Serial Port Profile (SPP) UUID.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var socket: BluetoothSocket? = null
    private var deviceName: String = ""
    private val notificationContentIntent: PendingIntent by lazy {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Disconnect signal from the receiver.
        if (intent?.action == ACTION_DISCONNECTED) {
            running = false
            closeSocket()
            showDisconnected()
            stopSelfKeepingNotification()
            return START_NOT_STICKY
        }

        val device: BluetoothDevice? = extractDevice(intent)
        if (device != null) {
            deviceName = safeDeviceName(device)
        }

        // Must call startForeground promptly after being started as a FGS.
        startForegroundCompat(
            buildNotification(currentDeviceName(), getString(R.string.connecting), ongoing = true)
        )

        if (device == null) {
            stopSelfKeepingNotification()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            worker = Thread { connectAndListen(device) }.apply {
                isDaemon = true
                name = "buds-rfcomm"
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        closeSocket()
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    // ---- Connection + read loop -------------------------------------------

    private fun connectAndListen(device: BluetoothDevice) {
        try {
            cancelDiscovery()

            var sock: BluetoothSocket? = try {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (se: SecurityException) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT for socket creation", se)
                null
            }
            socket = sock

            try {
                sock?.connect()
            } catch (e: IOException) {
                Log.w(TAG, "Secure RFCOMM connect failed, trying insecure fallback", e)
                sock = fallbackConnect(device)
                socket = sock
            }

            val s = sock ?: throw IOException("Unable to open RFCOMM socket")
            val input = s.inputStream
            val output = s.outputStream

            // Kick off with an explicit battery request. The device also pushes
            // updates unprompted, but this gives us an immediate first reading.
            try {
                output.write(HuaweiProtocol.buildBatteryRequest())
                output.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Battery request write failed", e)
            }

            val framer = PacketFramer(input)
            while (running) {
                val pkt = framer.readPacket() ?: break // null == stream closed
                if (pkt.isEmpty()) continue             // bogus frame, resynced

                if (!HuaweiProtocol.verifyCrc(pkt)) {
                    Log.d(TAG, "CRC mismatch, parsing leniently anyway")
                }

                val cmd = HuaweiProtocol.commandIdOf(pkt) ?: continue
                if (cmd == HuaweiProtocol.CMD_BATTERY || cmd == HuaweiProtocol.CMD_BATTERY_PUSH) {
                    val info = HuaweiProtocol.parseBattery(pkt) ?: continue
                    updateBatteryNotification(info)
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "RFCOMM connection ended: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception in read loop", e)
        } finally {
            closeSocket()
            if (running) {
                // Loop exited due to a lost connection rather than an explicit
                // disconnect broadcast — reflect that and shut down.
                running = false
                showDisconnected()
                stopSelfKeepingNotification()
            }
        }
    }

    /** Insecure-socket reflection fallback (channel 1) for finicky devices. */
    private fun fallbackConnect(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket", Int::class.javaPrimitiveType
            )
            val s = method.invoke(device, 1) as BluetoothSocket
            s.connect()
            s
        } catch (e: Exception) {
            Log.w(TAG, "Insecure fallback connect failed", e)
            null
        }
    }

    private fun cancelDiscovery() {
        try {
            val bm = getSystemService(BluetoothManager::class.java)
            bm?.adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // No BLUETOOTH_SCAN/CONNECT — connect can still succeed.
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
        }
    }

    // ---- Notification ------------------------------------------------------

    private fun formatBody(info: HuaweiProtocol.BatteryInfo): String {
        fun part(label: String, pct: Int, charging: Boolean): String {
            val value = if (pct in 0..100) "$pct%" else getString(R.string.na)
            return if (charging) "$label $value ⚡" else "$label $value"
        }
        return listOf(
            part("L", info.leftPercent, info.leftCharging),
            part("R", info.rightPercent, info.rightCharging),
            part(getString(R.string.case_label), info.casePercent, info.caseCharging),
        ).joinToString("  ·  ")
    }

    private fun buildNotification(
        title: String,
        body: CharSequence,
        ongoing: Boolean,
        customContent: RemoteViews? = null,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(notificationContentIntent)
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        if (customContent != null) {
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(customContent)
        }
        return builder.build()
    }

    /** Update in place so it doesn't flicker or re-alert on each battery tick. */
    private fun updateNotification(
        title: String,
        body: CharSequence,
        ongoing: Boolean,
        customContent: RemoteViews? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // Can't post without the runtime permission; nothing to do.
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, body, ongoing, customContent))
    }

    private fun updateBatteryNotification(info: HuaweiProtocol.BatteryInfo) {
        val title = currentDeviceName()
        updateNotification(
            title = title,
            body = formatBody(info),
            ongoing = true,
            customContent = buildBatteryContentView(title, info),
        )
    }

    private fun buildBatteryContentView(
        title: String,
        info: HuaweiProtocol.BatteryInfo,
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_battery).apply {
            setTextViewText(R.id.notification_device_name, title)
            bindBattery(R.id.notification_left_value, R.id.notification_left_charging,
                info.leftPercent, info.leftCharging)
            bindBattery(R.id.notification_right_value, R.id.notification_right_charging,
                info.rightPercent, info.rightCharging)
            bindBattery(R.id.notification_case_value, R.id.notification_case_charging,
                info.casePercent, info.caseCharging)
        }
    }

    private fun RemoteViews.bindBattery(
        valueViewId: Int,
        chargingViewId: Int,
        percent: Int,
        charging: Boolean,
    ) {
        val value = if (percent in 0..100) "$percent%" else getString(R.string.na)
        setTextViewText(valueViewId, value)
        batteryWarningColor(percent)?.let { color ->
            setTextColor(valueViewId, color)
        }
        setTextViewText(chargingViewId, if (charging) " ⚡" else "")
    }

    private fun showDisconnected() {
        updateNotification(currentDeviceName(), getString(R.string.disconnected), ongoing = false)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * Stop the service but detach (keep) the notification, so the final
     * "Disconnected" state stays visible and dismissible by the user.
     */
    private fun stopSelfKeepingNotification() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW // no sound, no heads-up
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Re-registering updates the visible name/description while preserving
        // notification settings the user has already chosen.
        nm.createNotificationChannel(channel)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun extractDevice(intent: Intent?): BluetoothDevice? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DEVICE)
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.takeIf { it.isNotBlank() }
                ?: device.address
                ?: currentDeviceName()
        } catch (e: SecurityException) {
            device.address ?: currentDeviceName()
        }
    }

    private fun currentDeviceName(): String {
        return deviceName.ifBlank { getString(R.string.unknown_device) }
    }

    private fun batteryWarningColor(percent: Int): Int? {
        val colorRes = when (percent) {
            in 0..20 -> R.color.battery_low
            in 21..49 -> R.color.battery_medium
            else -> return null
        }
        return ContextCompat.getColor(this, colorRes)
    }
}
