package com.whatsupbuds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        private const val BATTERY_POLL_INTERVAL_MS = 60_000L
        private const val INITIAL_BATTERY_RETRY_INTERVAL_MS = 5_000L
        private const val INITIAL_BATTERY_RETRIES = 2
        private const val RFCOMM_CONNECT_TIMEOUT_MS = 12_000L

        // Reconnect backoff for when the SPP socket drops but the earbuds are
        // still ACL-connected — typically another app (e.g. Huawei AI Life)
        // grabbed the exclusive SPP channel. We wait for it to be released.
        private const val RECONNECT_BASE_MS = 4_000L
        private const val RECONNECT_MAX_MS = 30_000L
        // Cap only for when we can't confirm the device's connection state, so
        // a real disconnect that never broadcast ACL_DISCONNECTED still stops.
        private const val MAX_BLIND_RECONNECTS = 15

        // Standard Serial Port Profile (SPP) UUID.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /**
         * Post the dismissible "Disconnected" notification directly, without the
         * service needing to be alive. Called from the Bluetooth receiver so a
         * disconnect is reflected even if the OS already killed/froze the service
         * — the manifest receiver is still woken by the system to deliver the
         * broadcast, and this keeps the notification in sync with the real
         * Bluetooth state.
         */
        fun showDisconnectedNotification(context: Context) {
            ensureChannel(context)
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_DEVICE_NAME, "").orEmpty()
                .ifBlank { context.getString(R.string.unknown_device) }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setContentTitle(name)
                .setContentText(context.getString(R.string.disconnected))
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, notification)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_desc)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    @Volatile private var running = false
    @Volatile private var hasBatteryData = false
    private var worker: Thread? = null
    // Bumped whenever a worker is started or cancelled. A worker only acts while
    // its captured generation still matches — this stops a zombie worker (e.g.
    // one revived from a reconnect-backoff sleep) from ever running again.
    @Volatile private var workerGeneration = 0
    private var poller: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connectedOutput: OutputStream? = null
    private var deviceName: String = ""
    private val requestWriteLock = Any()

    // Real-time connection tracking: react to profile state changes the instant
    // the earbuds sleep/lid-close, rather than waiting for the ACL link timeout.
    private var connectionStateReceiver: BroadcastReceiver? = null
    @Volatile private var currentDeviceAddress: String? = null

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
            cancelWorker() // stop & invalidate the worker so it can't keep posting
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

        // Already running: a duplicate ACL_CONNECTED (multiple BT profiles, a
        // quick reconnect, or START_STICKY redelivery) must NOT reset a working
        // notification back to "Connecting…" — that's what made it look stuck
        // until the next slow poll. Keep the current state and nudge a refresh.
        if (running) {
            reassertNotification()
            requestBatteryNow()
            return START_STICKY
        }

        // Fresh start — show "Connecting…" while the worker opens the socket.
        // (Also satisfies the startForeground-promptly requirement for a FGS.)
        postOngoing(
            buildNotification(currentDeviceName(), getString(R.string.connecting), ongoing = true)
        )

        if (device == null) {
            // Nothing to connect to — remove the placeholder notification.
            stopAndRemoveNotification()
            return START_NOT_STICKY
        }

        startWorker(device)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        unregisterConnectionStateReceiver()
        cancelWorker()
        super.onDestroy()
    }

    /** Start a single fresh worker, cancelling any previous one first. */
    private fun startWorker(device: BluetoothDevice) {
        cancelWorker() // guarantee no lingering/zombie worker before we start
        running = true
        hasBatteryData = false
        registerConnectionStateReceiver(device)
        val myGen = workerGeneration
        worker = Thread { connectAndListen(device, myGen) }.apply {
            isDaemon = true
            name = "buds-rfcomm"
            start()
        }
    }

    /**
     * Listen for the audio-profile connection-state changes (and ACL disconnect)
     * so we react to a real disconnect immediately — the moment the lid closes —
     * instead of waiting for the RFCOMM socket to break or the ACL to time out.
     */
    private fun registerConnectionStateReceiver(device: BluetoothDevice) {
        currentDeviceAddress = device.address
        if (connectionStateReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running) return
                val eventAddress = extractDevice(intent)?.address
                // Ignore events for other Bluetooth devices when identifiable.
                if (eventAddress != null &&
                    currentDeviceAddress != null &&
                    eventAddress != currentDeviceAddress
                ) {
                    return
                }
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                        onRealtimeDisconnect("ACL disconnected")

                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        // Only stop once ALL audio profiles are actually down —
                        // one profile flapping shouldn't kill a live connection.
                        if (state == BluetoothProfile.STATE_DISCONNECTED &&
                            profileConnectionState() == false
                        ) {
                            onRealtimeDisconnect("audio profile disconnected")
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        connectionStateReceiver = receiver
    }

    private fun unregisterConnectionStateReceiver() {
        connectionStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        connectionStateReceiver = null
        currentDeviceAddress = null
    }

    private fun onRealtimeDisconnect(reason: String) {
        Log.i(TAG, "Real-time disconnect: $reason")
        cancelWorker()
        showDisconnectedAndStop()
    }

    /**
     * Invalidate and hard-stop the current worker: bump the generation (so it
     * fails its next liveness check), interrupt it (to break out of a backoff
     * sleep), and close the socket (to unblock a blocking read()).
     */
    private fun cancelWorker() {
        workerGeneration++
        worker?.interrupt()
        worker = null
        closeSocket()
    }

    private fun isCurrentWorker(generation: Int): Boolean =
        running && generation == workerGeneration

    // ---- Connection + read loop -------------------------------------------

    private fun connectAndListen(device: BluetoothDevice, myGen: Int) {
        try {
            var backoffAttempt = 0
            var blindRetries = 0

            while (isCurrentWorker(myGen)) {
                val hadSession = runSession(device, myGen)
                if (!isCurrentWorker(myGen)) break

                when (deviceConnectionState(device)) {
                    false -> {
                        // Confirmed no longer connected — a real disconnect.
                        Log.i(TAG, "Device no longer connected — stopping")
                        break
                    }
                    null -> {
                        // Can't confirm (hidden API blocked). Retry, but bounded
                        // so a silent real disconnect can't loop forever.
                        if (++blindRetries >= MAX_BLIND_RECONNECTS) {
                            Log.i(TAG, "Cannot confirm connection after $blindRetries tries — stopping")
                            break
                        }
                    }
                    true -> blindRetries = 0 // still connected; keep trying
                }

                // Socket dropped but the earbuds are still there — most likely
                // another app is holding the exclusive SPP channel. Back off and
                // retry until it's released; keep the last reading meanwhile.
                backoffAttempt = if (hadSession) 1 else backoffAttempt + 1
                val delayMs = (RECONNECT_BASE_MS * backoffAttempt).coerceAtMost(RECONNECT_MAX_MS)
                Log.i(TAG, "RFCOMM unavailable (another app may hold SPP); retrying in ${delayMs}ms")
                keepNotificationDuringReconnect(myGen)
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            // Only the current worker performs teardown; a superseded (zombie)
            // worker must not touch shared state or post notifications.
            if (myGen == workerGeneration && running) {
                // Loop exited because the device is genuinely gone, not because
                // of an explicit ACTION_DISCONNECTED (which stops us directly).
                showDisconnectedAndStop()
            }
        }
    }

    /**
     * One connect + read session. Returns true if it actually opened the socket
     * (used to reset reconnect backoff). Never posts "Disconnected"; the outer
     * loop decides whether to retry or stop.
     */
    private fun runSession(device: BluetoothDevice, myGen: Int): Boolean {
        var connected = false
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
                val secureSocket = sock ?: throw IOException("Unable to create RFCOMM socket")
                connectWithTimeout(secureSocket, "SPP service record")
                Log.i(TAG, "Connected via SPP service record")
            } catch (e: IOException) {
                Log.w(TAG, "Secure RFCOMM connect failed, trying insecure channel-1 fallback", e)
                try {
                    sock?.close()
                } catch (_: IOException) {
                }
                sock = fallbackConnect(device)
                socket = sock
                if (sock != null) Log.i(TAG, "Connected via channel-1 fallback")
            }

            val s = sock ?: throw IOException("Unable to open RFCOMM socket")
            connected = true
            Log.i(TAG, "RFCOMM connected to ${device.address}, waiting for battery data")
            val input = s.inputStream
            val output = s.outputStream
            connectedOutput = output

            // Request immediately, then use a low-frequency fallback poll.
            // Unsolicited device pushes are still handled as soon as they arrive.
            sendBatteryRequest(output, "initial")
            startBatteryPolling(s, output)

            val framer = PacketFramer(input)
            while (isCurrentWorker(myGen)) {
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
                    hasBatteryData = true
                    updateBatteryNotification(info)
                } else {
                    Log.d(TAG, "Ignoring cmd 0x%04X".format(cmd))
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "RFCOMM session ended: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception in session", e)
        } finally {
            closeSocket()
        }
        return connected
    }

    /**
     * ACL connection state of the device via the hidden BluetoothDevice#isConnected.
     * true = connected, false = not connected, null = couldn't determine.
     */
    private fun deviceConnectionState(device: BluetoothDevice): Boolean? {
        // Prefer the adapter's audio-profile state: it flips immediately when the
        // earbuds sleep / the lid closes, unlike the ACL link (which lingers to a
        // supervision timeout) and unlike reflective isConnected() (often stale).
        profileConnectionState()?.let { return it }
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean
        } catch (e: Exception) {
            null
        }
    }

    /**
     * true if an audio profile (A2DP/HEADSET) is connected, false if both are
     * disconnected, null if unknown/transitional or the permission is missing.
     */
    private fun profileConnectionState(): Boolean? {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            val a2dp = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
            val headset = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            when {
                a2dp == BluetoothProfile.STATE_CONNECTED ||
                    headset == BluetoothProfile.STATE_CONNECTED -> true
                a2dp == BluetoothProfile.STATE_DISCONNECTED &&
                    headset == BluetoothProfile.STATE_DISCONNECTED -> false
                else -> null // a profile is mid connecting/disconnecting
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /** During a reconnect wait, keep the last battery reading rather than flap. */
    private fun keepNotificationDuringReconnect(myGen: Int) {
        if (!isCurrentWorker(myGen)) return // superseded/disconnected — don't revive the FGS
        if (hasBatteryData) {
            lastOngoingNotification?.let { startForegroundCompat(it) }
        } else {
            postOngoing(
                buildNotification(currentDeviceName(), getString(R.string.connecting), ongoing = true)
            )
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
            socket = s
            connectWithTimeout(s, "channel-1 fallback")
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
        stopBatteryPolling()
        connectedOutput = null
        try {
            socket?.close()
        } catch (_: IOException) {
        } finally {
            socket = null
        }
    }

    /**
     * Re-assert the current notification (used on a duplicate connect) without
     * reverting to "Connecting…". Also satisfies the startForeground-promptly
     * requirement when the duplicate arrived via startForegroundService.
     */
    private fun reassertNotification() {
        val current = lastOngoingNotification
        if (current != null) {
            startForegroundCompat(current)
        } else {
            postOngoing(
                buildNotification(currentDeviceName(), getString(R.string.connecting), ongoing = true)
            )
        }
    }

    /** Nudge an immediate battery read if we already have a live connection. */
    private fun requestBatteryNow() {
        connectedOutput?.let { sendBatteryRequest(it, "reconnect nudge") }
    }

    private fun connectWithTimeout(socket: BluetoothSocket, connectionName: String) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Missing BLUETOOTH_CONNECT for $connectionName")
        }
        val finished = CountDownLatch(1)
        val watchdog = Thread {
            try {
                if (!finished.await(RFCOMM_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "$connectionName timed out after ${RFCOMM_CONNECT_TIMEOUT_MS}ms")
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            } catch (_: InterruptedException) {
                // connect() completed before the timeout.
            }
        }.apply {
            isDaemon = true
            name = "buds-connect-watchdog"
            start()
        }

        try {
            socket.connect()
        } finally {
            finished.countDown()
            watchdog.interrupt()
        }
    }

    private fun startBatteryPolling(
        connectedSocket: BluetoothSocket,
        output: OutputStream,
    ) {
        stopBatteryPolling()
        poller = Thread {
            var startupRetriesRemaining = INITIAL_BATTERY_RETRIES
            while (running && socket === connectedSocket) {
                val waitMs = if (!hasBatteryData && startupRetriesRemaining > 0) {
                    INITIAL_BATTERY_RETRY_INTERVAL_MS
                } else {
                    BATTERY_POLL_INTERVAL_MS
                }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    break
                }

                if (!running || socket !== connectedSocket) break
                val startupRetry = !hasBatteryData && startupRetriesRemaining > 0
                if (startupRetry) startupRetriesRemaining--
                val reason = if (startupRetry) "startup retry" else "periodic"
                if (!sendBatteryRequest(output, reason)) {
                    // Unblock the read loop so its normal disconnect cleanup runs.
                    try {
                        connectedSocket.close()
                    } catch (_: IOException) {
                    }
                    break
                }

                if (!hasBatteryData && startupRetriesRemaining == 0) {
                    postOngoing(
                        buildNotification(
                            currentDeviceName(),
                            getString(R.string.no_battery_data),
                            ongoing = true,
                        )
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "buds-battery-poller"
            start()
        }
    }

    private fun stopBatteryPolling() {
        poller?.interrupt()
        poller = null
    }

    private fun sendBatteryRequest(output: OutputStream, reason: String): Boolean {
        return try {
            val request = HuaweiProtocol.buildBatteryRequest()
            synchronized(requestWriteLock) {
                output.write(request)
                output.flush()
            }
            Log.d(TAG, "Sent $reason battery request: ${request.toHex()}")
            true
        } catch (e: IOException) {
            Log.w(TAG, "$reason battery request failed", e)
            false
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
        unregisterConnectionStateReceiver()
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
        unregisterConnectionStateReceiver()
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
        ensureChannel(this)
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
