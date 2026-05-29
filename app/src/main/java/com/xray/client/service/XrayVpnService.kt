package com.xray.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.xray.client.routing.AdaptiveRoutingEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.FileInputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class XrayVpnService : VpnService() {

    @Inject lateinit var routingEngine: AdaptiveRoutingEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunFd:    ParcelFileDescriptor? = null
    private var relayJob: Job?                  = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> tearDown()
            ACTION_START -> setUp()
            else         -> tearDown()   // unknown/implicit start ⇒ don't silently open a tunnel
        }
        return START_STICKY
    }

    override fun onRevoke() = tearDown()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // TUN setup
    // -------------------------------------------------------------------------

    private fun setUp() {
        postForegroundNotification()

        tunFd = Builder()
            .setSession("XrayClient")
            .addAddress("10.0.0.1", 24)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(MTU)
            .setBlocking(true)   // blocking read; avoids a busy spin in the relay loop
            .addDisallowedApplication(packageName)
            .establish() ?: return

        relayJob = scope.launch { relayPackets() }
    }

    private fun tearDown() {
        relayJob?.cancel()
        tunFd?.close()
        tunFd    = null
        relayJob = null
        @Suppress("DEPRECATION")
        stopForeground(true)   // API 33+ would use STOP_FOREGROUND_REMOVE; this works on all
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Packet relay — non-rooted userspace path
    // -------------------------------------------------------------------------

    private suspend fun relayPackets() {
        val fd  = tunFd ?: return
        val buf = ByteArray(MTU)

        // NOTE: device-wide TUN routing is not yet functional. Turning IP packets
        // into proxied flows needs a userspace tun2socks bridge (e.g. a bundled
        // hev-socks5-tunnel) that dials xray's SOCKS5 inbound through protect()'d
        // sockets — a non-rooted app cannot use iptables/tproxy for this. Until
        // that library is added, the supported path is SOCKS5 mode. We drain the
        // fd with a blocking read so the loop doesn't busy-spin.
        FileInputStream(fd.fileDescriptor).use { tun ->
            while (coroutineContext.isActive) {
                val len = runCatching { tun.read(buf) }.getOrElse { -1 }
                if (len <= 0) break
                // forwardToXray(buf, len)  // TODO: tun2socks bridge
            }
        }
    }

    // -------------------------------------------------------------------------
    // Foreground notification
    // -------------------------------------------------------------------------

    private fun postForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xray VPN Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_START     = "com.xray.client.VPN_START"
        const val ACTION_STOP      = "com.xray.client.VPN_STOP"
        private const val CHANNEL_ID      = "xray_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU             = 1500
    }
}
