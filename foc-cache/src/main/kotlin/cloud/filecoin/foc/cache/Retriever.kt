package cloud.filecoin.foc.cache

/**
 * Plug point for the "network on miss" side.
 *
 * The default implementation ([RemoteStore]) does everything over HTTP: SP
 * PDPServer piece endpoints, Trustless Gateways, and gateways discovered via
 * IPFS Delegated Routing v1 (Amino DHT + IPNI). That path already covers
 * every provider that advertises `transport-ipfs-gateway-http` — which is
 * essentially all Curio SPs (with `IPNI_IPFS=true`) and every public gateway.
 *
 * A libp2p-native retriever (Bitswap) is only necessary if you want to fetch
 * from peers that expose *only* `transport-bitswap` — e.g. someone else's
 * desktop client with no gateway in front. In that case, wire in a
 * Bitswap-backed [Retriever] via:
 *
 *   * **Preferred:** [Nabu](https://github.com/peergos/nabu), a minimal Java
 *     IPFS built on `jvm-libp2p` — it bundles kad-dht and Bitswap, both of
 *     which `jvm-libp2p` deliberately does *not* ship (see the status table
 *     in `jvm-libp2p-develop/README.md`).
 *   * **Not recommended:** implementing kad-dht and Bitswap directly on top
 *     of raw `jvm-libp2p`. Those are two large protocol implementations and
 *     the maintenance surface is not worth it for a mobile client.
 *
 * The [FocCache] constructor accepts a custom [Retriever] so the local-first
 * caching layer stays identical while you swap the transport underneath. This
 * is the direct analog of `logos-storage-nim`'s `NetworkStore(engine, localStore)`
 * being generic in the block-exchange engine.
 */
interface Retriever {
    /**
     * Return the piece bytes or throw [PieceUnavailableException]. Called only
     * on a local cache miss; not called if the local store already has the
     * bytes.
     */
    suspend fun fetch(ref: PieceRef): ByteArray
}
