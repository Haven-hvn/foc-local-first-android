package cloud.filecoin.foc.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Public API. This is the class an app calls.
 *
 * Design mirrors the surface of `logos-storage-module`'s `StorageModuleImpl`
 * (`src/storage_module_plugin.h`): synchronous `exists`/`space`, coroutine-based
 * `get` / `stream` / `fetch` for asynchronous work, plus `remove`.
 *
 * The library is stateless with respect to uploads. Uploads are performed
 * through `synapse-sdk` (see README: `Upload flow`). The app receives a
 * [PieceRef] handle from that upload and hands it to this class to retrieve
 * the bytes with local-first semantics.
 *
 * Thread-safety: every public method is safe to call from any thread /
 * coroutine dispatcher. Internal serialization is per-piece so concurrent
 * requests for different pieces run in parallel; concurrent requests for the
 * same piece collapse into a single network fetch.
 *
 * ### Swapping the retrieval transport
 *
 * The default `retriever` is [RemoteStore], which fetches over HTTP (SP
 * PDPServer piece endpoints, Trustless Gateways, and gateways discovered via
 * Delegated Routing v1). If you need libp2p-native Bitswap retrieval, pass a
 * custom [Retriever] instead — see the [Retriever] KDoc for how to do this
 * with Nabu (`jvm-libp2p` alone does not implement kad-dht or Bitswap).
 */
class FocCache(
    context: Context,
    val config: Config,
    retriever: Retriever? = null,
) {
    private val local = LocalStore(context, config)
    private val remote: Retriever = retriever ?: RemoteStore(config)
    private val net = NetworkStore(local, remote)
    private val chunkSize: Int = config.chunkSize

    /**
     * Local-first retrieval into memory. Returns cached bytes if fresh; otherwise fetches from the
     * first successful SP endpoint (or Trustless Gateway fallback), stores them locally, and returns
     * them. For large pieces prefer [file] or [stream], which never hold the whole piece in memory.
     *
     * @param transform applied once when the piece arrives; see [PieceTransform].
     * @throws PieceUnavailableException on total network failure with an empty cache.
     */
    suspend fun get(ref: PieceRef, transform: PieceTransform? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val m = net.materialize(ref, transform)
            try {
                m.file.readBytes()
            } finally {
                if (!m.cached) m.file.delete()
            }
        }

    /**
     * The piece as a local file, fetching (and transforming) it first on a miss. Hand the path to
     * anything that reads files — a media player, a decoder — with no copy in memory.
     *
     * The file is owned by the cache: don't modify or delete it. It stays until the piece is evicted
     * or [remove]d; a stream already open on it keeps working after that. Each call counts as an
     * access for LRU.
     *
     * @throws QuotaExceededException if the piece is larger than the whole quota.
     * @throws PieceUnavailableException on total network failure with an empty cache.
     */
    suspend fun file(ref: PieceRef, transform: PieceTransform? = null): File {
        val m = net.materialize(ref, transform)
        if (!m.cached) {
            m.file.delete()
            throw QuotaExceededException("Piece ${ref.pieceCid} doesn't fit in the cache quota ($quotaBytes bytes)")
        }
        return m.file
    }

    /**
     * Streaming variant. Emits nothing until the piece is on disk (fetching it from the network if
     * needed), then reads the local file in [Config.chunkSize] chunks. The piece is never held in
     * memory as a whole, and a later read of the same piece is a local hit — matching how the nim
     * `StoreStream` walks the local `RepoStore`.
     */
    fun stream(ref: PieceRef, transform: PieceTransform? = null): Flow<ByteArray> = flow {
        val m = net.materialize(ref, transform)
        try {
            m.file.inputStream().use { input ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    emit(buf.copyOf(n))
                }
            }
        } finally {
            if (!m.cached) m.file.delete()
        }
    }.flowOn(Dispatchers.IO)

    /** Offline-friendly existence check; touches only the local metadata + fs. */
    fun exists(pieceCid: String): Boolean = net.exists(pieceCid)

    /**
     * Prefetch [ref] into the local cache, applying [transform] if it has to be fetched. Useful as a
     * background job. Idempotent.
     */
    suspend fun fetch(ref: PieceRef, transform: PieceTransform? = null) {
        val m = net.materialize(ref, transform)
        if (!m.cached) m.file.delete()
    }

    /** Drop the piece from local cache (returns true if it was present). */
    fun remove(pieceCid: String): Boolean = net.remove(pieceCid)

    /** The quota in force — [Config.quotaBytes], or the value [Config.autoQuota] resolved to. */
    val quotaBytes: Long get() = local.quotaBytes

    /** Quota / usage summary. */
    fun space(): SpaceInfo = net.space()
}
