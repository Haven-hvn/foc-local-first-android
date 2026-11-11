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
 *                                 Ignored when [autoQuota] is set.
 * @property autoQuota             If set, the quota is sized from the device's free space
 *                                 when the cache is constructed (see [AutoQuota]), and
 *                                 [quotaBytes] is only the fallback if free space can't
 *                                 be read.
 * @property blockTtl              How long a cached piece is considered fresh. Expired
 *                                 pieces are re-fetched on next access. Analog of
 *                                 `block-ttl` (default 30d in nim).
 * @property chunkSize             Streaming chunk size (bytes) for [FocCache.stream].
 *                                 Same role as the `chunkSize` arg in
 *                                 `storage_module_plugin.h::downloadToUrl`.
 * @property fetchTimeout          Per-attempt connect and read-inactivity timeout when
 *                                 fetching from an SP or gateway. Since 0.2.0 bodies are
 *                                 streamed to disk, so this bounds a stalled transfer,
 *                                 not the total time for a large piece.
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
 * @property enableChainProviderResolution
 *                                 When true (default) and a [PieceRef] carries no
 *                                 explicit `providerServiceUrls`, resolve the piece's
 *                                 storage providers from Filecoin's on-chain
 *                                 ServiceProviderRegistry for [PieceRef.chain] and
 *                                 HEAD-probe them. This is the writer-independent
 *                                 retrieval path: it needs no provider list in the
 *                                 entity, so it works for any uploader. Costs one
 *                                 cached chain read per network plus a HEAD per
 *                                 provider per piece.
 * @property registryCacheTtl       How long to cache a network's SP-registry read so
 *                                 a burst of pieces does not re-query the chain each time.
 * @property probeTimeout           Per-provider HEAD-probe timeout during chain resolution.
 */
data class Config(
    val cacheDir: File,
    val quotaBytes: Long = 20L * 1024 * 1024 * 1024,
    val autoQuota: AutoQuota? = null,
    val blockTtl: Duration = Duration.ofDays(30),
    val chunkSize: Int = 64 * 1024,
    val fetchTimeout: Duration = Duration.ofSeconds(30),
    val maxParallelFetches: Int = 3,
    val hedgeDelay: Duration = Duration.ofMillis(200),
    val enableProviderLookup: Boolean = true,
    val routingEndpoint: String = ProviderResolver.DEFAULT_ENDPOINT,

    val routingTimeout: Duration = Duration.ofSeconds(10),

    val enableChainProviderResolution: Boolean = true,
    val registryCacheTtl: Duration = Duration.ofDays(7),
    val probeTimeout: Duration = Duration.ofSeconds(8),
) {
    init {
        require(quotaBytes > 0) { "quotaBytes must be > 0" }
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(!blockTtl.isNegative) { "blockTtl must be non-negative" }
        require(maxParallelFetches >= 1) { "maxParallelFetches must be >= 1" }
        require(!hedgeDelay.isNegative) { "hedgeDelay must be non-negative" }
        require(!registryCacheTtl.isNegative) { "registryCacheTtl must be non-negative" }
        require(!probeTimeout.isNegative) { "probeTimeout must be non-negative" }
    }

}

/** Summary of the on-disk cache state; analog of the Logos storage module's `space()` result. */
data class SpaceInfo(
    val totalPieces: Long,
    val quotaMaxBytes: Long,
    val quotaUsedBytes: Long,
)
