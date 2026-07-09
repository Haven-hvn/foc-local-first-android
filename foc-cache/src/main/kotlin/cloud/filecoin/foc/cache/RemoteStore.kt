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
import okhttp3.OkHttpClient
import okhttp3.Request
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
internal class RemoteStore(
    private val config: Config,
    private val resolver: ProviderResolver? =
        if (config.enableProviderLookup)
            ProviderResolver(config.routingEndpoint, config.routingTimeout.toMillis())
        else null,
) : Retriever {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(config.fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .connectTimeout(config.fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(config.fetchTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    override suspend fun fetch(ref: PieceRef): ByteArray = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val candidates = buildCandidates(ref)

        if (candidates.isEmpty()) {
            throw PieceUnavailableException(
                "No candidate endpoints for piece ${ref.pieceCid} " +
                        "(providers=${ref.providerServiceUrls.size}, " +
                        "cdn=${ref.cdnEnabled}, ipfs=${ref.ipfsIndexed})",
            )
        }

        hedgedRace(candidates, errors)?.let { return@withContext it }

        throw PieceUnavailableException(
            "No endpoint could serve piece ${ref.pieceCid} " +
                    "(${candidates.size} candidates). Attempts: " +
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
     * Race [candidates] with hedged parallelism. Returns the first successful
     * body, cancelling the rest. Every attempt records its outcome in [errors]
     * for diagnostic purposes when everything fails.
     *
     * NOTE: The doc describes `synapse.download` as doing parallel `HEAD /piece/{cid}`
     * probes and then downloading from the first responder. Because our client
     * targets mobile and pieces are typically small (per-block, not per-DAG),
     * we skip HEAD and issue GETs directly — the win on latency outweighs the
     * cost of an extra body-read on a loser. Callers moving to very large
     * pieces should switch to HEAD-then-GET and are welcome to plug their own
     * [Retriever] via [FocCache].
     */
    private suspend fun hedgedRace(
        candidates: List<String>,
        errors: MutableList<String>,
    ): ByteArray? = coroutineScope {
        val winner = CompletableDeferred<ByteArray>()
        val permits = Semaphore(config.maxParallelFetches.coerceAtLeast(1))
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
                            val bytes = executeAndReadBytes(url)
                            if (bytes != null) {
                                winner.complete(bytes)
                            } else {
                                recordError(errors, "$url -> non-2xx")
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
            jobs.forEach { it.cancel() }
            everyoneDone.cancel()
        }
        result
    }

    private fun recordError(errors: MutableList<String>, msg: String) {
        synchronized(errors) { errors.add(msg) }
    }

    private fun executeAndReadBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: throw IOException("Empty response body for $url")
            return body.bytes()
        }
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

/** Internal sentinel: "everyone finished without a winner." */
private object NoWinner : RuntimeException() { private fun readResolve(): Any = NoWinner }

/** Cheap suspend-join over a list of Jobs. */
private suspend fun List<Job>.joinAll() { for (j in this) j.join() }

/** All configured endpoints failed for a given piece. */
class PieceUnavailableException(msg: String) : RuntimeException(msg)
