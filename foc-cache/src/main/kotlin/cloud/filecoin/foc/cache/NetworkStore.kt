package cloud.filecoin.foc.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-first orchestrator. Direct analog of
 * `logos-storage-nim/storage/stores/networkstore.nim::NetworkStore.getBlock`:
 *
 *   1. Ask the local store first.
 *   2. On miss, stream the piece from the remote store into a temp file.
 *   3. Optionally run a [PieceTransform] over it, then install the result so
 *      subsequent reads become local hits.
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
    /** Per-pieceCid mutex so concurrent reads of the same piece collapse to one fetch. */
    private val inflight = ConcurrentHashMap<String, Mutex>()

    /**
     * A piece on disk. [cached] false means it didn't fit under the quota: [file] is a temp file
     * the caller must delete when done.
     */
    class Materialized(val file: File, val cached: Boolean)

    /**
     * Local hit, or fetch → transform → install. Never holds the piece in memory.
     *
     * @throws PieceUnavailableException if the local store misses and every remote
     *         endpoint fails.
     */
    suspend fun materialize(ref: PieceRef, transform: PieceTransform?): Materialized {
        // Fast path — no lock.
        local.file(ref.pieceCid)?.let { return Materialized(it, cached = true) }

        val mutex = inflight.computeIfAbsent(ref.pieceCid) { Mutex() }
        return try {
            mutex.withLock {
                // Re-check inside the critical section: another caller may have
                // filled the local store while we were queued.
                local.file(ref.pieceCid)?.let { return@withLock Materialized(it, cached = true) }
                withContext(Dispatchers.IO) { fetchAndInstall(ref, transform) }
            }
        } finally {
            // Best-effort cleanup; leaving a stale Mutex is harmless.
            inflight.remove(ref.pieceCid, mutex)
        }
    }

    private suspend fun fetchAndInstall(ref: PieceRef, transform: PieceTransform?): Materialized {
        val raw = local.newTempFile()
        var out: File? = null
        var handedOff: File? = null
        try {
            remote.fetchTo(ref, raw)
            // PieceCID verification, when it lands, belongs here: before the transform,
            // over the bytes that actually hash to the CID. See README caveats.
            val stored = if (transform == null) {
                raw
            } else {
                local.newTempFile().also { file ->
                    out = file
                    raw.inputStream().buffered().use { input ->
                        file.outputStream().buffered().use { output -> transform.transform(ref, input, output) }
                    }
                }
            }
            return try {
                Materialized(local.install(ref.pieceCid, stored), cached = true)
            } catch (_: QuotaExceededException) {
                // Serve it even though it doesn't fit; the caller deletes it.
                handedOff = stored
                Materialized(stored, cached = false)
            }
        } finally {
            // Temp files that weren't installed or handed off. Deleting a renamed path is a no-op.
            if (raw != handedOff) raw.delete()
            out?.let { if (it != handedOff) it.delete() }
        }
    }

    /** Local-only existence check; never hits the network. */
    fun exists(pieceCid: String): Boolean = local.hasBlock(pieceCid)

    fun remove(pieceCid: String): Boolean = local.remove(pieceCid)

    fun space(): SpaceInfo = local.space()
}
