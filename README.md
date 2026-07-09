# foc-local-first-android

A Kotlin/Android library that gives you the **local-first, retrieve-from-network-on-miss**
UX from the Logos storage module (`logos-storage-nim`'s `NetworkStore` + `RepoStore`),
retargeted at **Filecoin Onchain Cloud** content uploaded via `synapse-sdk`.

Design mirrors the three retrieval paths documented in the
[FOC Retrieval reference](https://docs.filecoin.cloud/core-concepts/retrieval/):

| Path | Source | Latency | When to use |
|------|--------|---------|-------------|
| **Direct SP `/piece`** | Storage provider Curio HTTP API | Seconds | Default. No egress charge. |
| **FilBeam CDN `/piece`** | Filecoin Beam cache (per-wallet subdomain) | Milliseconds | `withCDN` data sets. Metered egress. |
| **IPFS `/ipfs`** | SP `/ipfs` + IPFS trustless gateways | Varies | `withIPFSIndexing` data sets. |

## Architecture

```
FocCache (public API)
 └── NetworkStore (local-first orchestration + in-flight coalescing)
      ├── LocalStore     ── disk-backed cache: LRU + TTL + quota (SQLite meta)
      └── RemoteStore    ── hedged parallel race across all applicable paths:
           ├── (1) FilBeam URL (only if PieceRef.cdnEnabled)
           ├── (2) Direct SP /piece — every provider copy
           └── (3) IPFS /ipfs — only if PieceRef.ipfsIndexed:
                 ├── SP /ipfs endpoints
                 ├── Explicit trustless gateways
                 └── Gateways discovered via Delegated Routing v1 (Amino DHT + IPNI)
```

## Reliability model (why HTTP, not Bitswap)

The FOC doc puts it plainly: "Filecoin Onchain Cloud is built for a **store and
serve** model, where every piece you store stays retrievable over HTTP for the
life of its data set." Reliability at scale comes from three properties baked
into FOC, all HTTP-native:

1. **Location independence via PieceCID.** The same content is addressable across
   many providers and CDN edges.
2. **≥2 SPs per piece** (pricing minimum). Retrieval routes around a slow or
   offline provider. Our `RemoteStore` hedges across all copies in parallel.
3. **Verifiable downloads.** Every SDK response is checked against the PieceCID.
   Tampering is detected before it reaches the app. *(This library's MVP
   trusts the transport; PieceCID verification is queued — see caveats.)*

Bitswap would add **reach** (peers with no gateway), not **reliability**.
FilBeam, `synapse.download`'s SP-parallel probes, and the retrieval SLA are all
HTTP by design. This library follows the same pattern:
`synapse.download`'s "probes providers in parallel and downloads from the first
one that confirms it has the piece" is precisely what `RemoteStore.hedgedRace`
does.

### Multi-provider hedged race

1. Fire the first candidate (FilBeam if `cdnEnabled`, otherwise the primary SP).
2. Every `hedgeDelay` (default 200 ms), start another candidate in parallel,
   up to `maxParallelFetches` at once (default 3).
3. First candidate to return bytes wins; the rest are cancelled cleanly.

Staircase-start (vs. "fan out immediately") is deliberate on mobile: a fast
FilBeam hit or a nearby SP typically finishes before backups are even opened,
saving cellular bytes. Set `Config.hedgeDelay = Duration.ZERO` for maximum
aggressiveness.

## Provider discovery (like `ipfs-check`)

When a `PieceRef` is `ipfsIndexed`, `ProviderResolver` queries **Delegated
Routing v1** (`GET /routing/v1/providers/{cid}`) at `https://delegated-ipfs.dev`
by default — the HTTP-shaped front door for the **Amino DHT + IPNI**, which is
what `ipfs-check-main/dht.go` uses directly. Swap to a self-hosted Kubo
(`Routing.Type=autoclient`) or Storacha `someguy` via `Config.routingEndpoint`.

Providers advertising `transport-ipfs-gateway-http` are turned into HTTPS
origins by parsing their multiaddrs (`/dns4/host/tcp/443/https`, etc.) and
added to the hedged race for the IPFS path.

## Public API

- `get(ref)` — local-first fetch; returns cached bytes if fresh, else races the network.
- `stream(ref)` — same but as `Flow<ByteArray>`; backed by the local store so
  re-reads are hits.
- `exists(pieceCid)` — pure local check, no network.
- `fetch(ref)` — background warm-up; useful before entering offline mode.
- `remove(pieceCid)` — evict from local cache.
- `space()` — quota / used / count.

## `PieceRef` — what the app persists

```kotlin
data class PieceRef(
  val pieceCid: String,
  val size: Long,
  val providerServiceUrls: List<String>,   // Curio /piece and /ipfs base URLs (≥2 in FOC)
  val walletAddress: String? = null,        // required for FilBeam URL
  val cdnEnabled: Boolean = false,          // set to true iff uploaded withCDN
  val chain: FocChain = FocChain.MAINNET,   // MAINNET or CALIBRATION — picks FilBeam host
  val ipfsIndexed: Boolean = false,         // set to true iff uploaded withIPFSIndexing
  val unixFsRoot: String? = null,           // UnixFS root CID for /ipfs path
  val trustlessGateways: List<String> = DEFAULT_GATEWAYS,
)
```

Populate `cdnEnabled` / `ipfsIndexed` from the `withCDN` / `withIPFSIndexing`
options you passed to `synapse.storage.upload`. `walletAddress` and `chain` come
from the wallet used at upload time. `filBeamUrl` on `PieceRef` builds the
`https://<address>.filbeam.io/<pieceCid>` (or `.calibration.filbeam.io`) URL
automatically.

## What's out of scope

- **Uploads.** Happen via `synapse-sdk` (browser/WebView with Reown wallet, or
  a small Node backend). Mobile app persists the returned `PieceRef`.
- **PieceCID / CommP verification.** Required for parity with `synapse.download`
  ("validates the bytes against the PieceCID"). Queued for a follow-up — needs
  an FR32-padded Merkle root computation over the streamed bytes.
- **FilBeam egress accounting.** The doc shows `synapse.filbeam.getDataSetStats`
  for quota inspection; add a tiny helper if you need to surface remaining
  quota in-app.
- **USDFC / EIP-712 signing.** Only relevant for uploads or FilBeam metering,
  neither of which run in this library.

## Bitswap / libp2p-native retrieval (still v2, and now optional-er)

The FOC doc confirms the default and correct answer is HTTP. If you later need
to fetch from peers that expose only `transport-bitswap` (no gateway), the
`Retriever` interface lets you plug one in without touching `NetworkStore` or
`LocalStore`. Recommended options in preferred order: [Nabu](https://github.com/peergos/nabu)
(built on jvm-libp2p, ships kad-dht + Bitswap), then `iroh` via Rust FFI. Raw
`jvm-libp2p` alone doesn't ship kad-dht or Bitswap and isn't practical to build
on directly.

## Wiring into an Android project

`settings.gradle.kts`:
```kotlin
include(":foc-cache")
project(":foc-cache").projectDir = file("../foc-local-first-android/foc-cache")
```

`Application`:
```kotlin
val cache = FocCache(
    context,
    Config(
        cacheDir = File(cacheDir, "foc"),
        quotaBytes = 512L * 1024 * 1024,
        blockTtl = Duration.ofDays(30),
        maxParallelFetches = 3,
        hedgeDelay = Duration.ofMillis(200),
    )
)
```

`ViewModel`:
```kotlin
val bytes = cache.get(ref)
cache.stream(ref).collect { chunk -> … }
cache.exists(ref.pieceCid)
viewModelScope.launch { cache.fetch(ref) }
```

## Correspondence with logos-storage-nim

| Logos (nim)                                    | Here (Kotlin)                          |
|------------------------------------------------|-----------------------------------------|
| `NetworkStore.getBlock`                        | `NetworkStore.get`                      |
| `RepoStore` + LevelDB                          | `LocalStore` + filesystem + SQLite meta |
| `BlockExcEngine` (`/storage/blockexc/1.0.0`)   | `RemoteStore` (HTTP: SP `/piece`, FilBeam, SP `/ipfs`, discovered gateways) |
| `codexdht` (discv5) provider lookup            | `ProviderResolver` (Delegated Routing v1) |
| `Manifest` (0xCD01) + `StorageMerkleTree`      | PieceCID + optional UnixFS root         |
| `storage-quota`, `block-ttl`, `data-dir`       | `Config.quotaBytes`, `blockTtl`, `cacheDir` |
