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
import java.io.FileOutputStream
import java.net.InetAddress
import javax.inject.Inject

/**
 * Creates the TUN interface used in [AdaptiveRoutingEngine]'s TUN mode.
 *
 * Responsibility boundary
 * ───────────────────────
 * This service ONLY manages the TUN fd and the OS-level VPN session.
 * Traffic relay into xray's transparent-proxy port happens via the
 * kernel's iptables/nftables TPROXY rule — the service itself does not
 * copy packets in userspace.
 *
 * iptables rules (set up via `su` or using root/eBPF on rooted devices,
 * or via the Android VPN routing table on non-rooted devices):
 *   ip rule add fwmark 0x1 table 100
 *   ip route add local default dev lo table 100
 *   iptables -t mangle -A PREROUTING -p tcp -j TPROXY \
 *            --tproxy-mark 0x1 --on-port 12345 --on-ip 127.0.0.1
 *
 * On stock (non-rooted) Android, the VPN builder's addRoute() sends all
 * packets through the tun0 fd; the service forwards them to xray's
 * dokodemo-door inbound via a loopback socket.
 */
@AndroidEntryPoint
class XrayVpnService : VpnService() {

    @Inject lateinit var routingEngine: AdaptiveRoutingEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tunFd:     ParcelFileDescriptor? = null
    private var relayJob:  Job?                  = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> tearDown()
            else        -> setUp()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // OS called us back (e.g. user toggled off in Settings)
        tearDown()
    }

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
            .addRoute("0.0.0.0", 0)      // capture all IPv4 traffic
            .addRoute("::", 0)            // capture all IPv6 traffic
            .setMtu(MTU)
            .setBlocking(false)          // non-blocking read on the tun fd
            .also { builder ->
                // Exclude our own app to avoid routing loops
                builder.addDisallowedApplication(packageName)
            }
            .establish() ?: return      // null = VPN permission not granted yet

        relayJob = scope.launch { relayPackets() }
    }

    private fun tearDown() {
        relayJob?.cancel()
        tunFd?.close()
        tunFd   = null
        relayJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Packet relay  (non-rooted path — userspace copy to xray's tproxy port)
    //
    // On rooted devices you'd skip this entirely and rely on kernel TPROXY.
    // On stock devices, we copy each raw IP packet from tun0 to a local
    // TCP/UDP socket bound to xray's dokodemo-door inbound.
    // -------------------------------------------------------------------------

    private suspend fun relayPackets() {
        val fd  = tunFd ?: return
        val buf = ByteArray(MTU)

        FileInputStream(fd.fileDescriptor).use { tun ->
            while (isActive) {
                val len = runCatching { tun.read(buf) }.getOrElse { -1 }
                if (len <= 0) {
                    delay(1)    // yield; tun is non-blocking
                    continue
                }
                // Forward raw packet bytes to xray's transparent inbound.
                // Full implementation would parse the IP header to determine
                // protocol (TCP/UDP), then forward to the appropriate socket.
                forwardToXray(buf, len)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun forwardToXray(packet: ByteArray, length: Int) {
        // Production: parse IP header → create Socket/DatagramSocket
        // protected() via VpnService.protect() → connect to
        // 127.0.0.1:AdaptiveRoutingEngine.TUN_TPROXY_PORT with original dst
        // injected as the TPROXY destination.
        //
        // Stub: implementation varies by packet type; see libraries like
        // tun2socks or use WireGuard's boringtun for a production relay.
    }

    // -------------------------------------------------------------------------
    // Foreground notification  (required Android 14+ for VPN services)
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
        const val ACTION_STOP   = "com.xray.client.VPN_STOP"
        private const val CHANNEL_ID       = "xray_vpn"
        private const val NOTIFICATION_ID  = 1
        private const val MTU              = 1500
    }
}
