package cloud.filecoin.foc.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
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
 *
 * ### Why per-piece caching matters
 *
 * The active PDP registry on Filecoin has dozens of providers, many of them stale or
 * slow. Probing every one for every piece is expensive: a single piece open used to
 * pay that cost for the availability probe and again for the fetch. This class caches
 * the resolved provider list per `(chain, pieceCid)` for [Config.pieceResolutionTtl],
 * coalesces concurrent resolutions, and stops probing as soon as it has enough
 * providers to serve the piece (one for an availability check, two for a fetch, since
 * FOC stores at least two copies). See [resolve] and [probeProviders].
 */
internal class FilecoinRegistry(
    private val config: Config,
    private val http: OkHttpClient = defaultHttp(config),
    private val probeHttp: OkHttpClient = defaultProbeHttp(config),
) {
    /** One provider that answered a HEAD for the piece, with the base URL to use. */
    data class Provider(val id: Long, val serviceUrl: String)

    private val cache = ConcurrentHashMap<FocChain, CachedRegistry>()

    private data class CachedRegistry(val providers: List<Provider>, val fetchedAtMillis: Long)

    /** Per-chain single-flight for the registry read (the list itself is shared). */
    private val registryLocks = ConcurrentHashMap<FocChain, Mutex>()

    private val pieceCache = ConcurrentHashMap<String, CachedPiece>()

    /** Per-piece single-flight: concurrent callers share one probe instead of repeating it. */
    private val pieceInflight = ConcurrentHashMap<String, CompletableDeferred<List<Provider>>>()

    private data class CachedPiece(
        val providers: List<Provider>,
        val fetchedAtMillis: Long,
        /** False when the probe stopped early (availability check); the list may be partial. */
        val complete: Boolean,
    )

    /**
     * Providers on [chain] that currently serve [pieceCid].
     *
     * Probes in parallel and returns as soon as [targetProviders] answer 2xx; on a
     * partial result (for an availability check) or when the probe times out, it
     * returns whatever it found. Returns an empty list (never throws) when the chain
     * is unreachable, the registry is empty, or no provider has the piece — callers
     * treat that as "unresolved" and fall back to whatever other candidates they have.
     */
    suspend fun findProviders(chain: FocChain, pieceCid: String): List<Provider> =
        resolve(chain, pieceCid, targetProviders = MIN_FETCH_PROVIDERS)

    /**
     * The first provider on [chain] that serves [pieceCid], or null.
     *
     * This is the availability-check path: it stops the probe as soon as one provider
     * answers, so a dead provider later in the registry cannot add its full timeout to
     * the user's wait. The result is cached like [findProviders], so a fetch immediately
     * afterwards reuses it instead of probing the registry again.
     */
    suspend fun findAnyProvider(chain: FocChain, pieceCid: String): Provider? =
        resolve(chain, pieceCid, targetProviders = 1).firstOrNull()

    /** Cached resolution for [pieceCid], if one is still fresh. Used by [RemoteStore] on fetch. */
    suspend fun cachedProviders(chain: FocChain, pieceCid: String): List<Provider>? =
        withContext(Dispatchers.IO) { freshPiece(chain, pieceCid)?.providers }

    /** Registry contents for [chain], cache-aware. Used by [RemoteStore] warmup. */
    suspend fun allProviders(chain: FocChain): List<Provider> = providersFor(chain)

    private suspend fun resolve(
        chain: FocChain,
        pieceCid: String,
        targetProviders: Int,
    ): List<Provider> = withContext(Dispatchers.IO) {
        val key = pieceKey(chain, pieceCid)
        freshPiece(chain, pieceCid)?.let { return@withContext it.providers }

        // Coalesce concurrent resolutions for the same piece. Two callers (the
        // pre-disclosure probe and the fetch) must not both walk 50 providers.
        val existing = pieceInflight[key]
        if (existing != null) return@withContext existing.await()

        val deferred = CompletableDeferred<List<Provider>>()
        if (pieceInflight.putIfAbsent(key, deferred) != null) {
            return@withContext pieceInflight[key]!!.await()
        }

        try {
            val providers = providersFor(chain)
            val found = if (providers.isEmpty()) {
                emptyList()
            } else {
                probeProviders(providers, pieceCid, targetProviders)
            }
            // Cache the answer even when it is empty, briefly: a genuinely unavailable
            // piece should not make every "try again" tap pay for a full probe.
            pieceCache[key] = CachedPiece(
                providers = found,
                fetchedAtMillis = System.currentTimeMillis(),
                complete = found.size >= targetProviders,
            )
            deferred.complete(found)
            found
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            pieceInflight.remove(key, deferred)
        }
    }

    private fun freshPiece(chain: FocChain, pieceCid: String): CachedPiece? {
        val key = pieceKey(chain, pieceCid)
        val cached = pieceCache[key] ?: return null
        val ttl = if (cached.providers.isEmpty()) {
            config.negativePieceResolutionTtl
        } else {
            config.pieceResolutionTtl
        }
        if (System.currentTimeMillis() - cached.fetchedAtMillis < ttl.toMillis()) return cached
        pieceCache.remove(key, cached)
        return null
    }

    private fun pieceKey(chain: FocChain, pieceCid: String): String = "$chain:$pieceCid"

    /** All active PDP providers on [chain] (cached, single-flight). */
    private suspend fun providersFor(chain: FocChain): List<Provider> {
        val cached = cache[chain]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAtMillis < config.registryCacheTtl.toMillis()) {
            return cached.providers
        }
        val network = FilecoinNetworks.resolve(chain) ?: return emptyList()
        val lock = registryLocks.computeIfAbsent(chain) { Mutex() }
        return lock.withLock {
            // Another caller may have filled the cache while we waited for the lock.
            cache[chain]?.let { current ->
                if (System.currentTimeMillis() - current.fetchedAtMillis < config.registryCacheTtl.toMillis()) {
                    return@withLock current.providers
                }
            }
            val fetched = withContext(Dispatchers.IO) { fetchProviders(network) }
            cache[chain] = CachedRegistry(fetched, System.currentTimeMillis())
            fetched
        }
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

    /**
     * HEAD-probe each provider's `/piece/{cid}` in parallel.
     *
     * The probe stops as soon as [targetProviders] providers answer 2xx, or when
     * [Config.probeTimeout] elapses. Both the coroutine jobs and the underlying OkHttp
     * calls are cancelled on exit: `withTimeoutOrNull` around only `joinAll` does not
     * cancel the launched jobs, so a single unreachable provider used to keep the whole
     * probe alive for its full connect/read timeout.
     */
    private suspend fun probeProviders(
        providers: List<Provider>,
        pieceCid: String,
        targetProviders: Int,
    ): List<Provider> = withContext(Dispatchers.IO) {
        val target = targetProviders.coerceAtLeast(1)
        val found = ArrayList<Provider>(target)
        val lock = Any()
        // An availability check wants the first hit as fast as possible, so fan out to
        // every provider: with ~50 active PDPs and only two copies, capping at 16 would
        // leave the serving provider waiting behind a batch of dead hosts for a full
        // probe timeout. A fetch can afford the smaller, bandwidth-friendlier cap.
        val concurrency = if (target == 1) {
            providers.size.coerceIn(1, MAX_AVAILABILITY_PROBES)
        } else {
            config.probeConcurrency.coerceAtLeast(1)
        }
        val semaphore = Semaphore(concurrency)
        val calls = ConcurrentHashMap<Long, Call>()
        val enough = CompletableDeferred<Unit>()

        coroutineScope {
            val jobs = providers.map { provider ->
                launch {
                    semaphore.withPermit {
                        if (enough.isCompleted) return@withPermit
                        try {
                            val url = "${provider.serviceUrl.trimEnd('/')}/piece/$pieceCid"
                            val call = probeHttp.newCall(Request.Builder().url(url).head().build())
                            calls[provider.id] = call
                            call.execute().use { response ->
                                if (response.isSuccessful) {
                                    val reachedTarget = synchronized(lock) {
                                        found += provider
                                        found.size >= target
                                    }
                                    if (reachedTarget) enough.complete(Unit)
                                }
                            }
                        } catch (_: Exception) {
                            // Unreachable provider: skip.
                        } finally {
                            calls.remove(provider.id)
                        }
                    }
                }
            }

            try {
                // Stop as soon as the target is reached, or as soon as every probe has
                // answered with fewer than the target. If neither happens,
                // probeTimeout bounds the wait and the caller gets the partial result.
                val allDone = CompletableDeferred<Unit>()
                val monitor = launch {
                    jobs.forEach { it.join() }
                    allDone.complete(Unit)
                }
                try {
                    withTimeoutOrNull(config.probeTimeout.toMillis()) {
                        select<Unit?> {
                            enough.onAwait { Unit }
                            allDone.onAwait { Unit }
                        }
                    }
                } finally {
                    monitor.cancel()
                }
            } finally {
                // Cancel the jobs and, crucially, the blocking OkHttp calls. execute()
                // ignores coroutine cancellation, so cancelling the calls is what actually
                // stops a dead provider from holding the probe open.
                jobs.forEach { it.cancel() }
                calls.values.forEach { it.cancel() }
            }
        }

        synchronized(lock) { found.toList() }
    }

    companion object {
        /** `enum ProductType { PDP }` — PDP is product type 0. */
        private const val PDP_PRODUCT_TYPE = 0L
        private const val PAGE_LIMIT = 50L
        private const val MAX_PAGES = 50

        /** FOC stores at least two copies; stop probing once two providers serve the piece. */
        private const val MIN_FETCH_PROVIDERS = 2

        /** Upper bound on an availability probe's fan-out, even if a registry grows huge. */
        private const val MAX_AVAILABILITY_PROBES = 64

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttp(config: Config): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.routingTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.routingTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()

        /**
         * Probes are headers-only and must never inherit the long fetch timeout: a dead
         * provider has to fail in [Config.probeTimeout], not hold the whole resolution
         * open for [Config.fetchTimeout].
         */
        private fun defaultProbeHttp(config: Config): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.probeTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.probeTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(config.probeTimeout.toMillis(), TimeUnit.MILLISECONDS)
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