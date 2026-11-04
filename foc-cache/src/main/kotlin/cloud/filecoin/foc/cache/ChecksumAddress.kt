package cloud.filecoin.foc.cache

/**
 * EIP-55 checksummed Ethereum addresses.
 *
 * FilBeam keys its per-account subdomain by wallet address and validates it
 * as an Ethereum address — the checksummed spelling is the only one it
 * accepts (an all-lowercase subdomain is rejected `400 Invalid address`).
 * Callers hand us addresses in whatever case their index stored (Arkiv
 * owners arrive lowercased), so the case must be *derived* here, never
 * inherited: lowercase what we were given, then re-checksum it.
 */
internal fun toChecksumAddress(address: String): String {
    val hex = address.lowercase().removePrefix("0x")
    require(hex.length == 40 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
        "walletAddress must be a 0x-prefixed 20-byte hex address, got $address"
    }
    val hashHex = Keccak256.hashHex(hex.toByteArray(Charsets.US_ASCII))
    val out = StringBuilder("0x")
    for (i in hex.indices) {
        val c = hex[i]
        out.append(if (c in 'a'..'f' && hashHex[i].digitToInt(16) >= 8) c.uppercaseChar() else c)
    }
    return out.toString()
}

/**
 * Keccak-256 (pre-NIST padding), hand-rolled: the platform offers SHA-256
 * but no Keccak, and one hash does not justify a crypto dependency. Pinned
 * against the standard vectors in tests.
 */
internal object Keccak256 {

    /** Rate for Keccak-256: 1088 bits. */
    private const val RATE_BYTES = 136

    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001u.toLong(),
        0x0000000000008082u.toLong(),
        0x800000000000808Au.toLong(),
        0x8000000080008000u.toLong(),
        0x000000000000808Bu.toLong(),
        0x0000000080000001u.toLong(),
        0x8000000080008081u.toLong(),
        0x8000000000008009u.toLong(),
        0x000000000000008Au.toLong(),
        0x0000000000000088u.toLong(),
        0x0000000080008009u.toLong(),
        0x000000008000000Au.toLong(),
        0x000000008000808Bu.toLong(),
        0x800000000000008Bu.toLong(),
        0x8000000000008089u.toLong(),
        0x8000000000008003u.toLong(),
        0x8000000000008002u.toLong(),
        0x8000000000000080u.toLong(),
        0x000000000000800Au.toLong(),
        0x800000008000000Au.toLong(),
        0x8000000080008081u.toLong(),
        0x8000000000008080u.toLong(),
        0x0000000080000001u.toLong(),
        0x8000000080008008u.toLong(),
    )

    private val ROTATION_OFFSETS = arrayOf(
        intArrayOf(0, 36, 3, 41, 18),
        intArrayOf(1, 44, 10, 45, 2),
        intArrayOf(62, 6, 43, 15, 61),
        intArrayOf(28, 55, 25, 21, 56),
        intArrayOf(27, 20, 39, 8, 14),
    )

    fun hash(data: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        val padded = pad(data)
        while (offset < padded.size) {
            for (i in 0 until RATE_BYTES / 8) {
                state[i] = state[i] xor readLongLE(padded, offset + i * 8)
            }
            keccakF(state)
            offset += RATE_BYTES
        }
        val out = ByteArray(32)
        for (i in 0 until 4) writeLongLE(out, i * 8, state[i])
        return out
    }

    fun hashHex(data: ByteArray): String =
        hash(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun pad(data: ByteArray): ByteArray {
        // pad10*1 with Keccak suffix 0x01: one 0x01 byte, zeros, final byte OR 0x80.
        val suffixLen = RATE_BYTES - (data.size % RATE_BYTES)
        val out = data.copyOf(data.size + suffixLen)
        out[data.size] = 0x01
        out[out.size - 1] = (out[out.size - 1].toInt() or 0x80).toByte()
        return out
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun writeLongLE(bytes: ByteArray, offset: Int, v: Long) {
        for (i in 0 until 8) bytes[offset + i] = (v ushr (i * 8)).toByte()
    }

    private fun keccakF(a: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)
        repeat(24) { round ->
            for (x in 0 until 5) {
                c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            }
            for (x in 0 until 5) {
                d[x] = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d[x]
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(ROTATION_OFFSETS[x][y])
                }
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    a[x + 5 * y] = b[x + 5 * y] xor ((b[(x + 1) % 5 + 5 * y].inv()) and b[(x + 2) % 5 + 5 * y])
                }
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
