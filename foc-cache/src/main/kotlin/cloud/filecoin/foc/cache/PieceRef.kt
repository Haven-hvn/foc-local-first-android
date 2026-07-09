package cloud.filecoin.foc.cache

import kotlinx.serialization.Serializable

/**
 * A stable handle for a piece stored on Filecoin Onchain Cloud, produced by an
 * upload via `synapse-sdk`.
 *
 * Mirrors the three retrieval paths documented in the FOC Retrieval page:
 *   1. Direct SP `/piece` retrieval (default, no egress charge)
 *   2. FilBeam CDN `/piece` retrieval (opt-in via `withCDN`, metered egress)
 *   3. IPFS `/ipfs` retrieval (opt-in via `withIPFSIndexing`)
 *
 * @property pieceCid            Filecoin PieceCID v2 string (e.g. `bafkzcib...`).
 * @property size                Raw (unpadded) piece size in bytes.
 * @property providerServiceUrls SP Curio HTTP endpoints returned by `synapse-sdk`'s
 *                               upload result. Consumed by the Direct SP `/piece`
 *                               path. In FOC, uploads always land on ≥2 SPs
 *                               (pricing minimum), so this list normally has ≥2
 *                               entries and the hedged runner races them.
 * @property walletAddress       Owning wallet address (`0x...`). Required to
 *                               construct FilBeam URLs when [cdnEnabled] is true.
 *                               This is the "per-account subdomain keyed by the
 *                               wallet address that added the piece" from the doc.
 * @property cdnEnabled          True iff the data set was uploaded with `withCDN`.
 *                               When true and [walletAddress] is present, the
 *                               library races a FilBeam URL alongside SP probes.
 *                               When false, FilBeam is never contacted, matching
 *                               `synapse.download` behavior.
 * @property chain               Which chain the data set was created on
 *                               ([FocChain.MAINNET] or [FocChain.CALIBRATION]).
 *                               Selects the FilBeam host domain.
 * @property ipfsIndexed         True iff the data set was uploaded with
 *                               `withIPFSIndexing`. When true and [unixFsRoot]
 *                               is present, the IPFS `/ipfs` path (trustless
 *                               gateway on SPs, discovered gateways via IPNI)
 *                               is available. Otherwise IPFS retrieval is not
 *                               attempted — matches the doc's "opt-in per data set".
 * @property unixFsRoot          UnixFS root CID for the same content. Populated
 *                               when the SP indexed the piece; the doc notes SPs
 *                               with `IPNI_IPFS=true` announce this to IPNI.
 * @property trustlessGateways   Optional explicit Trustless Gateway origins to
 *                               try before falling back to IPNI-discovered ones.
 */
@Serializable
data class PieceRef(
    val pieceCid: String,
    val size: Long,
    val providerServiceUrls: List<String>,
    val walletAddress: String? = null,
    val cdnEnabled: Boolean = false,
    val chain: FocChain = FocChain.MAINNET,
    val ipfsIndexed: Boolean = false,
    val unixFsRoot: String? = null,
    val trustlessGateways: List<String> = DEFAULT_GATEWAYS,
) {
    init {
        require(pieceCid.isNotBlank()) { "pieceCid must not be blank" }
        require(size >= 0) { "size must be non-negative" }
        if (cdnEnabled) {
            require(!walletAddress.isNullOrBlank()) {
                "cdnEnabled requires walletAddress to build the FilBeam per-account subdomain"
            }
        }
    }

    /** Full FilBeam URL for this piece, or null if CDN is disabled or wallet address missing. */
    val filBeamUrl: String?
        get() {
            if (!cdnEnabled) return null
            val addr = walletAddress?.lowercase() ?: return null
            val host = when (chain) {
                FocChain.MAINNET -> "${addr}.filbeam.io"
                FocChain.CALIBRATION -> "${addr}.calibration.filbeam.io"
            }
            return "https://$host/$pieceCid"
        }

    companion object {
        val DEFAULT_GATEWAYS: List<String> = listOf(
            "https://trustless-gateway.link",
            "https://ipfs.io",
        )
    }
}

/** The chain a data set was created on. Selects the FilBeam host. */
@Serializable
enum class FocChain { MAINNET, CALIBRATION }
