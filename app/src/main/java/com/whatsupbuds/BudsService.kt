package com.whatsupbuds

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
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

        // Fired by the notification's delete intent if the user swipes it away
        // while still connected — we immediately re-post to keep it sticky.
        const val ACTION_REPOST = "com.whatsupbuds.action.REPOST"

        private const val CHANNEL_ID = "whats_up_buds_status"
        private const val NOTIF_ID = 1001
        private const val PREFS_NAME = "buds_status"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"

        // Standard Serial Port Profile (SPP) UUID.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var socket: BluetoothSocket? = null
    private var deviceName: String = ""

    // Last "connected" notification, re-posted if the user dismisses it.
    @Volatile private var lastOngoingNotification: Notification? = null

    private val repostIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, BudsService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
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
        deviceName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE_NAME, "")
            .orEmpty()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Disconnect signal from the receiver.
        if (intent?.action == ACTION_DISCONNECTED) {
            closeSocket()
            showDisconnectedAndStop()
            return START_NOT_STICKY
        }

        // User swiped the notification away while connected — bring it back.
        if (intent?.action == ACTION_REPOST) {
            val n = lastOngoingNotification
            if (running && n != null) {
                startForegroundCompat(n)
                return START_STICKY
            }
            // Stale (service was recreated / already disconnected) — don't idle.
            stopSelf()
            return START_NOT_STICKY
        }

        val device: BluetoothDevice? = extractDevice(intent)
        if (device != null) {
            deviceName = safeDeviceName(device)
            rememberDeviceName(deviceName)
        }

        // Must call startForeground promptly after being started as a FGS.
        postOngoing(
            buildNotification(currentDeviceName(), getString(R.string.connecting), ongoing = true)
        )

        if (device == null) {
            // Nothing to show — remove the placeholder notification entirely.
            stopAndRemoveNotification()
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
                Log.i(TAG, "Connected via SPP service record")
            } catch (e: IOException) {
                Log.w(TAG, "Secure RFCOMM connect failed, trying insecure channel-1 fallback", e)
                sock = fallbackConnect(device)
                socket = sock
                if (sock != null) Log.i(TAG, "Connected via channel-1 fallback")
            }

            val s = sock ?: throw IOException("Unable to open RFCOMM socket")
            Log.i(TAG, "RFCOMM connected to ${device.address}, waiting for battery data")
            val input = s.inputStream
            val output = s.outputStream

            // Kick off with an explicit battery request. The device also pushes
            // updates unprompted, but this gives us an immediate first reading.
            try {
                val req = HuaweiProtocol.buildBatteryRequest()
                output.write(req)
                output.flush()
                Log.i(TAG, "Sent battery request: ${req.toHex()}")
            } catch (e: IOException) {
                Log.w(TAG, "Battery request write failed", e)
            }

            val framer = PacketFramer(input)
            while (running) {
                val pkt = framer.readPacket() ?: break // null == stream closed
                if (pkt.isEmpty()) continue             // bogus frame, resynced

                Log.d(TAG, "RX packet: ${pkt.toHex()}")
                if (!HuaweiProtocol.verifyCrc(pkt)) {
                    Log.d(TAG, "CRC mismatch, parsing leniently anyway")
                }

                val cmd = HuaweiProtocol.commandIdOf(pkt) ?: continue
                if (cmd == HuaweiProtocol.CMD_BATTERY || cmd == HuaweiProtocol.CMD_BATTERY_PUSH) {
                    val info = HuaweiProtocol.parseBattery(pkt)
                    if (info == null) {
                        Log.w(TAG, "Battery cmd 0x%04X but parse failed".format(cmd))
                        continue
                    }
                    Log.i(TAG, "Battery: $info")
                    updateBatteryNotification(info)
                } else {
                    Log.d(TAG, "Ignoring cmd 0x%04X".format(cmd))
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
                showDisconnectedAndStop()
            }
        }
    }

    /** Insecure-socket reflection fallback (channel 1) for finicky devices. */
    private fun fallbackConnect(device: BluetoothDevice): BluetoothSocket? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT for RFCOMM fallback")
            return null
        }
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

    @SuppressLint("MissingPermission") // Guarded below; lint cannot follow this API split.
    private fun cancelDiscovery() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Discovery cancellation is only an optimization. Do not request
            // the broader scan permission solely for this call.
            return
        }
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
        customBigContent: RemoteViews? = null,
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

        // While connected, re-post if the user tries to swipe it away.
        if (ongoing) {
            builder.setDeleteIntent(repostIntent)
        }

        if (customContent != null) {
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(customContent)
            customBigContent?.let { builder.setCustomBigContentView(it) }
        }
        return builder.build()
    }

    /**
     * Post/refresh the ongoing (connected) notification via startForeground
     * rather than notify(). Re-asserting the foreground-service binding on each
     * update keeps its non-dismissible flags alive across aggressive OEM
     * notification handling (Huawei/EMUI in particular), and updates in place
     * so it doesn't flicker or re-alert.
     */
    private fun postOngoing(notification: Notification) {
        lastOngoingNotification = notification
        startForegroundCompat(notification)
    }

    /** Post a dismissible (non-foreground) notification — used once detached. */
    private fun postDismissible(title: String, body: CharSequence) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, body, ongoing = false))
    }

    private fun updateBatteryNotification(info: HuaweiProtocol.BatteryInfo) {
        val title = currentDeviceName()
        postOngoing(
            buildNotification(
                title = title,
                body = formatBody(info),
                ongoing = true,
                customContent = buildBatteryContentView(R.layout.notification_battery, title, info),
                customBigContent = buildBatteryContentView(
                    R.layout.notification_battery_expanded,
                    title,
                    info,
                ),
            )
        )
    }

    private fun buildBatteryContentView(
        layoutId: Int,
        title: String,
        info: HuaweiProtocol.BatteryInfo,
    ): RemoteViews {
        val expanded = layoutId == R.layout.notification_battery_expanded
        return RemoteViews(packageName, layoutId).apply {
            setTextViewText(R.id.notification_device_name, title)
            // Only the expanded layout carries a status subtitle.
            if (expanded) {
                setTextViewText(R.id.notification_status, getString(R.string.connected))
            }
            bindBattery(R.id.notification_left_container, R.id.notification_left_value,
                R.id.notification_left_charging, info.leftPercent, info.leftCharging, expanded)
            bindBattery(R.id.notification_right_container, R.id.notification_right_value,
                R.id.notification_right_charging, info.rightPercent, info.rightCharging, expanded)
            bindBattery(R.id.notification_case_container, R.id.notification_case_value,
                R.id.notification_case_charging, info.casePercent, info.caseCharging, expanded)
        }
    }

    private fun RemoteViews.bindBattery(
        containerViewId: Int,
        valueViewId: Int,
        chargingViewId: Int,
        percent: Int,
        charging: Boolean,
        expanded: Boolean,
    ) {
        val value = if (percent in 0..100) "$percent%" else getString(R.string.na)
        setTextViewText(valueViewId, value)
        setInt(
            containerViewId,
            "setBackgroundResource",
            batteryPillBackground(percent, expanded),
        )
        setViewVisibility(chargingViewId, if (charging) View.VISIBLE else View.GONE)
    }

    private fun showDisconnected() {
        postDismissible(currentDeviceName(), getString(R.string.disconnected))
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
     * Show the dismissible "Disconnected" state and stop the service.
     *
     * Order matters: we must leave the foreground FIRST. While the notification
     * is still the foreground-service notification the system forces it
     * non-dismissible (FLAG_NO_CLEAR), so a non-ongoing repost only "sticks" as
     * dismissible once we've detached it from the service.
     */
    private fun showDisconnectedAndStop() {
        running = false
        lastOngoingNotification = null // don't let a late delete-intent resurrect it
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        showDisconnected() // ongoing = false → now genuinely swipeable
        stopSelf()
    }

    /** Stop the service and remove its notification entirely. */
    private fun stopAndRemoveNotification() {
        running = false
        lastOngoingNotification = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun rememberDeviceName(name: String) {
        if (name.isBlank()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_NAME, name)
            .apply()
    }

    /**
     * Background for a pill/card by level. Warning-only: neutral gray while
     * healthy, amber at or below 30%, red at or below 15%. An unknown reading
     * (percent out of 0..100) stays neutral.
     */
    private fun batteryPillBackground(percent: Int, expanded: Boolean): Int {
        return when (percent) {
            in 0..15 -> if (expanded) R.drawable.bg_battery_card_low else R.drawable.bg_battery_pill_low
            in 16..30 -> if (expanded) R.drawable.bg_battery_card_medium else R.drawable.bg_battery_pill_medium
            else -> if (expanded) R.drawable.bg_battery_card else R.drawable.bg_battery_pill
        }
    }
}
