# Changelog

## 0.4.4

- **Availability probes fan out to every provider (capped at 64), not `probeConcurrency`.**
  The active registry has ~50 PDP providers and a piece usually lives on only two. With a
  16-way cap, the serving provider often sat behind a full batch of dead hosts and the
  probe waited out its 8s timeout before even reaching it. An availability check is
  user-initiated, headers-only, and cancelled on the first 2xx, so it now starts one HEAD
  per provider (bounded at 64 for a hypothetical huge registry). Fetch resolution keeps the
  smaller `probeConcurrency` cap because it may need two hits and is followed by the body
  transfer anyway.

## 0.4.3

- **Provider resolution no longer probes the whole registry for every piece, twice.**
  The active PDP registry on Filecoin has dozens of providers, many stale or slow.
  `RemoteStore.probe` used to build candidates (which HEAD-probed every provider),
  then run a second GET race over the result, and `fetchTo` built candidates again —
  so one piece open could pay the full probe three times. The availability probe now
  stops at the **first** provider that serves the piece, the fetch reuses the resolved
  per-piece list, and a resolution is cached per `(chain, pieceCid)` for
  `pieceResolutionTtl` (5 minutes). `probeTimeout` now actually cancels the in-flight
  HEAD requests (the previous `withTimeoutOrNull` wrapped only `joinAll`, so the
  launched jobs and their blocking OkHttp calls kept running), and probes use their own
  short-timeout client instead of the 30s fetch client.
- **New `Config` fields:** `probeConcurrency` (default 16; probes are headers-only, so
  they can be much more parallel than body fetches), `pieceResolutionTtl`, and
  `negativePieceResolutionTtl` (30s, so an unavailable piece is retried quickly without
  making every retry tap pay for a full probe).
- **Fetch stops at two providers.** FOC stores at least two copies; once two providers
  answer, the probe returns and the hedged fetch race takes over. A piece with only one
  copy still resolves (the probe falls back to its timeout-bounded partial result).
- **Behaviour note:** `FocCache.warmupProviders` now pre-fills the registry list only
  (as before) — the per-piece probe still happens on first use, but it is now the
  first-provider fast path and is cached for the rest of the open.

## 0.4.2

- **`registryCacheTtl` default raised from 10 minutes to 7 days.** The active-PDP-provider
  listing on a network changes slowly; re-reading it every 10 minutes was bandwidth and latency
  spent on a nearly-static answer. The per-piece HEAD probe is unchanged — it still runs on
  every network operation, as it must to detect a provider gaining or losing the piece.
  Callers that want the registry to live for the process lifetime (a mobile app session) can
  pass a very large `registryCacheTtl` (e.g. `Duration.ofDays(365_000)` — `java.time.Duration`
  has no INFINITE) — the cache is in-memory, so it dies with the process.
- **New `FocCache.warmupProviders(chains)`.** Best-effort, no per-piece probes: pre-fills the
  on-chain SP-registry cache for the networks the caller passes — it is parameterised rather
  than warming every chain, since a deployment only ever reads one of them. Off the calling
  thread; never throws.

## 0.4.1

- **Coroutine-based provider HEAD-probes.** `FilecoinRegistry`'s parallel probe now uses
  `coroutineScope` + `Semaphore` + `withTimeoutOrNull` instead of raw `Thread`s, so it
  participates in structured concurrency and cancellation like the rest of the library.

## 0.4.0

- **Writer-independent provider resolution from Filecoin's on-chain registry.** A `PieceRef`
  with no explicit `providerServiceUrls` (the common case for third-party uploaders, who publish
  only a `piece` locator) now resolves its storage providers from the network's
  ServiceProviderRegistry via `eth_call` (`FilecoinRegistry`): enumerate active PDP providers,
  decode each `serviceURL` capability (hex UTF-8 on chain), then HEAD-probe
  `{serviceURL}/piece/{pieceCid}` and keep the ones that serve the piece. This is the Kotlin port
  of `@filoz/synapse-core`'s `chainResolver` + `findPieceOnProviders`, with no web3/viem. The
  registry read is cached per network (`Config.registryCacheTtl`); any chain/probe failure falls
  back to the other candidates rather than erroring. New `Config` fields:
  `enableChainProviderResolution` (default true), `registryCacheTtl`, `probeTimeout`. The ABI
  decoder (`FilecoinAbi`) is tested against real captured `getProvidersByProductType` bytes from
  both calibration and mainnet.

## 0.3.1

- **FilBeam URLs now carry the EIP-55 checksummed wallet address.** `PieceRef.filBeamUrl`
  previously lowercased the per-account subdomain; FilBeam validates that subdomain as an
  Ethereum address and rejects the lowercase form (`400 Invalid address`), which made every
  CDN candidate — fetch and `isAvailable` probe alike — fail for indexes that store owners
  lowercased. The checksum is now derived from the address (Keccak-256, no new dependency),
  so any input case produces the one host Beam accepts. Malformed addresses fail fast with
  `IllegalArgumentException` instead of producing an unroutable URL.

## 0.3.0

- **`isAvailable(ref)`**: remote availability check to gate expensive work (a wallet signature,
  a key-derivation call) on a piece still being served. A local hit answers `true` with no
  network; otherwise the same candidates a fetch would try are raced on response headers —
  first 2xx wins, losers cancelled, winner closed without reading its body. Nothing is
  downloaded, cached, or transformed, and a ref with no candidate endpoints answers `false`
  immediately. Deliberately a GET decided on headers, not HEAD or Range, so the win condition
  is exactly the fetch's. `Retriever.probe(ref)` is the new hook behind it; it defaults to a
  full `fetch()`, so custom retrievers keep working, and `RemoteStore` overrides it with the
  headers-only race.

## 0.2.0

- **Streaming to disk.** Pieces are downloaded straight into the cache's temp area and installed by
  rename. `stream()` now reads the local file in `chunkSize` chunks instead of loading the whole piece
  into memory first. `Retriever.fetchTo(ref, file)` is the new hook; it defaults to `fetch()`, so
  custom retrievers keep working, and `RemoteStore` overrides it to stream the HTTP body.
- **Hedged race decided on headers.** The first 2xx response wins and losing calls are cancelled
  before they download a body. If the winner fails mid-body, the race re-runs over the rest.
- **`file(ref)`** returns the piece as a local file, for media players and decoders.
- **`PieceTransform`**: optional, per call on `get`/`file`/`stream`/`fetch`. Runs once when a piece
  arrives; the output is what the cache stores (decrypt-once, decompress, transcode).
- **`Config.autoQuota`**: size the cache from free space (`AutoQuota`, ½ free + used, 0.5–16 GB by
  default). `FocCache.quotaBytes` reports the quota in force.
- **Fixes.** A piece larger than the whole quota is now served without being cached (it used to fail
  with `IllegalArgumentException`). Eviction checks first, so a piece that can't fit no longer evicts
  others for nothing. Interrupted downloads leave no stray files.
- **Behaviour change.** `fetchTimeout` is now a connect and read-inactivity timeout, not a total call
  timeout, since large bodies stream.
- **API.** Source-compatible: new parameters have defaults. Not binary-compatible with 0.1.0
  (the `get`, `stream` and `fetch` JVM signatures gained a parameter), so dependents must recompile.
