package cloud.filecoin.foc.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * "Network on miss" side of the local-first design. Implements the three
 * retrieval paths documented at
 * https://docs.filecoin.cloud/core-concepts/retrieval/ :
 *
 *   1. **Direct SP `/piece` retrieval** (default): `GET {serviceUrl}/piece/{pieceCid}`.
 *      No egress charge. Latency in the seconds.
 *   2. **FilBeam CDN `/piece` retrieval** (opt-in via `withCDN`):
 *      `GET https://<address>.filbeam.io/<pieceCid>` (mainnet) or
 *      `<address>.calibration.filbeam.io` (calibration). Millisecond latency,
 *      metered egress. Only attempted when [PieceRef.cdnEnabled] is true.
 *   3. **IPFS `/ipfs` retrieval** (opt-in via `withIPFSIndexing`):
 *      Trustless Gateway on SPs (`{serviceUrl}/ipfs/{unixFsRoot}`) and, via
 *      [ProviderResolver], gateways discovered by Delegated Routing v1 over
 *      Amino DHT and IPNI. Only attempted when [PieceRef.ipfsIndexed] is true
 *      and a [PieceRef.unixFsRoot] is present.
 *
 * ### Hedged retrieval
 *
 * FOC uploads land on ≥2 SPs (pricing minimum: `$2.50/TiB/month/copy, minimum
 * 2 copies`). We race all applicable candidates in parallel with a small
 * `hedgeDelay` staircase and cancel losers, matching `synapse.download`'s
 * "SP-agnostic, probes providers in parallel" behavior described in the doc.
 * If `withCDN` is on, the FilBeam URL is placed at the front of the race — the
 * doc explicitly says "Setting `withCDN` races a FilBeam lookup alongside the
 * provider probes, so whichever path responds first wins."
 *
 * ### PieceCID verification (TODO)
 *
 * The FOC doc says: "Because the identifier is derived from the bytes, a client
 * can recompute the PieceCID of whatever it receives and confirm it matches
 * what was requested. The Synapse SDK does this automatically on every
 * download." **This implementation does not verify yet.** See README caveats.
 * Verification is required for parity and is queued for a follow-up.
 */
@OptIn(ExperimentalCoroutinesApi::class) // CompletableDeferred.getCompleted
internal class RemoteStore(
    private val config: Config,
    private val resolver: ProviderResolver? =
        if (config.enableProviderLookup)
            ProviderResolver(config.routingEndpoint, config.routingTimeout.toMillis())
        else null,
) : Retriever {

    // No callTimeout: bodies stream to disk and a large piece can legitimately take minutes.
    // connect + read (inactivity) timeouts still bound a dead or stalled provider.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(config.fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetch(ref: PieceRef): ByteArray = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("foc-", ".fetch", config.cacheDir.apply { mkdirs() })
        try {
            fetchTo(ref, tmp)
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Races the candidates on response headers, then streams the winner's body into [target].
     * If the winner fails mid-body, the race is re-run over the remaining candidates.
     */
    override suspend fun fetchTo(ref: PieceRef, target: File): Unit = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val candidates = buildCandidates(ref).toMutableList()

        if (candidates.isEmpty()) {
            throw PieceUnavailableException(
                "No candidate endpoints for piece ${ref.pieceCid} " +
                        "(providers=${ref.providerServiceUrls.size}, " +
                        "cdn=${ref.cdnEnabled}, ipfs=${ref.ipfsIndexed})",
            )
        }
        val total = candidates.size

        while (candidates.isNotEmpty()) {
            val (url, response) = hedgedRace(candidates, errors) ?: break
            candidates.remove(url)
            try {
                response.use { resp ->
                    val body = resp.body ?: throw IOException("Empty response body")
                    body.byteStream().use { input ->
                        target.outputStream().use { out -> input.copyTo(out, BODY_BUFFER) }
                    }
                }
                return@withContext
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: IOException) {
                recordError(errors, "$url -> body failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        throw PieceUnavailableException(
            "No endpoint could serve piece ${ref.pieceCid} " +
                    "($total candidates). Attempts: " +
                    errors.joinToString("; "),
        )
    }

    // -------------------------------------------------- candidate assembly

    private suspend fun buildCandidates(ref: PieceRef): List<String> {
        val out = LinkedHashSet<String>()

        // 1. FilBeam first when cdnEnabled — millisecond latency wins the race
        //    on cache hits. Placed first so the staircase gives it the head start.
        ref.filBeamUrl?.let { out += it }

        // 2. Direct SP /piece — the default path, all copies.
        for (sp in ref.providerServiceUrls) {
            out += pieceUrl(sp, ref.pieceCid)
        }

        // 3. IPFS retrieval — only when the data set was uploaded with
        //    withIPFSIndexing (per the doc: "opt-in per data set"). Otherwise
        //    UnixFS isn't guaranteed to be indexed and we should not spend a
        //    round trip on it.
        val ipfsRoot = ref.unixFsRoot
        if (ref.ipfsIndexed && ipfsRoot != null) {
            //   3a. Explicit SP /ipfs endpoints (Curio serves both /piece and /ipfs).
            for (sp in ref.providerServiceUrls) {
                out += ipfsUrl(sp, ipfsRoot)
            }
            //   3b. Explicit trustless gateways.
            for (gw in ref.trustlessGateways) {
                out += ipfsUrl(gw, ipfsRoot)
            }
            //   3c. Gateways discovered via Delegated Routing v1 (Amino DHT + IPNI).
            if (resolver != null) {
                for (origin in resolver.findGatewayUrls(ipfsRoot)) {
                    out += ipfsUrl(origin, ipfsRoot)
                }
            }
        }

        return out.toList()
    }

    // -------------------------------------------------- hedged race

    /**
     * Race [candidates] with hedged parallelism on **response headers**. Returns the first 2xx
     * response, still open, together with its URL; every other call is cancelled. The caller owns
     * (and must close) the returned response. Every attempt records its outcome in [errors].
     *
     * Deciding the winner on headers rather than on the full body means losers stop before they
     * download anything, and the body can be streamed to disk instead of buffered.
     */
    private suspend fun hedgedRace(
        candidates: List<String>,
        errors: MutableList<String>,
    ): Pair<String, Response>? = coroutineScope {
        val winner = CompletableDeferred<Pair<String, Response>>()
        val permits = Semaphore(config.maxParallelFetches.coerceAtLeast(1))
        val calls = java.util.concurrent.ConcurrentHashMap<String, Call>()
        val jobs = mutableListOf<Job>()

        for ((index, url) in candidates.withIndex()) {
            jobs += launch {
                try {
                    val stagger = config.hedgeDelay.toMillis() * index
                    if (stagger > 0) delay(stagger)
                    if (winner.isCompleted) return@launch
                    permits.withPermit {
                        if (winner.isCompleted) return@withPermit
                        try {
                            val call = http.newCall(Request.Builder().url(url).get().build())
                            calls[url] = call
                            val resp = call.execute()
                            when {
                                !resp.isSuccessful -> {
                                    resp.close()
                                    recordError(errors, "$url -> non-2xx (${resp.code})")
                                }
                                !winner.complete(url to resp) -> resp.close() // lost the race
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            recordError(errors, "$url -> ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                } catch (_: CancellationException) {
                    // Losing branches are cancelled; not an error.
                }
            }
        }

        val everyoneDone = launch {
            jobs.joinAll()
            if (!winner.isCompleted) winner.completeExceptionally(NoWinner)
        }

        val result = try {
            winner.await()
        } catch (_: NoWinner) {
            null
        } finally {
            // execute() blocks and ignores coroutine cancellation, so cancel the calls themselves.
            val won = if (winner.isCompleted && !winner.isCancelled) runCatching { winner.getCompleted().first }.getOrNull() else null
            calls.forEach { (url, call) -> if (url != won) call.cancel() }
            jobs.forEach { it.cancel() }
            everyoneDone.cancel()
        }
        result
    }

    private fun recordError(errors: MutableList<String>, msg: String) {
        synchronized(errors) { errors.add(msg) }
    }

    // -------------------------------------------------- url builders

    /** Direct SP `/piece` endpoint per the FOC Retrieval doc. */
    private fun pieceUrl(base: String, pieceCid: String): String =
        joinUrl(base, "/piece/$pieceCid")

    /**
     * SP `/ipfs` trustless-gateway endpoint per the FOC Retrieval doc. Curio
     * exposes both `/piece` and `/ipfs`; the latter serves an IPFS trustless
     * gateway response for the UnixFS root CID.
     */
    private fun ipfsUrl(base: String, cid: String): String =
        joinUrl(base, "/ipfs/$cid?format=raw")

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return b + p
    }
}

private const val BODY_BUFFER = 64 * 1024

/** Internal sentinel: "everyone finished without a winner." */
private object NoWinner : RuntimeException() { private fun readResolve(): Any = NoWinner }

/** Cheap suspend-join over a list of Jobs. */
private suspend fun List<Job>.joinAll() { for (j in this) j.join() }

/** All configured endpoints failed for a given piece. */
class PieceUnavailableException(msg: String) : RuntimeException(msg)
