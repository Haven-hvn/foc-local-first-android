package cloud.filecoin.foc.cache

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * Transform-on-store, file access, streaming from disk and quota behaviour, against a fake
 * [Retriever] (no network, no loopback server).
 *
 * Like [FocCacheLocalFirstTest] these need Android's SQLite, so they're ignored until the module
 * runs androidTest or Robolectric.
 */
@Ignore("Requires Android SQLite — run under androidTest or Robolectric.")
class FocCacheTransformTest {

    private lateinit var tmpDir: File

    /** Serves fixed bytes per pieceCid and counts calls. Overrides only fetch(), so the default fetchTo is exercised. */
    private class FakeRetriever(private val pieces: Map<String, ByteArray>) : Retriever {
        var calls = 0
        override suspend fun fetch(ref: PieceRef): ByteArray {
            calls++
            return pieces[ref.pieceCid] ?: throw PieceUnavailableException("no ${ref.pieceCid}")
        }
    }

    /** XOR "cipher": stands in for decrypt-once. */
    private val xor = PieceTransform { _, input, output ->
        input.readBytes().forEach { output.write(it.toInt() xor 0x5A) }
    }

    @Before fun setUp() { tmpDir = Files.createTempDirectory("foc-transform").toFile() }
    @After fun tearDown() { tmpDir.deleteRecursively() }

    private fun cache(retriever: Retriever, quota: Long = 1L shl 20) = FocCache(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        Config(cacheDir = tmpDir, quotaBytes = quota, blockTtl = Duration.ZERO, chunkSize = 4, enableProviderLookup = false),
        retriever,
    )

    private fun ref(cid: String) = PieceRef(pieceCid = cid, size = 0, providerServiceUrls = emptyList())

    private fun ByteArray.xored() = ByteArray(size) { (this[it].toInt() xor 0x5A).toByte() }

    @Test
    fun `transform runs once and its output is what every later read returns`() = runTest {
        val raw = "sealed bytes".toByteArray()
        val remote = FakeRetriever(mapOf("a" to raw))
        val c = cache(remote)

        assertArrayEquals(raw.xored(), c.get(ref("a"), xor))
        // Later reads — with or without the transform — hit the stored, transformed bytes.
        assertArrayEquals(raw.xored(), c.get(ref("a")))
        assertArrayEquals(raw.xored(), c.file(ref("a")).readBytes())
        assertEquals(1, remote.calls)
    }

    @Test
    fun `a failing transform stores nothing and leaves no temp files`() = runTest {
        val c = cache(FakeRetriever(mapOf("a" to ByteArray(8))))
        val boom = PieceTransform { _, _, _ -> error("wrong key") }

        assertTrue(runCatching { c.get(ref("a"), boom) }.isFailure)
        assertFalse(c.exists("a"))
        assertEquals(0, File(tmpDir, "tmp").listFiles()!!.size)
    }

    @Test
    fun `stream reads from disk in chunkSize pieces`() = runTest {
        val raw = ByteArray(10) { it.toByte() }
        val chunks = cache(FakeRetriever(mapOf("a" to raw))).stream(ref("a")).toList()
        assertEquals(listOf(4, 4, 2), chunks.map { it.size })
        assertArrayEquals(raw, chunks.reduce { acc, b -> acc + b })
    }

    @Test
    fun `a piece bigger than the quota is still served but not kept`() = runTest {
        val remote = FakeRetriever(mapOf("big" to ByteArray(64)))
        val c = cache(remote, quota = 32)

        assertEquals(64, c.get(ref("big")).size)
        assertFalse(c.exists("big"))
        assertThrows(QuotaExceededException::class.java) { kotlinx.coroutines.runBlocking { c.file(ref("big")) } }
        assertEquals(0, File(tmpDir, "tmp").listFiles()!!.size)
    }

    @Test
    fun `eviction is least recently used and a failed fit evicts nothing`() = runTest {
        val c = cache(FakeRetriever(mapOf("a" to ByteArray(10), "b" to ByteArray(10), "c" to ByteArray(10))), quota = 25)
        c.fetch(ref("a"))
        c.fetch(ref("b"))
        c.file(ref("a"))              // a is now more recent than b
        c.fetch(ref("c"))             // needs 5 more bytes → evicts b only
        assertTrue(c.exists("a"))
        assertFalse(c.exists("b"))
        assertTrue(c.exists("c"))
    }

    @Test
    fun `autoQuota replaces the fixed quota`() {
        val c = FocCache(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            Config(cacheDir = tmpDir, quotaBytes = 1, autoQuota = AutoQuota(minBytes = 1_000, maxBytes = 2_000), enableProviderLookup = false),
            FakeRetriever(emptyMap()),
        )
        assertTrue(c.quotaBytes in 1_000..2_000)
    }
}
