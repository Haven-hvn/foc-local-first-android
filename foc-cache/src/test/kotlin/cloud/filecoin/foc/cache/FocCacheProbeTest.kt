package cloud.filecoin.foc.cache

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * Availability probe: headers-only, against [RemoteStore] directly (no Android
 * SQLite involved), plus the [Retriever.probe] default custom retrievers inherit.
 */
class FocCacheProbeTest {

    private lateinit var mockSp: MockWebServer
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        mockSp = MockWebServer().apply { start() }
        tmpDir = Files.createTempDirectory("foc-probe").toFile()
    }

    @After
    fun tearDown() {
        mockSp.shutdown()
        tmpDir.deleteRecursively()
    }

    private fun remote() = RemoteStore(
        Config(
            cacheDir = tmpDir,
            chunkSize = 32,
            fetchTimeout = Duration.ofSeconds(5),
            maxParallelFetches = 2,
            hedgeDelay = Duration.ofMillis(10),
            enableProviderLookup = false,
        ),
    )

    private fun ref(cid: String, urls: List<String>) =
        PieceRef(pieceCid = cid, size = 0, providerServiceUrls = urls)

    private fun spUrl() = mockSp.url("/").toString().trimEnd('/')

    @Test
    fun `probe is true when a candidate answers 2xx`() = runTest {
        mockSp.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().apply { write("payload".toByteArray()) }),
        )

        assertTrue(remote().probe(ref("bafkzcibprobehit", listOf(spUrl()))))
        assertEquals(1, mockSp.requestCount)
    }

    @Test
    fun `probe is false when every candidate answers non-2xx`() = runTest {
        mockSp.enqueue(MockResponse().setResponseCode(404))

        assertFalse(remote().probe(ref("bafkzcibprobemiss", listOf(spUrl()))))
        assertEquals(1, mockSp.requestCount)
    }

    @Test
    fun `probe is false with no candidate endpoints and makes no request`() = runTest {
        assertFalse(remote().probe(ref("bafkzcibprobeempty", emptyList())))
        assertEquals(0, mockSp.requestCount)
    }

    @Test
    fun `default probe follows fetch for custom retrievers`() = runTest {
        val serving = object : Retriever {
            override suspend fun fetch(ref: PieceRef): ByteArray = byteArrayOf(1)
        }
        val gone = object : Retriever {
            override suspend fun fetch(ref: PieceRef): ByteArray =
                throw PieceUnavailableException("gone")
        }

        assertTrue(serving.probe(ref("a", emptyList())))
        assertFalse(gone.probe(ref("a", emptyList())))
    }
}
