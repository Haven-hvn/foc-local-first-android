package cloud.filecoin.foc.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Discovers where a CID is currently hosted — the Kotlin analog of what
 * `ipfs-check-main/dht.go` does when only a `cid` is passed (it queries the
 * Amino DHT and IPNI, returning a `providerOutput` list with `Source: "IPNI"`
 * or `Source: "Amino DHT"`).
 *
 * A mobile app cannot practically embed a full libp2p Kademlia node, so we
 * front the DHT and IPNI over **IPFS Delegated Routing v1** — an HTTP API that
 * IPFS itself defines for exactly this "no libp2p in my client" case. The
 * public endpoint at [DEFAULT_ENDPOINT] answers with both DHT and IPNI records
 * merged, and the wire format is the standard one from the IPFS Public
 * Delegated Routing spec, so alternate endpoints (self-hosted, a Kubo node
 * with `Routing.Type=autoclient`, or Storacha's someguy) can be swapped in.
 *
 * The resolver is used by [RemoteStore] when a [PieceRef] omits or exhausts
 * [PieceRef.providerServiceUrls]. Query order: try the PieceCID first (SPs with
 * `IPNI_PIECE=true` announce it), then the UnixFS root (SPs with
 * `IPNI_IPFS=true` announce that). Every discovered provider that advertises
 * the `transport-ipfs-gateway-http` protocol is turned into a usable HTTP
 * origin; results from other transports are surfaced too so callers can
 * inspect them (e.g. to see peer IDs).
 */
class ProviderResolver(
    private val endpoint: String = DEFAULT_ENDPOINT,
    timeoutMs: Long = 10_000,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Look up providers for [cid]. Returns an empty list if nothing is
     * advertised or the routing endpoint is unreachable — callers should treat
     * an empty list as "unresolved" and try any explicit endpoints they already
     * have.
     */
    suspend fun findProviders(cid: String): List<ProviderRecord> =
        withContext(Dispatchers.IO) {
            val url = "${endpoint.trimEnd('/')}/routing/v1/providers/$cid"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    parseProviders(body)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Resolve a list of HTTP origins that can serve [cid] over the Trustless
     * Gateway. Filters [findProviders] to `transport-ipfs-gateway-http`
     * providers and extracts an `https://host[:port]` origin from each.
     * Order is preserved so IPNI/DHT ranking is respected.
     */
    suspend fun findGatewayUrls(cid: String): List<String> {
        val providers = findProviders(cid)
        return providers.asSequence()
            .filter { GATEWAY_PROTOCOLS.any(it.protocols::contains) }
            .flatMap { it.gatewayOrigins().asSequence() }
            .distinct()
            .toList()
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Delegated Routing v1 servers may respond either as a JSON object with a
     * `"Providers"` array (the default when `Accept: application/json`) or as
     * newline-delimited JSON. Handle both.
     */
    private fun parseProviders(body: String): List<ProviderRecord> {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            val root = json.parseToJsonElement(trimmed)
            val items: List<JsonElement> = when {
                root is JsonObject && root["Providers"] != null ->
                    root["Providers"]!!.jsonArray.toList()
                root is JsonObject -> listOf(root) // single record
                else -> trimmed.lineSequence()
                    .filter { it.isNotBlank() }
                    .map(json::parseToJsonElement)
                    .toList()
            }
            items.mapNotNull { it.toProviderRecordOrNull() }
        } catch (_: Exception) {
            // Fall back to line-delimited JSON if the first parse failed.
            trimmed.lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull {
                    runCatching { json.parseToJsonElement(it).toProviderRecordOrNull() }
                        .getOrNull()
                }
                .toList()
        }
    }

    private fun JsonElement.toProviderRecordOrNull(): ProviderRecord? {
        val obj = this as? JsonObject ?: return null
        val id = obj["ID"]?.jsonPrimitive?.content ?: return null
        val addrs = obj["Addrs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        val schema = obj["Schema"]?.jsonPrimitive?.content
        val protocols = buildList {
            // "Protocol" (singular) is legacy; "Protocols" (plural, array) is current.
            obj["Protocol"]?.jsonPrimitive?.content?.let(::add)
            obj["Protocols"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.forEach(::add)
            // schema=peer records typically imply bitswap; keep the field for downstream inspection.
            if (schema == "peer" && isEmpty()) add("transport-bitswap")
        }
        return ProviderRecord(peerId = id, protocols = protocols, addrs = addrs, source = schema)
    }

    companion object {
        /**
         * IPFS Foundation's public delegated routing endpoint. It fans out to
         * IPNI (`cid.contact`) and the Amino DHT and merges results.
         */
        const val DEFAULT_ENDPOINT: String = "https://delegated-ipfs.dev"

        private val GATEWAY_PROTOCOLS = setOf(
            "transport-ipfs-gateway-http",
            "transport-http",
        )
    }
}

/**
 * A single provider record returned by a Delegated Routing v1 server.
 *
 * @property peerId    libp2p peer ID of the provider.
 * @property protocols e.g. `transport-bitswap`, `transport-ipfs-gateway-http`.
 * @property addrs     Multiaddrs; may include HTTPS transports for gateway providers.
 * @property source    Schema string as returned by the server (`peer`, `bitswap`, `http`, …).
 */
data class ProviderRecord(
    val peerId: String,
    val protocols: List<String>,
    val addrs: List<String>,
    val source: String? = null,
) {
    /**
     * Extract usable `https://host[:port]` (or `http://…`) origins from the
     * provider's multiaddrs. Understands the common gateway-advertising forms:
     *
     *   /dns4/example.com/tcp/443/https
     *   /dns/example.com/tcp/443/tls/http
     *   /ip4/1.2.3.4/tcp/8080/http
     */
    fun gatewayOrigins(): List<String> {
        val out = mutableListOf<String>()
        for (ma in addrs) {
            val parts = ma.trim('/').split('/')
            if (parts.isEmpty()) continue

            var host: String? = null
            var port: String? = null
            var scheme: String? = null
            var i = 0
            while (i < parts.size) {
                when (parts[i]) {
                    "dns", "dns4", "dns6", "dnsaddr" -> {
                        host = parts.getOrNull(i + 1); i += 2
                    }
                    "ip4", "ip6" -> {
                        host = parts.getOrNull(i + 1); i += 2
                    }
                    "tcp", "udp" -> {
                        port = parts.getOrNull(i + 1); i += 2
                    }
                    "https", "wss" -> { scheme = "https"; i += 1 }
                    "http", "ws" -> { scheme = scheme ?: "http"; i += 1 }
                    "tls" -> { scheme = "https"; i += 1 }
                    else -> i += 1
                }
            }
            if (host == null) continue
            val portPart = port?.let {
                val defaultForScheme = if (scheme == "https") "443" else "80"
                if (it == defaultForScheme) "" else ":$it"
            } ?: ""
            val effectiveScheme = scheme ?: "https"
            out += "$effectiveScheme://$host$portPart"
        }
        return out
    }
}
