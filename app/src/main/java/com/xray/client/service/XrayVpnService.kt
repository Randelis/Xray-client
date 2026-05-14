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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> tearDown()
            else        -> setUp()
        }
        return START_STICKY
    }

    override fun onRevoke() = tearDown()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun setUp() {
        postForegroundNotification()
        tunFd = Builder()
            .setSession("XrayClient")
            .addAddress("10.0.0.1", 24)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(MTU)
            .setBlocking(false)
            .addDisallowedApplication(packageName)
            .establish() ?: return
        relayJob = scope.launch { relayPackets() }
    }

    private fun tearDown() {
        relayJob?.cancel()
        tunFd?.close()
        tunFd = null; relayJob = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private suspend fun relayPackets() {
        val fd  = tunFd ?: return
        val buf = ByteArray(MTU)
        FileInputStream(fd.fileDescriptor).use { tun ->
            while (coroutineContext.isActive) {
                val len = runCatching { tun.read(buf) }.getOrElse { -1 }
                if (len <= 0) { delay(1); continue }
                forwardToXray(buf, len)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun forwardToXray(packet: ByteArray, length: Int) { /* stub */ }

    private fun postForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Xray VPN Active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true).build())
    }

    companion object {
        const val ACTION_STOP      = "com.xray.client.VPN_STOP"
        private const val CHANNEL_ID      = "xray_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU             = 1500
    }
}
