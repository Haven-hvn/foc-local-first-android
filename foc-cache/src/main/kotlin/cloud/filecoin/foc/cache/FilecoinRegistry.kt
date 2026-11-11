package cloud.filecoin.foc.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Resolves a piece's retrieval service URLs from Filecoin's on-chain
 * ServiceProviderRegistry — the writer-independent path.
 *
 * The reader does not trust any provider list baked into an entity. Given only a
 * [PieceRef.pieceCid] and the network it was uploaded to ([PieceRef.chain]), it:
 *
 *  1. reads the network's SP registry contract (`getProvidersByProductType`) over
 *     Filecoin's JSON-RPC `eth_call`, decoding each provider's `serviceURL`
 *     capability (hex-encoded UTF-8 on chain), and
 *  2. HEAD-probes `{serviceURL}/piece/{pieceCid}` across those providers, returning
 *     the ones that actually serve this piece.
 *
 * That is exactly what `@filoz/synapse-core`'s `chainResolver` +
 * `findPieceOnProviders` do in the TypeScript SDK, ported to Kotlin because the app
 * has no JS runtime / web3 / viem. It works for any uploader: no entity fields,
 * provider numbers, or per-record hints are required.
 *
 * The registry read is cached per network for [Config.registryCacheTtl] so a burst
 * of pieces does not re-query the chain for each one.
 */
internal class FilecoinRegistry(
    private val config: Config,
    private val http: OkHttpClient = defaultHttp(config),
) {
    /** One provider that answered a HEAD for the piece, with the base URL to use. */
    data class Provider(val id: Long, val serviceUrl: String)

    private val cache = java.util.concurrent.ConcurrentHashMap<FocChain, CachedRegistry>()

    private data class CachedRegistry(val providers: List<Provider>, val fetchedAtMillis: Long)

    /**
     * Providers on [chain] that currently serve [pieceCid].
     *
     * Returns an empty list (never throws) when the chain is unreachable, the
     * registry is empty, or no provider has the piece — callers treat that as
     * "unresolved" and fall back to whatever other candidates they have.
     */
    suspend fun findProviders(chain: FocChain, pieceCid: String): List<Provider> =
        withContext(Dispatchers.IO) {
            val providers = providersFor(chain)
            if (providers.isEmpty()) return@withContext emptyList()
            probeProviders(providers, pieceCid)
        }

    /** All active PDP providers on [chain] (cached). */
    private fun providersFor(chain: FocChain): List<Provider> {
        val cached = cache[chain]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAtMillis < config.registryCacheTtl.toMillis()) {
            return cached.providers
        }
        val network = FilecoinNetworks.resolve(chain) ?: return emptyList()
        val fetched = fetchProviders(network)
        cache[chain] = CachedRegistry(fetched, now)
        return fetched
    }

    /** Page through the registry and collect every provider's serviceURL. */
    private fun fetchProviders(network: FilecoinNetwork): List<Provider> {
        val out = LinkedHashMap<Long, String>()
        var offset = 0L
        var guard = 0
        while (guard++ < MAX_PAGES) {
            val data = FilecoinAbi.encodeGetProvidersByProductType(
                productType = PDP_PRODUCT_TYPE,
                onlyActive = true,
                offset = offset,
                limit = PAGE_LIMIT,
            )
            val raw = ethCall(network, data) ?: break
            val page = FilecoinAbi.decodeProvidersPage(raw)
            for (p in page.providers) {
                if (p.serviceUrl.isNotBlank()) out.putIfAbsent(p.id, p.serviceUrl)
            }
            if (!page.hasMore || page.providers.isEmpty()) break
            offset += page.providers.size
        }
        return out.map { Provider(it.key, it.value) }
    }

    private fun ethCall(network: FilecoinNetwork, data: String): String? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "eth_call")
            put(
                "params",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("to", network.serviceProviderRegistry)
                            put("data", data)
                        },
                    )
                    add("latest")
                },
            )
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(network.rpcUrl)
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                val json = Json.parseToJsonElement(text) as? JsonObject ?: return null
                if (json.containsKey("error")) return null
                val result = json["result"]?.jsonPrimitive?.content
                result?.takeIf { it.isNotBlank() && it != "0x" }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** HEAD-probe each provider's `/piece/{cid}`; return the ones that answer 2xx. */
    private suspend fun probeProviders(providers: List<Provider>, pieceCid: String): List<Provider> =
        withContext(Dispatchers.IO) {
            val found = ArrayList<Provider>()
            val semaphore = kotlinx.coroutines.sync.Semaphore(config.maxParallelFetches.coerceAtLeast(1))
            val lock = Any()
            coroutineScope {
                val jobs = providers.map { provider ->
                    launch {
                        semaphore.withPermit {
                            try {
                                val url = "${provider.serviceUrl.trimEnd('/')}/piece/$pieceCid"
                                val request = Request.Builder().url(url).head().build()
                                http.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        synchronized(lock) { found += provider }
                                    }
                                }
                            } catch (_: Exception) {
                                // Unreachable provider: skip.
                            }
                        }
                    }
                }
                withTimeoutOrNull(config.probeTimeout.toMillis()) { jobs.joinAll() }
            }
            found
        }

    companion object {
        /** `enum ProductType { PDP }` — PDP is product type 0. */
        private const val PDP_PRODUCT_TYPE = 0L
        private const val PAGE_LIMIT = 50L
        private const val MAX_PAGES = 50

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttp(config: Config): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.routingTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.routingTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()
    }
}

/** The Filecoin network constants the resolver needs, keyed by [FocChain]. */
internal data class FilecoinNetwork(
    val rpcUrl: String,
    val serviceProviderRegistry: String,
)

internal object FilecoinNetworks {
    // ServiceProviderRegistry addresses from @filoz/synapse-core chains.ts (verified live).
    val MAINNET = FilecoinNetwork(
        rpcUrl = "https://api.node.glif.io/rpc/v1",
        serviceProviderRegistry = "0xf55dDbf63F1b55c3F1D4FA7e339a68AB7b64A5eB",
    )
    val CALIBRATION = FilecoinNetwork(
        rpcUrl = "https://api.calibration.node.glif.io/rpc/v1",
        serviceProviderRegistry = "0x839e5c9988e4e9977d40708d0094103c0839Ac9D",
    )

    fun forChain(chain: FocChain): FilecoinNetwork? = when (chain) {
        FocChain.MAINNET -> MAINNET
        FocChain.CALIBRATION -> CALIBRATION
    }

    /** Test seam: override a network's RPC endpoint (e.g. to a mock server). */
    fun rpcUrlForTest(chain: FocChain, url: String) {
        val base = forChain(chain) ?: return
        overrides[chain] = base.copy(rpcUrl = url)
    }

    /** Test seam: drop all overrides. */
    fun clearForTest() {
        overrides.clear()
    }

    private val overrides = java.util.concurrent.ConcurrentHashMap<FocChain, FilecoinNetwork>()

    fun resolve(chain: FocChain): FilecoinNetwork? = overrides[chain] ?: forChain(chain)
}