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

@Singleton
class AdaptiveRoutingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreManager: CoreManager,
) {
    private val appRules = mutableMapOf<String, RoutingMode>()

    private val _activeMode = MutableStateFlow(RoutingMode.SOCKS5)
    val activeMode: StateFlow<RoutingMode> = _activeMode.asStateFlow()

    fun setAppRule(packageName: String, mode: RoutingMode) { appRules[packageName] = mode }
    fun clearAppRule(packageName: String) { appRules.remove(packageName) }
    fun resolveMode(packageName: String): RoutingMode = appRules[packageName] ?: _activeMode.value

    suspend fun switchTo(mode: RoutingMode, node: ProxyNode): SwitchResult {
        if (_activeMode.value == mode && coreManager.isRunning) return SwitchResult.Ok
        if (mode == RoutingMode.TUN) {
            val permIntent = VpnService.prepare(context)
            if (permIntent != null) return SwitchResult.NeedsVpnPermission(permIntent)
        }
        if (mode == RoutingMode.TUN) {
            context.startService(Intent(context, XrayVpnService::class.java))
        } else {
            context.startService(
                Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP)
            )
        }
        val config = buildXrayConfig(mode, node)
        val configFile = persistConfig(config)
        val result = coreManager.restart(configFile)
        if (result.isSuccess) _activeMode.value = mode
        return if (result.isSuccess) SwitchResult.Ok else SwitchResult.CoreError(result.exceptionOrNull()!!)
    }

    sealed interface SwitchResult {
        data object Ok : SwitchResult
        data class NeedsVpnPermission(val intent: Intent) : SwitchResult
        data class CoreError(val cause: Throwable) : SwitchResult
    }

    private fun buildXrayConfig(mode: RoutingMode, node: ProxyNode): JsonObject = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "warning") })
        putJsonArray("inbounds") {
            when (mode) {
                RoutingMode.SOCKS5 -> {
                    addJsonObject {
                        put("tag", "socks-in"); put("port", SOCKS5_PORT)
                        put("listen", "127.0.0.1"); put("protocol", "socks")
                        put("settings", buildJsonObject { put("auth", "noauth"); put("udp", true) })
                        put("sniffing", sniffingBlock())
                    }
                    addJsonObject {
                        put("tag", "http-in"); put("port", HTTP_PORT)
                        put("listen", "127.0.0.1"); put("protocol", "http")
                        put("sniffing", sniffingBlock())
                    }
                }
                RoutingMode.TUN -> addJsonObject {
                    put("tag", "tun-in"); put("port", TUN_TPROXY_PORT)
                    put("listen", "127.0.0.1"); put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject { put("network", "tcp,udp"); put("followRedirect", true) })
                    put("streamSettings", buildJsonObject { put("sockopt", buildJsonObject { put("tproxy", "tproxy") }) })
                    put("sniffing", sniffingBlock())
                }
                RoutingMode.DIRECT -> {}
            }
        }
        putJsonArray("outbounds") {
            addJsonObject {
                put("tag", "proxy-out"); put("protocol", node.protocol)
                put("settings", buildJsonObject {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", node.host); put("port", node.port)
                            putJsonArray("users") { addJsonObject { put("id", node.uuid); put("alterId", 0); put("security", "auto") } }
                        }
                    }
                })
            }
            addJsonObject { put("tag", "direct-out"); put("protocol", "freedom") }
            addJsonObject { put("tag", "block-out"); put("protocol", "blackhole") }
        }
        put("routing", buildJsonObject {
            put("domainStrategy", "IPIfNonMatch")
            putJsonArray("rules") {
                addJsonObject { put("type", "field"); put("outboundTag", "direct-out"); putJsonArray("ip") { add("geoip:private") } }
                addJsonObject { put("type", "field"); put("outboundTag", "block-out"); putJsonArray("domain") { add("geosite:category-ads-all") } }
            }
        })
        put("dns", buildJsonObject {
            put("queryStrategy", "UseIPv4")
            putJsonArray("servers") { add("https+local://1.1.1.1/dns-query"); add("8.8.8.8") }
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
