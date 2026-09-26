package cloud.filecoin.foc.cache

import java.io.InputStream
import java.io.OutputStream

/**
 * Turns a retrieved piece into what the cache keeps.
 *
 * Runs once, when a piece first arrives from the network: [transform] reads the retrieved bytes and
 * writes the bytes to store, which are then kept under the same [PieceRef.pieceCid] and served by
 * every later read, including after a restart. Typical uses: decrypt once so the key isn't needed
 * again, decompress, or transcode.
 *
 * Both sides are streams, so a piece never has to fit in memory. [transform] is called on
 * `Dispatchers.IO`; blocking reads and writes are fine. Throwing aborts the fetch and nothing is
 * stored.
 *
 * **One representation per piece.** The cache doesn't record which transform produced the stored
 * bytes. Use the same transform (or none) for a given piece everywhere in the app; after changing
 * it, [FocCache.remove] the affected pieces.
 *
 * **Integrity.** Anything that checks the retrieved bytes against the PieceCID must run before the
 * transform — the stored bytes no longer hash to it. Nothing in this library re-hashes stored pieces.
 */
fun interface PieceTransform {
    suspend fun transform(ref: PieceRef, input: InputStream, output: OutputStream)
}

/**
 * Sizes the cache from the device instead of a fixed number: [fractionOfFree] of the free space
 * plus whatever the cache already holds, clamped to [minBytes]..[maxBytes]. Evaluated once, when the
 * [FocCache] is constructed, so the quota doesn't move under its own writes.
 */
data class AutoQuota(
    val fractionOfFree: Double = 0.5,
    val minBytes: Long = 512L * 1024 * 1024,
    val maxBytes: Long = 16L * 1024 * 1024 * 1024,
) {
    init {
        require(fractionOfFree > 0.0 && fractionOfFree <= 1.0) { "fractionOfFree must be in (0, 1]" }
        require(minBytes in 1..maxBytes) { "need 0 < minBytes <= maxBytes" }
    }

    /** Pure so it's testable without a filesystem. */
    fun resolve(usedBytes: Long, freeBytes: Long): Long =
        (usedBytes + (freeBytes.coerceAtLeast(0) * fractionOfFree).toLong()).coerceIn(minBytes, maxBytes)
}
