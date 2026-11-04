package cloud.filecoin.foc.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * FilBeam subdomain spelling: Beam rejects lowercase addresses, so
 * [PieceRef.filBeamUrl] must carry the EIP-55 checksum regardless of the
 * case the caller stored. Vectors are the canonical EIP-55 examples plus
 * the calibration uploader address observed live (lowercase → 400,
 * checksummed → 200).
 */
class PieceRefBeamUrlTest {

    private fun ref(wallet: String) = PieceRef(
        pieceCid = "bafkzcibpiece",
        size = 0,
        providerServiceUrls = emptyList(),
        walletAddress = wallet,
        cdnEnabled = true,
        chain = FocChain.CALIBRATION,
    )

    @Test
    fun `lowercase wallet is checksummed in the beam host`() {
        assertEquals(
            "https://0x5C32469325d4093aB142DDdC1305F91d76e45141.calibration.filbeam.io/bafkzcibpiece",
            ref("0x5c32469325d4093ab142dddc1305f91d76e45141").filBeamUrl,
        )
    }

    @Test
    fun `checksummed wallet passes through unchanged`() {
        assertEquals(
            "https://0x5C32469325d4093aB142DDdC1305F91d76e45141.calibration.filbeam.io/bafkzcibpiece",
            ref("0x5C32469325d4093aB142DDdC1305F91d76e45141").filBeamUrl,
        )
    }

    @Test
    fun `uppercase wallet is checksummed in the beam host`() {
        assertEquals(
            "https://0x5C32469325d4093aB142DDdC1305F91d76e45141.calibration.filbeam.io/bafkzcibpiece",
            ref("0x5C32469325D4093AB142DDDC1305F91D76E45141").filBeamUrl,
        )
    }

    @Test
    fun `eip-55 reference vectors`() {
        assertEquals(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            toChecksumAddress("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        )
        assertEquals(
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            toChecksumAddress("0xfb6916095ca1df60bb79ce92ce3ea74c37c5d359"),
        )
        assertEquals(
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            toChecksumAddress("0xdbf03b407c01e7cd3cbea99509d93f8dddc8c6fb"),
        )
    }

    @Test
    fun `keccak-256 empty vector`() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Keccak256.hashHex(ByteArray(0)),
        )
    }

    @Test
    fun `malformed wallet is rejected, never lowercased into a host`() {
        assertThrows(IllegalArgumentException::class.java) { ref("0x123").filBeamUrl }
        assertThrows(IllegalArgumentException::class.java) { ref("not-an-address").filBeamUrl }
    }
}
