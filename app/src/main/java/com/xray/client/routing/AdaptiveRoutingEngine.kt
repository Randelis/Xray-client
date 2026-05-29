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
 *  SOCKS5 mode  →  xray exposes 127.0.0.1:10808 (SOCKS5) + 127.0.0.1:10809 (HTTP).
 *                  Apps point at this proxy (or it is bridged into the TUN).
 *
 *  TUN mode     →  VpnService creates a tun interface. NOTE: device-wide capture
 *                  still requires a userspace tun2socks bridge (see XrayVpnService);
 *                  until one is bundled, SOCKS5 is the supported path.
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

    // The node the core is currently running with (so we know when a switch is a no-op).
    private var activeNodeId: String? = null

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
     * Switches the global routing mode / active node and restarts xray.
     * Idempotent: a no-op only if BOTH the mode and the node are already active.
     */
    suspend fun switchTo(mode: RoutingMode, node: ProxyNode): SwitchResult {
        if (_activeMode.value == mode && activeNodeId == node.id && coreManager.isRunning) {
            return SwitchResult.Ok
        }

        if (mode == RoutingMode.TUN) {
            val permIntent = VpnService.prepare(context)
            if (permIntent != null) return SwitchResult.NeedsVpnPermission(permIntent)
            context.startService(
                Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START)
            )
        } else {
            // Tear down the VPN interface if we're leaving TUN mode.
            context.startService(
                Intent(context, XrayVpnService::class.java)
                    .setAction(XrayVpnService.ACTION_STOP)
            )
        }

        val config     = buildXrayConfig(mode, node)
        val configFile = persistConfig(config)

        val result = coreManager.restart(configFile)
        return if (result.isSuccess) {
            _activeMode.value = mode
            activeNodeId      = node.id
            SwitchResult.Ok
        } else {
            SwitchResult.CoreError(result.exceptionOrNull() ?: RuntimeException("Unknown core error"))
        }
    }

    fun onStopped() {
        activeNodeId = null
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
        put("log", buildJsonObject { put("loglevel", "warning") })

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
                    put("tag",      "tun-in")
                    put("port",     TUN_TPROXY_PORT)
                    put("listen",   "127.0.0.1")
                    put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject {
                        put("network",        "tcp,udp")
                        put("followRedirect", true)
                    })
                    put("streamSettings", buildJsonObject {
                        put("sockopt", buildJsonObject { put("tproxy", "tproxy") })
                    })
                    put("sniffing", sniffingBlock())
                }

                RoutingMode.DIRECT -> { /* no inbound needed */ }
            }
        }

        putJsonArray("outbounds") {
            add(buildProxyOutbound(node))
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
                // Keep LAN / loopback traffic off the proxy. Explicit CIDRs are used
                // instead of geoip:private so no geoip.dat asset is required.
                addJsonObject {
                    put("type",        "field")
                    put("outboundTag", "direct-out")
                    putJsonArray("ip") {
                        add("10.0.0.0/8");     add("172.16.0.0/12"); add("192.168.0.0/16")
                        add("127.0.0.0/8");    add("::1/128");       add("fc00::/7")
                        add("fe80::/10")
                    }
                }
            }
        })

        // Remote DNS over the proxy to avoid leaks; no geo assets needed.
        put("dns", buildJsonObject {
            put("queryStrategy", "UseIPv4")
            putJsonArray("servers") {
                add("https://1.1.1.1/dns-query")
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
        const val SOCKS5_PORT     = 10808
        const val HTTP_PORT       = 10809
        const val TUN_TPROXY_PORT = 12345
    }
}

// ---------------------------------------------------------------------------
// ProxyNode → xray outbound (protocol settings + stream settings)
// ---------------------------------------------------------------------------

private fun buildProxyOutbound(node: ProxyNode): JsonObject = buildJsonObject {
    put("tag",      "proxy-out")
    put("protocol", node.protocol)
    put("settings", node.outboundSettings())
    val stream = node.streamSettings()
    if (stream != null) put("streamSettings", stream)
}

private fun ProxyNode.outboundSettings(): JsonObject = when (protocol) {
    "trojan" -> buildJsonObject {
        putJsonArray("servers") {
            addJsonObject {
                put("address",  host)
                put("port",     port)
                put("password", uuid)
                if (flow.isNotBlank()) put("flow", flow)
            }
        }
    }
    else -> buildJsonObject {  // vless / vmess
        putJsonArray("vnext") {
            addJsonObject {
                put("address", host)
                put("port",    port)
                putJsonArray("users") {
                    addJsonObject {
                        put("id", uuid)
                        if (protocol == "vless") {
                            put("encryption", encryption.ifBlank { "none" })
                            if (flow.isNotBlank()) put("flow", flow)
                        } else {  // vmess
                            put("alterId",  alterId)
                            put("security", vmessSecurity.ifBlank { "auto" })
                        }
                    }
                }
            }
        }
    }
}

/** Builds streamSettings, or null when there's nothing non-default to emit. */
private fun ProxyNode.streamSettings(): JsonObject? {
    val net = network.ifBlank { "tcp" }
    val sec = security.ifBlank { "none" }
    if (net == "tcp" && sec == "none" && headerType.isBlank()) return null

    return buildJsonObject {
        put("network",  net)
        put("security", sec)

        when (sec) {
            "tls" -> put("tlsSettings", buildJsonObject {
                put("serverName", sni.ifBlank { host })
                if (alpn.isNotBlank()) putJsonArray("alpn") {
                    alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                }
                if (fingerprint.isNotBlank()) put("fingerprint", fingerprint)
                put("allowInsecure", false)
            })
            "reality" -> put("realitySettings", buildJsonObject {
                put("serverName",  sni.ifBlank { host })
                put("fingerprint", fingerprint.ifBlank { "chrome" })  // uTLS required for reality
                put("publicKey",   publicKey)
                if (shortId.isNotBlank()) put("shortId", shortId)
                if (spiderX.isNotBlank()) put("spiderX", spiderX)
            })
        }

        when (net) {
            "ws" -> put("wsSettings", buildJsonObject {
                if (path.isNotBlank()) put("path", path)
                if (hostHeader.isNotBlank()) put("headers", buildJsonObject { put("Host", hostHeader) })
            })
            "grpc" -> put("grpcSettings", buildJsonObject {
                put("serviceName", serviceName.ifBlank { path })
            })
            "h2", "http" -> put("httpSettings", buildJsonObject {
                if (path.isNotBlank()) put("path", path)
                if (hostHeader.isNotBlank()) putJsonArray("host") { add(hostHeader) }
            })
            "tcp" -> if (headerType == "http") put("tcpSettings", buildJsonObject {
                put("header", buildJsonObject {
                    put("type", "http")
                    if (hostHeader.isNotBlank() || path.isNotBlank()) {
                        put("request", buildJsonObject {
                            if (path.isNotBlank()) putJsonArray("path") { add(path) }
                            if (hostHeader.isNotBlank()) put("headers", buildJsonObject {
                                putJsonArray("Host") { add(hostHeader) }
                            })
                        })
                    }
                })
            })
        }
    }
}
