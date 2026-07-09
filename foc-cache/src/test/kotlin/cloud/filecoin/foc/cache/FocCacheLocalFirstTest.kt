package cloud.filecoin.foc.cache

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * Smoke tests for the local-first behavior in [NetworkStore] paired with [LocalStore],
 * and for the hedged-race behavior in [RemoteStore].
 *
 * NOTE: These tests exercise Android's SQLite APIs, so they run as instrumented
 * tests or under Robolectric. Marked @Ignore here because the project skeleton
 * in this repository doesn't include an emulator; convert to androidTest or add
 * Robolectric when wiring this into a real Android project. The tests document,
 * in executable form, exactly what "local-first" and "multi-provider hedged
 * fetch" mean for this library.
 */
@Ignore("Requires Android SQLite — run under androidTest or Robolectric.")
class FocCacheLocalFirstTest {

    private lateinit var mockSpA: MockWebServer
    private lateinit var mockSpB: MockWebServer
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        mockSpA = MockWebServer().apply { start() }
        mockSpB = MockWebServer().apply { start() }
        tmpDir = Files.createTempDirectory("foc-test").toFile()
    }

    @After
    fun tearDown() {
        mockSpA.shutdown()
        mockSpB.shutdown()
        tmpDir.deleteRecursively()
    }

    private fun makeCache(): FocCache {
        val context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        return FocCache(
            context,
            Config(
                cacheDir = tmpDir,
                quotaBytes = 1L shl 20,
                blockTtl = Duration.ofDays(1),
                chunkSize = 32,
                fetchTimeout = Duration.ofSeconds(5),
                maxParallelFetches = 2,
                hedgeDelay = Duration.ofMillis(50),
                enableProviderLookup = false, // don't hit the real routing endpoint from tests
            ),
        )
    }

    @Test
    fun `miss then hit — second read is served from local cache`() = runTest {
        val payload = "hello filecoin".toByteArray()
        // Enqueue ONE 200 on SP-A only. Second read must not hit the network.
        mockSpA.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().apply { write(payload) })
        )

        val ref = PieceRef(
            pieceCid = "bafkzcibfaketestpiece",
            size = payload.size.toLong(),
            providerServiceUrls = listOf(mockSpA.url("/").toString().trimEnd('/')),
        )
        val cache = makeCache()

        val first = cache.get(ref)
        assertArrayEquals(payload, first)

        val second = cache.get(ref)
        assertArrayEquals(payload, second)

        assertEquals(1, mockSpA.requestCount) // no second network hit
        assertTrue(cache.exists(ref.pieceCid))
    }

    @Test
    fun `hedged race — slow primary is beaten by fast secondary`() = runTest {
        val payload = "won by secondary".toByteArray()

        // SP-A is slow (500 ms delay). SP-B is fast (immediate 200).
        mockSpA.enqueue(
            MockResponse()
                .setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody(Buffer().apply { write(payload) })
        )
        mockSpB.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().apply { write(payload) })
        )

        val ref = PieceRef(
            pieceCid = "bafkzcibhedgedracetest",
            size = payload.size.toLong(),
            providerServiceUrls = listOf(
                mockSpA.url("/").toString().trimEnd('/'),
                mockSpB.url("/").toString().trimEnd('/'),
            ),
        )
        val cache = makeCache()

        val started = System.nanoTime()
        val got = cache.get(ref)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertArrayEquals(payload, got)
        // The fast provider must have won within its own latency + the 50 ms
        // hedge delay + slack, well below the 500 ms slow-primary latency.
        assertTrue(
            "Hedge failed: elapsed=${elapsedMs}ms exceeded slow-primary window",
            elapsedMs < 400,
        )
    }

    @Test
    fun `all providers fail — throws PieceUnavailableException with diagnostics`() = runTest {
        mockSpA.enqueue(MockResponse().setResponseCode(500))
        mockSpB.enqueue(MockResponse().setResponseCode(404))

        val ref = PieceRef(
            pieceCid = "bafkzcibnobodyhasit",
            size = 0,
            providerServiceUrls = listOf(
                mockSpA.url("/").toString().trimEnd('/'),
                mockSpB.url("/").toString().trimEnd('/'),
            ),
        )
        val cache = makeCache()

        val err = runCatching { cache.get(ref) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is PieceUnavailableException)
        val msg = err.message ?: ""
        assertTrue(msg.contains("non-2xx"))
        assertTrue(msg.contains(ref.pieceCid))
    }
}
