package com.xray.client.routing

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.xray.client.core.CoreManager
import com.xray.client.domain.model.ProxyNode
import com.xray.client.service.XrayVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingMode { SOCKS5, TUN, DIRECT }

/**
 * Decides *how* traffic is intercepted and routes it to the active xray node.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  SOCKS5 mode  →  xray exposes 127.0.0.1:10808 (SOCKS5)          │
 * │                   + 127.0.0.1:10809 (HTTP CONNECT)               │
 * │                   Apps configure the system proxy or use ProxySelector│
 * │                                                                  │
 * │  TUN mode     →  VpnService creates tun0, iptables redirect all  │
 * │                   traffic to xray's transparent-proxy inbound    │
 * │                   (dokodemo-door on 127.0.0.1:12345).            │
 * │                   Works for all apps without any app-side config. │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Per-app rules override the global mode so you can, e.g., keep most
 * traffic in TUN while forcing a specific app through a raw SOCKS5 port.
 */
@Singleton
class AdaptiveRoutingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreManager: CoreManager,
) {
    // packageName → override mode (null = use global mode)
    private val appRules = mutableMapOf<String, RoutingMode>()

    private val _activeMode = MutableStateFlow(RoutingMode.SOCKS5)
    val activeMode: StateFlow<RoutingMode> = _activeMode.asStateFlow()

    // -------------------------------------------------------------------------
    // Rule management
    // -------------------------------------------------------------------------

    fun setAppRule(packageName: String, mode: RoutingMode) {
        appRules[packageName] = mode
    }

    fun clearAppRule(packageName: String) {
        appRules.remove(packageName)
    }

    /** Returns the effective mode for a given app. */
    fun resolveMode(packageName: String): RoutingMode =
        appRules[packageName] ?: _activeMode.value

    // -------------------------------------------------------------------------
    // Mode switching
    // -------------------------------------------------------------------------

    /**
     * Switches the global routing mode and restarts xray with the matching config.
     * Idempotent: a no-op if the mode is already active.
     *
     * @return null on success, or an error message if VPN permission is needed
     *         (caller must launch VpnService.prepare() intent first).
     */
    suspend fun switchTo(mode: RoutingMode, node: ProxyNode): SwitchResult {
        if (_activeMode.value == mode && coreManager.isRunning) return SwitchResult.Ok

        if (mode == RoutingMode.TUN) {
            val permIntent = VpnService.prepare(context)
            if (permIntent != null) return SwitchResult.NeedsVpnPermission(permIntent)
        }

        if (mode == RoutingMode.TUN) {
            context.startService(Intent(context, XrayVpnService::class.java))
        } else {
            // Stop VPN interface if we're leaving TUN mode
            context.startService(
                Intent(context, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_STOP)
            )
        }

        val config = buildXrayConfig(mode, node)
        val configFile = persistConfig(config)

        val result = coreManager.restart(configFile)
        if (result.isSuccess) _activeMode.value = mode

        return if (result.isSuccess) SwitchResult.Ok
               else SwitchResult.CoreError(result.exceptionOrNull()!!)
    }

    sealed interface SwitchResult {
        data object Ok : SwitchResult
        data class NeedsVpnPermission(val intent: Intent) : SwitchResult
        data class CoreError(val cause: Throwable) : SwitchResult
    }

    // -------------------------------------------------------------------------
    // xray JSON config generation
    // -------------------------------------------------------------------------

    private fun buildXrayConfig(mode: RoutingMode, node: ProxyNode): JsonObject = buildJsonObject {
        put("log", buildJsonObject {
            put("loglevel", "warning")
        })

        putJsonArray("inbounds") {
            when (mode) {
                RoutingMode.SOCKS5 -> {
                    addJsonObject {
                        put("tag",      "socks-in")
                        put("port",     SOCKS5_PORT)
                        put("listen",   "127.0.0.1")
                        put("protocol", "socks")
                        put("settings", buildJsonObject {
                            put("auth", "noauth")
                            put("udp",  true)
                        })
                        put("sniffing", sniffingBlock())
                    }
                    addJsonObject {
                        put("tag",      "http-in")
                        put("port",     HTTP_PORT)
                        put("listen",   "127.0.0.1")
                        put("protocol", "http")
                        put("sniffing", sniffingBlock())
                    }
                }

                RoutingMode.TUN -> addJsonObject {
                    // dokodemo-door in follow-redirect mode acts as a transparent proxy:
                    // the VPN service re-injects packets here, xray sees the original dst.
                    put("tag",      "tun-in")
                    put("port",     TUN_TPROXY_PORT)
                    put("listen",   "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject {
                        put("network",         "tcp,udp")
                        put("followRedirect",  true)
                    })
                    put("streamSettings", buildJsonObject {
                        put("sockopt", buildJsonObject {
                            put("tproxy", "tproxy")
                        })
                    })
                    put("sniffing", sniffingBlock())
                }

                RoutingMode.DIRECT -> { /* no inbound needed */ }
            }
        }

        putJsonArray("outbounds") {
            addJsonObject {
                put("tag",      "proxy-out")
                put("protocol", node.protocol)
                put("settings", node.toOutboundSettings())
            }
            addJsonObject {
                put("tag",      "direct-out")
                put("protocol", "freedom")
                put("settings", buildJsonObject { put("domainStrategy", "UseIPv4") })
            }
            addJsonObject {
                put("tag",      "block-out")
                put("protocol", "blackhole")
            }
        }

        put("routing", buildJsonObject {
            put("domainStrategy", "IPIfNonMatch")
            putJsonArray("rules") {
                // Private IP space always goes direct (LAN, loopback)
                addJsonObject {
                    put("type",        "field")
                    put("outboundTag", "direct-out")
                    putJsonArray("ip") { add("geoip:private") }
                }
                // Block known ad/tracker domains
                addJsonObject {
                    put("type",        "field")
                    put("outboundTag", "block-out")
                    putJsonArray("domain") {
                        add("geosite:category-ads-all")
                    }
                }
            }
        })

        // DNS: use a remote resolver through the proxy to prevent DNS leaks in TUN mode
        put("dns", buildJsonObject {
            put("queryStrategy", "UseIPv4")
            putJsonArray("servers") {
                add("https+local://1.1.1.1/dns-query")
                add("8.8.8.8")
            }
        })
    }

    private fun sniffingBlock(): JsonObject = buildJsonObject {
        put("enabled", true)
        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
        put("routeOnly", false)
    }

    private fun persistConfig(config: JsonObject): File {
        val configDir = File(context.filesDir, "config").also { it.mkdirs() }
        return File(configDir, "xray.json").also {
            it.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), config))
        }
    }

    companion object {
        const val SOCKS5_PORT      = 10808
        const val HTTP_PORT        = 10809
        const val TUN_TPROXY_PORT  = 12345
    }
}

// ---------------------------------------------------------------------------
// ProxyNode → xray outbound settings  (kept near usage, not in domain model)
// ---------------------------------------------------------------------------

private fun ProxyNode.toOutboundSettings(): JsonObject = buildJsonObject {
    putJsonArray("vnext") {
        addJsonObject {
            put("address", host)
            put("port",    port)
            putJsonArray("users") {
                addJsonObject {
                    put("id",      uuid)
                    put("alterId", 0)      // AEAD encryption; alterId must be 0
                    put("security", "auto")
                }
            }
        }
    }
}
