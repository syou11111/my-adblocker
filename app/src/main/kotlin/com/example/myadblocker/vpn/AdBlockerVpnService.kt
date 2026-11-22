package com.example.myadblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import com.example.myadblocker.MainActivity
import com.example.myadblocker.R
import com.example.myadblocker.tile.AdBlockerTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AdBlockerVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnJob: Job? = null
    private var pauseJob: Job? = null
    private val writeLock = Any()

    override fun onCreate() {
        super.onCreate()
        AdBlockerState.init(this)
        BlocklistManager.init(this)
        ExcludedAppsManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartVpn()
                return START_STICKY
            }
            ACTION_PAUSE -> {
                if (AdBlockerState.isRunning.value) {
                    val seconds = intent.getIntExtra(EXTRA_PAUSE_SECONDS, 60)
                    pauseBlocking(seconds)
                }
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (AdBlockerState.isRunning.value) {
                    resumeBlocking()
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                startForegroundNotification()
                startVpn()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "広告ブロックサービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ローカルDNS広告ブロッカーの稼働状態"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isPaused = AdBlockerState.isPaused.value
        val remaining = AdBlockerState.pauseRemainingSeconds.value

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openIntent)
            .setOngoing(true)

        if (isPaused) {
            val resumePending = PendingIntent.getService(
                this,
                1,
                Intent(this, AdBlockerVpnService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder
                .setContentTitle("MyAdBlocker 一時停止中")
                .setContentText("残り ${remaining}秒 で自動再開します")
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "今すぐ再開",
                        resumePending
                    ).build()
                )
        } else {
            val pausePending = PendingIntent.getService(
                this,
                2,
                Intent(this, AdBlockerVpnService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_PAUSE_SECONDS, 60)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder
                .setContentTitle("MyAdBlocker 稼働中")
                .setContentText("ローカルDNSフィルタで広告をブロックしています")
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "1分一時停止",
                        pausePending
                    ).build()
                )
        }

        return builder.build()
    }

    private fun startVpn() {
        if (vpnJob?.isActive == true) return

        val builder = Builder()
            .setSession("MyAdBlocker")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            .setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disallow self package", e)
        }

        for (pkg in ExcludedAppsManager.getExcludedPackages()) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disallow package: $pkg", e)
            }
        }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "Failed to establish VPN interface (permission missing?)")
            stopVpn()
            return
        }

        vpnInterface = pfd
        AdBlockerState.setRunning(true)
        requestTileSync()

        vpnJob = serviceScope.launch {
            runPacketLoop(pfd)
        }
    }

    private fun runPacketLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)
        val upstreamAddr = InetAddress.getByName(UPSTREAM_DNS)

        try {
            while (serviceScope.isActive && vpnInterface != null) {
                val length = input.read(buffer)
                if (length <= 0) continue

                val query = DnsPacketHandler.parseIpv4UdpDnsQuery(buffer, length) ?: continue

                val shouldBlock = !AdBlockerState.isPaused.value && BlocklistManager.isBlocked(query.domain)
                AdBlockerState.recordDnsQuery(applicationContext, query.domain, shouldBlock)

                if (shouldBlock) {
                    val blockedDns = DnsPacketHandler.buildBlockedDnsResponse(query)
                    val respPacket = DnsPacketHandler.buildResponseIpUdpPacket(query, blockedDns)
                    writeToTun(output, respPacket)
                } else {
                    serviceScope.launch {
                        forwardDnsQuery(query, upstreamAddr, output)
                    }
                }
            }
        } catch (e: Exception) {
            if (AdBlockerState.isRunning.value) {
                Log.d(TAG, "VPN loop ended: ${e.message}")
            }
        }
    }

    private fun forwardDnsQuery(
        query: ParsedDnsPacket,
        upstreamAddr: InetAddress,
        output: FileOutputStream
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                Log.w(TAG, "Failed to protect upstream DNS socket")
                return
            }
            socket.soTimeout = 3000

            val outPacket = DatagramPacket(
                query.dnsPayload,
                query.dnsPayload.size,
                upstreamAddr,
                53
            )
            socket.send(outPacket)

            val respBuf = ByteArray(4096)
            val inPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(inPacket)

            val dnsResponse = respBuf.copyOfRange(0, inPacket.length)
            val ipUdpResp = DnsPacketHandler.buildResponseIpUdpPacket(query, dnsResponse)
            writeToTun(output, ipUdpResp)
        } catch (e: Exception) {
            Log.d(TAG, "DNS forward failed for ${query.domain}: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun writeToTun(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) {
            try {
                if (vpnInterface != null) {
                    output.write(packet)
                }
            } catch (e: Exception) {
                Log.d(TAG, "TUN write failed: ${e.message}")
            }
        }
    }

    private fun pauseBlocking(seconds: Int) {
        pauseJob?.cancel()
        val duration = seconds.coerceAtLeast(1)
        AdBlockerState.setPauseState(paused = true, remainingSeconds = duration)
        updateNotification()
        requestTileSync()

        pauseJob = serviceScope.launch {
            var remaining = duration
            while (remaining > 0 && isActive) {
                delay(1000L)
                remaining--
                AdBlockerState.setPauseState(paused = true, remainingSeconds = remaining)
                if (remaining > 0 && (remaining % 5 == 0 || remaining <= 5)) {
                    updateNotification()
                }
            }
            if (isActive) {
                resumeBlocking()
            }
        }
    }

    private fun resumeBlocking() {
        pauseJob?.cancel()
        pauseJob = null
        AdBlockerState.setPauseState(paused = false, remainingSeconds = 0)
        updateNotification()
        requestTileSync()
    }

    private fun restartVpn() {
        pauseJob?.cancel()
        pauseJob = null
        AdBlockerState.setPauseState(paused = false, remainingSeconds = 0)
        vpnJob?.cancel()
        vpnJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        startForegroundNotification()
        startVpn()
    }

    private fun stopVpn() {
        pauseJob?.cancel()
        pauseJob = null
        vpnJob?.cancel()
        vpnJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        AdBlockerState.setRunning(false)
        requestTileSync()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestTileSync() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, AdBlockerTileService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tile sync failed", e)
        }
    }

    companion object {
        private const val TAG = "AdBlockerVpnService"
        private const val CHANNEL_ID = "adblocker_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_DNS = "10.0.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1"

        const val ACTION_START = "com.example.myadblocker.ACTION_START"
        const val ACTION_STOP = "com.example.myadblocker.ACTION_STOP"
        const val ACTION_RESTART = "com.example.myadblocker.ACTION_RESTART"
        const val ACTION_PAUSE = "com.example.myadblocker.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.myadblocker.ACTION_RESUME"
        const val EXTRA_PAUSE_SECONDS = "extra_pause_seconds"

        fun pause(context: Context, seconds: Int) {
            if (!AdBlockerState.isRunning.value) return
            val intent = Intent(context, AdBlockerVpnService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_PAUSE_SECONDS, seconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun resume(context: Context) {
            if (!AdBlockerState.isRunning.value) return
            val intent = Intent(context, AdBlockerVpnService::class.java).apply {
                action = ACTION_RESUME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, AdBlockerVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockerVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restartIfRunning(context: Context) {
            if (!AdBlockerState.isRunning.value) return
            val intent = Intent(context, AdBlockerVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
