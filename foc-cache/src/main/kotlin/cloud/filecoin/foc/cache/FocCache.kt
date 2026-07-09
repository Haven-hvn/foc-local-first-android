package cloud.filecoin.foc.cache

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream

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
     * Local-first retrieval. Returns cached bytes if fresh; otherwise fetches
     * from the first successful SP endpoint (or Trustless Gateway fallback),
     * stores them locally, and returns them.
     *
     * @throws PieceUnavailableException on total network failure with an empty cache.
     */
    suspend fun get(ref: PieceRef): ByteArray = net.get(ref)

    /**
     * Streaming variant. First emits nothing until the piece is available in the
     * local store (fetching it from the network if needed), then re-opens the
     * local file and emits [Config.chunkSize] byte chunks.
     *
     * This deliberately doesn't stream directly from the HTTP response so that
     * a subsequent read of the same piece is a local hit — matching how the
     * nim `StoreStream` walks the local `RepoStore`.
     */
    fun stream(ref: PieceRef): Flow<ByteArray> = flow {
        val bytes = net.get(ref)
        val stream = ByteArrayInputStream(bytes)
        val buf = ByteArray(chunkSize)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            emit(buf.copyOf(n))
        }
    }

    /** Offline-friendly existence check; touches only the local metadata + fs. */
    fun exists(pieceCid: String): Boolean = net.exists(pieceCid)

    /**
     * Prefetch [ref] into the local cache. Useful before entering offline mode
     * or as a background job. Idempotent.
     */
    suspend fun fetch(ref: PieceRef) = net.warm(ref)

    /** Drop the piece from local cache (returns true if it was present). */
    fun remove(pieceCid: String): Boolean = net.remove(pieceCid)

    /** Quota / usage summary. */
    fun space(): SpaceInfo = net.space()
}
