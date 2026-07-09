package cloud.filecoin.foc.cache

import java.io.File
import java.time.Duration

/**
 * Configuration for [FocCache]. Field names deliberately mirror
 * `logos-storage-nim/storage/conf.nim` (`storage-quota`, `block-ttl`, `data-dir`)
 * so behavior maps cleanly between the two implementations.
 *
 * @property cacheDir              On-disk cache root. One subdirectory per piece.
 * @property quotaBytes            Hard cap for the on-disk cache in bytes. When exceeded,
 *                                 the LRU evictor drops least-recently-accessed pieces
 *                                 until usage is under the cap. Analog of
 *                                 `storage-quota` (default 21474836480 = 20 GiB).
 * @property blockTtl              How long a cached piece is considered fresh. Expired
 *                                 pieces are re-fetched on next access. Analog of
 *                                 `block-ttl` (default 30d in nim).
 * @property chunkSize             Streaming chunk size (bytes) for [FocCache.stream].
 *                                 Same role as the `chunkSize` arg in
 *                                 `storage_module_plugin.h::downloadToUrl`.
 * @property fetchTimeout          Per-attempt HTTP timeout when fetching from an SP or
 *                                 gateway. Total wall-clock time for a miss is bounded
 *                                 by (fetchTimeout × total endpoints).
 * @property maxParallelFetches    Cap on concurrent HTTP fetches for the same
 *                                 piece during the hedged race. FOC pieces are
 *                                 stored on ≥2 SPs (pricing minimum) and often
 *                                 more when secondaries are discovered via
 *                                 IPNI. `2`-`4` is a good default: enough
 *                                 parallelism to bypass a slow provider without
 *                                 wasting bandwidth if the first one is fast.
 * @property hedgeDelay            How long to wait before adding another
 *                                 parallel attempt during the race. `Duration.ZERO`
 *                                 fires all candidates immediately (max
 *                                 aggressiveness); a small value (150–300 ms)
 *                                 lets a fast primary complete before we spend
 *                                 mobile bandwidth on backups.
 * @property enableProviderLookup  If true and a piece's explicit endpoints all fail,

 *                                 the remote store falls back to
 *                                 [ProviderResolver] (Delegated Routing v1 over Amino DHT
 *                                 + IPNI). Same discovery surface ipfs-check uses.
 * @property routingEndpoint       Delegated Routing v1 endpoint. Defaults to
 *                                 [ProviderResolver.DEFAULT_ENDPOINT]. Point this at a
 *                                 self-hosted Kubo / someguy if you don't want to depend
 *                                 on the public one.
 * @property routingTimeout        HTTP timeout for the routing lookup itself.
 */
data class Config(
    val cacheDir: File,
    val quotaBytes: Long = 20L * 1024 * 1024 * 1024,
    val blockTtl: Duration = Duration.ofDays(30),
    val chunkSize: Int = 64 * 1024,
    val fetchTimeout: Duration = Duration.ofSeconds(30),
    val maxParallelFetches: Int = 3,
    val hedgeDelay: Duration = Duration.ofMillis(200),
    val enableProviderLookup: Boolean = true,
    val routingEndpoint: String = ProviderResolver.DEFAULT_ENDPOINT,

    val routingTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(quotaBytes > 0) { "quotaBytes must be > 0" }
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(!blockTtl.isNegative) { "blockTtl must be non-negative" }
        require(maxParallelFetches >= 1) { "maxParallelFetches must be >= 1" }
        require(!hedgeDelay.isNegative) { "hedgeDelay must be non-negative" }
    }

}

/** Summary of the on-disk cache state; analog of the Logos storage module's `space()` result. */
data class SpaceInfo(
    val totalPieces: Long,
    val quotaMaxBytes: Long,
    val quotaUsedBytes: Long,
)
