package cloud.filecoin.foc.cache

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * [FilecoinRegistry] writer-independent resolution, driven by a mock Filecoin RPC
 * and a mock provider HTTP server.
 *
 * The RPC response is built by [PageBuilder], a tiny correct ABI encoder for the
 * one return shape the registry decodes; `PageBuilderTest` (via the round-trip
 * assertions here) pins it to [FilecoinAbi] and the real captured bytes.
 */
class FilecoinRegistryTest {

    private lateinit var rpc: MockWebServer
    private lateinit var provider: MockWebServer
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        rpc = MockWebServer().apply { start() }
        provider = MockWebServer().apply { start() }
        tmpDir = Files.createTempDirectory("foc-registry").toFile()
        FilecoinNetworks.rpcUrlForTest(FocChain.CALIBRATION, rpc.url("/").toString())
    }

    @After
    fun tearDown() {
        rpc.shutdown()
        provider.shutdown()
        tmpDir.deleteRecursively()
        FilecoinNetworks.clearForTest()
    }

    private fun registry() = FilecoinRegistry(
        Config(
            cacheDir = tmpDir,
            fetchTimeout = Duration.ofSeconds(3),
            maxParallelFetches = 2,
            enableChainProviderResolution = true,
            registryCacheTtl = Duration.ofMinutes(10),
            probeTimeout = Duration.ofSeconds(2),
        ),
        http = okhttp3.OkHttpClient(),
    )

    private fun rpcResult(hex: String): MockResponse =
        MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":"$hex"}""")

    @Test
    fun `decodes registry and probes the provider that serves the piece`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        provider.enqueue(MockResponse().setResponseCode(200))

        val providers = registry().findProviders(FocChain.CALIBRATION, "bafkzcibtest")
        assertEquals(1, providers.size)
        assertEquals(providerUrl, providers.single().serviceUrl)
    }

    @Test
    fun `returns empty when the RPC is unreachable`() = runTest {
        FilecoinNetworks.rpcUrlForTest(FocChain.CALIBRATION, "http://127.0.0.1:1/")
        assertTrue(registry().findProviders(FocChain.CALIBRATION, "bafkzcibtest").isEmpty())
    }

    @Test
    fun `returns empty when the provider 404s the piece`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        provider.enqueue(MockResponse().setResponseCode(404))
        assertTrue(registry().findProviders(FocChain.CALIBRATION, "bafkzcibtest").isEmpty())
    }

    @Test
    fun `returns empty when RPC reports an error`() = runTest {
        rpc.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom"}}"""),
        )
        assertTrue(registry().findProviders(FocChain.CALIBRATION, "bafkzcibtest").isEmpty())
    }

    @Test
    fun `findAnyProvider returns the first serving provider without waiting for slow ones`() = runTest {
        val fast = MockWebServer().apply { start() }
        val slowA = MockWebServer().apply { start() }
        val slowB = MockWebServer().apply { start() }
        try {
            val fastUrl = fast.url("/").toString().trimEnd('/')
            val slowAUrl = slowA.url("/").toString().trimEnd('/')
            val slowBUrl = slowB.url("/").toString().trimEnd('/')
            rpc.enqueue(
                rpcResult(
                    PageBuilder.page(
                        listOf(
                            PageBuilder.provider(1, fastUrl, "fast"),
                            PageBuilder.provider(2, slowAUrl, "slow-a"),
                            PageBuilder.provider(3, slowBUrl, "slow-b"),
                        ),
                    ),
                ),
            )
            fast.enqueue(MockResponse().setResponseCode(200))
            // Delay the response headers past the probe timeout.
            slowA.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(30, TimeUnit.SECONDS))
            slowB.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(30, TimeUnit.SECONDS))

            val started = System.nanoTime()
            val found = registry().findAnyProvider(FocChain.CALIBRATION, "bafkzcibtest")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(fastUrl, found?.serviceUrl)
            // probeTimeout is 2s in the test config; the slow providers must not hold it open.
            assertTrue("findAnyProvider took ${elapsedMs}ms", elapsedMs < 4_000)
        } finally {
            runCatching { fast.close() }
            runCatching { slowA.close() }
            runCatching { slowB.close() }
        }
    }

    @Test
    fun `findAnyProvider gives up at probeTimeout when nobody serves the piece`() = runTest {
        val hanging = MockWebServer().apply { start() }
        try {
            val url = hanging.url("/").toString().trimEnd('/')
            rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(1, url, "hanging")))))
            hanging.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(30, TimeUnit.SECONDS))

            val started = System.nanoTime()
            val found = registry().findAnyProvider(FocChain.CALIBRATION, "bafkzcibtest")
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertNull(found)
            assertTrue("probe took ${elapsedMs}ms", elapsedMs < 4_000)
        } finally {
            runCatching { hanging.close() }
        }
    }

    @Test
    fun `per-piece resolution is cached across calls`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        provider.enqueue(MockResponse().setResponseCode(200))

        val registry = registry()
        val first = registry.findProviders(FocChain.CALIBRATION, "bafkzcibtest")
        val second = registry.findProviders(FocChain.CALIBRATION, "bafkzcibtest")

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        // One HEAD for both calls: the fetch path must not repeat the probe.
        assertEquals(1, provider.requestCount)
        assertEquals(1, rpc.requestCount)
    }

    @Test
    fun `fetch resolution returns once every probe answered below the target`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(
            rpcResult(
                PageBuilder.page(
                    listOf(
                        PageBuilder.provider(1, providerUrl, "a"),
                        PageBuilder.provider(2, providerUrl, "b"),
                        PageBuilder.provider(3, providerUrl, "c"),
                    ),
                ),
            ),
        )
        repeat(3) { provider.enqueue(MockResponse().setResponseCode(404)) }

        val started = System.nanoTime()
        val found = registry().findProviders(FocChain.CALIBRATION, "bafkzcibtest")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(found.isEmpty())
        // All probes answered quickly; do not sit out the probe timeout.
        assertTrue("resolution took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `availability probe is cached for repeated availability checks`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        provider.enqueue(MockResponse().setResponseCode(200))

        val registry = registry()
        val first = registry.findAnyProvider(FocChain.CALIBRATION, "bafkzcibtest")
        val second = registry.findAnyProvider(FocChain.CALIBRATION, "bafkzcibtest")

        assertEquals(providerUrl, first?.serviceUrl)
        assertEquals(providerUrl, second?.serviceUrl)
        assertEquals(1, provider.requestCount)
        assertEquals(1, rpc.requestCount)
    }

    @Test
    fun `fetch upgrades a partial availability resolution to a full probe`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        // One HEAD for the availability probe, one for the fetch's fuller probe.
        repeat(2) { provider.enqueue(MockResponse().setResponseCode(200)) }

        val registry = registry()
        val any = registry.findAnyProvider(FocChain.CALIBRATION, "bafkzcibtest")
        val all = registry.findProviders(FocChain.CALIBRATION, "bafkzcibtest")

        assertEquals(providerUrl, any?.serviceUrl)
        assertEquals(listOf(providerUrl), all.map { it.serviceUrl })
        // The availability hit is intentionally partial, so the fetch probes again to
        // get a list it can hedge across; the registry read itself is still cached.
        assertEquals(2, provider.requestCount)
        assertEquals(1, rpc.requestCount)
    }

    @Test
    fun `remote probe uses the registry HEAD and does not run a GET race`() = runTest {
        val providerUrl = provider.url("/").toString().trimEnd('/')
        rpc.enqueue(rpcResult(PageBuilder.page(listOf(PageBuilder.provider(9, providerUrl, "mock")))))
        provider.enqueue(MockResponse().setResponseCode(200))

        val remote = RemoteStore(
            Config(
                cacheDir = tmpDir,
                fetchTimeout = Duration.ofSeconds(3),
                maxParallelFetches = 2,
                enableProviderLookup = false,
                enableChainProviderResolution = true,
                registryCacheTtl = Duration.ofMinutes(10),
                probeTimeout = Duration.ofSeconds(2),
            ),
        )
        val ref = PieceRef(
            pieceCid = "bafkzcibtest",
            size = 0,
            providerServiceUrls = emptyList(),
            chain = FocChain.CALIBRATION,
        )

        assertTrue(remote.probe(ref))
        // Exactly the HEAD from FilecoinRegistry: no second GET from hedgedRace.
        assertEquals(1, provider.requestCount)
        assertEquals("HEAD", provider.takeRequest().method)
    }
}

/**
 * Minimal, correct ABI encoder for a one-page `getProvidersByProductType` return,
 * used only by tests. Emits the same head/tail layout as the Solidity contract so
 * [FilecoinAbi] decodes it identically to the real captured chain bytes.
 */
internal object PageBuilder {
    private const val W = 32

    private fun word(v: Long): String = v.toString(16).padStart(2 * W, '0')

    private fun bytesWord(b: ByteArray): String {
        val padded = b.copyOf(((b.size + W - 1) / W) * W)
        return word(b.size.toLong()) + padded.joinToString("") { "%02x".format(it) }
    }

    /** Encode a dynamic `string` (length word + padded bytes). */
    private fun string(s: String): String = bytesWord(s.toByteArray(Charsets.UTF_8))

    /** Encode a dynamic `string[]`: length, offsets (rel to body), then each string. */
    private fun stringArray(items: List<String>): String {
        val encoded = items.map { string(it) }
        val body = StringBuilder()
        body.append(word(items.size.toLong()))
        var offset = W.toLong() * items.size
        val offsets = ArrayList<Long>()
        for (e in encoded) { offsets += offset; offset += e.length / 2 }
        for (o in offsets) body.append(word(o))
        for (e in encoded) body.append(e)
        return body.toString()
    }

    /** Encode a dynamic `bytes[]`: length, offsets (rel to body), then each bytes. */
    private fun bytesArray(items: List<ByteArray>): String {
        val encoded = items.map { bytesWord(it) }
        val body = StringBuilder()
        body.append(word(items.size.toLong()))
        var offset = W.toLong() * items.size
        val offsets = ArrayList<Long>()
        for (e in encoded) { offsets += offset; offset += e.length / 2 }
        for (o in offsets) body.append(word(o))
        for (e in encoded) body.append(e)
        return body.toString()
    }

    /** One provider tuple with the given id, name, and serviceURL. */
    fun provider(id: Long, serviceUrl: String, name: String): String {
        // providerInfo = (address,address,string,string,bool) — dynamic tuple.
        val infoHeadLen = W.toLong() * 5
        val nameOff = infoHeadLen
        val nameEnc = string(name)
        val descOff = nameOff + nameEnc.length / 2
        val descEnc = string("")
        val info = buildString {
            append(word(0)) // serviceProvider
            append(word(0)) // payee
            append(word(nameOff))
            append(word(descOff))
            append(word(1)) // isActive
            append(nameEnc)
            append(descEnc)
        }

        // product = (uint8, string[], bool) — dynamic tuple.
        val productHeadLen = W.toLong() * 3
        val keysEnc = stringArray(listOf("serviceURL"))
        val product = buildString {
            append(word(0)) // productType = PDP
            append(word(productHeadLen)) // capabilityKeys offset
            append(word(1)) // isActive
            append(keysEnc)
        }

        // capabilityValues = bytes[] with the serviceURL bytes.
        val capEnc = bytesArray(listOf(serviceUrl.toByteArray(Charsets.UTF_8)))

        // provider = (uint256, providerInfo, product, bytes[]) — dynamic tuple.
        val providerHeadLen = W.toLong() * 4
        val piOff = providerHeadLen
        val prodOff = piOff + info.length / 2
        val capOff = prodOff + product.length / 2
        return buildString {
            append(word(id))
            append(word(piOff))
            append(word(prodOff))
            append(word(capOff))
            append(info)
            append(product)
            append(capEnc)
        }
    }

    /** One page: `((providerId,…)[], bool hasMore)`. */
    fun page(providers: List<String>, hasMore: Boolean = false): String {
        val provEnc = providers.map { it }.joinToString("")
        val providersArray = buildString {
            append(word(providers.size.toLong()))
            var offset = W.toLong() * providers.size
            val offsets = ArrayList<Long>()
            for (p in providers) { offsets += offset; offset += p.length / 2 }
            for (o in offsets) append(word(o))
            append(provEnc)
        }
        val rootHeadLen = W.toLong() * 2
        val root = buildString {
            append(word(rootHeadLen)) // providers offset
            append(word(if (hasMore) 1L else 0L))
            append(providersArray)
        }
        return "0x" + word(W.toLong()) + root // word0 = root offset
    }
}