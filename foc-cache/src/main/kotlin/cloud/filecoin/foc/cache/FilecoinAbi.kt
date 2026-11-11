package cloud.filecoin.foc.cache

/**
 * Minimal read-only Solidity ABI decoder for the one contract call the reader needs:
 * `ServiceProviderRegistry.getProvidersByProductType(uint8,bool,uint256,uint256)`.
 *
 * Its return type is the nested tuple
 * `( (uint256 providerId, (address,address,string,string,bool) providerInfo,
 *     (uint8,string[],bool) product, bytes[] productCapabilityValues)[], bool hasMore )`,
 * which viem/the SDK would normally decode. The app has no web3/viem, so this walks
 * the standard head/tail encoding directly. It is deliberately not a general ABI
 * library: it understands exactly the types that appear in that return and nothing
 * else (static words, `string`, `bytes`, and dynamic arrays/tuples of those).
 *
 * Verified against live `eth_call` bytes from both Filecoin networks (calibration
 * chain 314159 and mainnet chain 314) — see `FilecoinAbiTest`.
 */
internal object FilecoinAbi {

    /** The function selector for `getProvidersByProductType(uint8,bool,uint256,uint256)`. */
    const val GET_PROVIDERS_BY_PRODUCT_TYPE_SELECTOR = "0x6ba44226"

    /** One decoded provider: the fields the retrieval path needs. */
    data class Provider(val id: Long, val serviceUrl: String, val name: String)

    /** One page of the paginated registry call. */
    data class Page(val providers: List<Provider>, val hasMore: Boolean)

    /** Thrown when the returned bytes are not the ABI shape we expect. */
    class MalformedReturnException(message: String) : RuntimeException(message)

    /**
     * Decode the raw `eth_call` return (the `0x`-prefixed hex data) into a page of
     * providers. Each provider's `serviceURL` capability is hex-encoded UTF-8 on
     * chain (e.g. `0x68747470733a2f2f…` → `https://…`); this decodes it.
     */
    fun decodeProvidersPage(rawHex: String): Page {
        return try {
            val bytes = decodeHex(rawHex)
            val reader = Reader(bytes)
            // The return is a single dynamic tuple, so word 0 is its byte offset.
            val tupleOff = reader.uint(0).toInt()
            val root = decodeTuple(reader, tupleOff, ROOT)
            @Suppress("UNCHECKED_CAST")
            val providers = (root["providers"] as List<*>).map {
                @Suppress("UNCHECKED_CAST")
                decodeProvider(it as Map<String, Any?>)
            }
            Page(providers, root["hasMore"] as Boolean)
        } catch (e: MalformedReturnException) {
            throw e
        } catch (e: Exception) {
            throw MalformedReturnException("could not decode provider page: ${e.message}")
        }
    }

    /** ABI-encode `getProvidersByProductType(productType, onlyActive, offset, limit)` calldata. */
    fun encodeGetProvidersByProductType(
        productType: Long,
        onlyActive: Boolean,
        offset: Long,
        limit: Long,
    ): String {
        val args = listOf(
            word(productType),
            word(if (onlyActive) 1L else 0L),
            word(offset),
            word(limit),
        )
        return GET_PROVIDERS_BY_PRODUCT_TYPE_SELECTOR + args.joinToString("")
    }

    // ------------------------------------------------------------------ internals

    private fun decodeProvider(raw: Map<String, Any?>): Provider {
        @Suppress("UNCHECKED_CAST")
        val info = raw["providerInfo"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val product = raw["product"] as Map<String, Any?>
        val keys = product["capabilityKeys"] as List<String>
        // `productCapabilityValues` is a sibling of `product` on the provider tuple,
        // parallel to `product.capabilityKeys`.
        @Suppress("UNCHECKED_CAST")
        val values = raw["productCapabilityValues"] as List<ByteArray>
        val capabilities = keys.zip(values).toMap()
        val serviceUrl = capabilities["serviceURL"]?.toString(Charsets.UTF_8).orEmpty()
        return Provider(
            id = (raw["providerId"] as Long),
            serviceUrl = serviceUrl,
            name = info["name"] as? String ?: "",
        )
    }

    private fun decodeHex(hex: String): ByteArray {
        val h = hex.removePrefix("0x")
        require(h.length % 2 == 0) { "odd-length hex string" }
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun word(value: Long): String {
        val hex = value.toString(16)
        return hex.padStart(64, '0')
    }

    private class Reader(private val bytes: ByteArray) {
        /** Read a 32-byte ABI word as an unsigned integer. Values here (offsets, counts, ids) fit in a Long. */
        fun uint(offset: Int): Long {
            require(offset >= 0 && offset + WORD <= bytes.size) {
                "read out of bounds: offset=$offset size=${bytes.size}"
            }
            var value = 0L
            // Skip leading zero bytes; the meaningful part is the low 8 bytes.
            for (i in (offset + WORD - 8) until (offset + WORD)) {
                value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            }
            return value
        }

        fun byteAt(offset: Int): Byte = bytes[offset]

        fun slice(offset: Int, length: Int): ByteArray =
            bytes.copyOfRange(offset, offset + length)

        /** Length-prefixed UTF-8 string at [offset] (offset points at the length word). */
        fun string(offset: Int): String {
            val length = uint(offset).toInt()
            val start = offset + WORD
            return String(bytes, start, length, Charsets.UTF_8)
        }
    }

    // Type descriptors for exactly the return tuple we decode.
    private sealed interface Ty {
        val dynamic: Boolean

        class Word(override val dynamic: Boolean = false) : Ty
        class Bool(override val dynamic: Boolean = false) : Ty
        class Str(override val dynamic: Boolean = true) : Ty
        class Bytes(override val dynamic: Boolean = true) : Ty
        class Array(val element: Ty, override val dynamic: Boolean = true) : Ty
        class Tuple(val fields: List<Pair<String, Ty>>, override val dynamic: Boolean) : Ty
    }

    private val WORD_TY = Ty.Word()
    private val BOOL_TY = Ty.Bool()
    private val ADDRESS_TY = Ty.Word()
    private val STRING_TY = Ty.Str()
    private val BYTES_TY = Ty.Bytes()
    private val STRING_ARRAY_TY = Ty.Array(STRING_TY)
    private val BYTES_ARRAY_TY = Ty.Array(BYTES_TY)

    private val PROVIDER_INFO_TY = Ty.Tuple(
        listOf(
            "serviceProvider" to ADDRESS_TY,
            "payee" to ADDRESS_TY,
            "name" to STRING_TY,
            "description" to STRING_TY,
            "isActive" to BOOL_TY,
        ),
        dynamic = true,
    )
    private val PRODUCT_TY = Ty.Tuple(
        listOf(
            "productType" to WORD_TY,
            "capabilityKeys" to STRING_ARRAY_TY,
            "isActive" to BOOL_TY,
        ),
        dynamic = true,
    )
    private val PROVIDER_TY = Ty.Tuple(
        listOf(
            "providerId" to WORD_TY,
            "providerInfo" to PROVIDER_INFO_TY,
            "product" to PRODUCT_TY,
            "productCapabilityValues" to BYTES_ARRAY_TY,
        ),
        dynamic = true,
    )
    private val ROOT = Ty.Tuple(
        listOf(
            "providers" to Ty.Array(PROVIDER_TY),
            "hasMore" to BOOL_TY,
        ),
        dynamic = true,
    )

    /** Byte size a value of [ty] occupies when encoded statically (head slot). */
    private fun staticSize(ty: Ty): Int = when (ty) {
        is Ty.Word, is Ty.Bool -> WORD
        is Ty.Tuple -> ty.fields.sumOf { staticSize(it.second) }
        is Ty.Str, is Ty.Bytes, is Ty.Array -> error("dynamic type has no static size: $ty")
    }

    private fun decodeValue(reader: Reader, offset: Int, ty: Ty): Any? = when (ty) {
        is Ty.Word -> reader.uint(offset)
        is Ty.Bool -> reader.uint(offset) != 0L
        is Ty.Str -> reader.string(offset)
        is Ty.Bytes -> {
            val length = reader.uint(offset).toInt()
            reader.slice(offset + WORD, length)
        }
        is Ty.Array -> {
            val count = reader.uint(offset).toInt()
            require(count in 0..MAX_ITEMS) { "array length out of range: $count" }
            val body = offset + WORD
            val out = ArrayList<Any?>(count)
            if (ty.element.dynamic) {
                for (i in 0 until count) {
                    val rel = reader.uint(body + i * WORD).toInt()
                    out += decodeValue(reader, body + rel, ty.element)
                }
            } else {
                val elementSize = staticSize(ty.element)
                for (i in 0 until count) {
                    out += decodeValue(reader, body + i * elementSize, ty.element)
                }
            }
            out
        }
        is Ty.Tuple -> decodeTuple(reader, offset, ty)
    }

    private fun decodeTuple(reader: Reader, offset: Int, ty: Ty.Tuple): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(ty.fields.size)
        // Head slots are byte-addressed: static fields advance by their byte size,
        // dynamic fields occupy one 32-byte offset word. Tail offsets are relative
        // to the start of this tuple.
        var slot = offset
        val deferred = ArrayList<Triple<String, Ty, Int>>()
        for ((name, fieldTy) in ty.fields) {
            if (fieldTy.dynamic) {
                val rel = reader.uint(slot).toInt()
                deferred += Triple(name, fieldTy, rel)
                slot += WORD
            } else {
                val size = staticSize(fieldTy)
                out[name] = decodeValue(reader, slot, fieldTy)
                slot += size
            }
        }
        for ((name, fieldTy, rel) in deferred) {
            require(rel in 0..MAX_OFFSET) { "offset out of range: $rel" }
            out[name] = decodeValue(reader, offset + rel, fieldTy)
        }
        return out
    }

    private const val WORD = 32
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_OFFSET = 1_000_000_000
}