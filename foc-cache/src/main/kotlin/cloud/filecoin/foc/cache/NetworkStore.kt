package cloud.filecoin.foc.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first orchestrator. Direct analog of
 * `logos-storage-nim/storage/stores/networkstore.nim::NetworkStore.getBlock`:
 *
 *   1. Ask the local store first.
 *   2. On miss, request bytes from the remote store.
 *   3. Populate the local store on the way back so subsequent reads become local hits.
 *
 * Also coalesces concurrent requests for the same pieceCid so we do not fetch the
 * same piece N times if N callers ask for it simultaneously — the Kotlin analog
 * of libstorage's `DownloadManager` "want handle" bookkeeping in
 * `blockexchange/engine/downloadmanager.nim`.
 *
 * The [remote] parameter is a [Retriever]. In default builds this is a
 * [RemoteStore] doing HTTP + Delegated Routing; a Bitswap-backed retriever
 * (e.g. via Nabu) can be dropped in without touching this class.
 */
internal class NetworkStore(
    private val local: LocalStore,
    private val remote: Retriever,
) {
    /** Per-pieceCid mutex so concurrent get() for the same piece collapses to one fetch. */
    private val inflight = ConcurrentHashMap<String, Mutex>()

    /**
     * Local-first read.
     *
     * @throws PieceUnavailableException if the local store misses and every remote
     *         endpoint fails.
     */
    suspend fun get(ref: PieceRef): ByteArray {
        // Fast path — no lock, no coroutine suspension.
        local.get(ref.pieceCid)?.let { return it }

        val mutex = inflight.computeIfAbsent(ref.pieceCid) { Mutex() }
        return try {
            mutex.withLock {
                // Re-check inside the critical section: another caller may have
                // filled the local store while we were queued.
                local.get(ref.pieceCid)?.let { return@withLock it }

                val bytes = remote.fetch(ref)
                // Optionally verify(pieceCid, bytes) here — see README caveats.
                try {
                    local.put(ref.pieceCid, bytes)
                } catch (_: QuotaExceededException) {
                    // Serve the bytes even if they don't fit in cache.
                }
                bytes
            }
        } finally {
            // Best-effort cleanup; leaving a stale Mutex is harmless.
            inflight.remove(ref.pieceCid, mutex)
        }
    }

    /** Local-only existence check; never hits the network. */
    fun exists(pieceCid: String): Boolean = local.hasBlock(pieceCid)

    /** Warm the cache — fire and (mostly) forget. Errors are propagated to the caller. */
    suspend fun warm(ref: PieceRef) {
        if (local.hasBlock(ref.pieceCid)) return
        get(ref)
    }

    fun remove(pieceCid: String): Boolean = local.remove(pieceCid)

    fun space(): SpaceInfo = local.space()
}
