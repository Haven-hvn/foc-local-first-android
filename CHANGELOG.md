# Changelog

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
