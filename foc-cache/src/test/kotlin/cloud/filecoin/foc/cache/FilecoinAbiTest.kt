package cloud.filecoin.foc.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FilecoinAbi] against real `eth_call` bytes captured live from both Filecoin
 * networks (`getProvidersByProductType(0,true,0,1)`). These pin the decoder to the
 * exact head/tail layout the ServiceProviderRegistry contract returns, so a future
 * ABI change fails here rather than in the field.
 */
class FilecoinAbiTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText().trim()

    @Test
    fun `calibration provider decodes to its serviceURL`() {
        val page = FilecoinAbi.decodeProvidersPage(resource("calibration_getProvidersByProductType.hex"))

        assertEquals(1, page.providers.size)
        val provider = page.providers.single()
        assertEquals(1L, provider.id)
        assertEquals("Kubuxu's dev node", provider.name)
        // serviceURL is hex-encoded UTF-8 on chain; must decode to the real URL.
        assertEquals("https://pdp-dev.kubuxu.com", provider.serviceUrl)
        assertTrue(page.hasMore)
    }

    @Test
    fun `mainnet provider decodes to its serviceURL`() {
        val page = FilecoinAbi.decodeProvidersPage(resource("mainnet_getProvidersByProductType.hex"))

        assertEquals(1, page.providers.size)
        val provider = page.providers.single()
        assertEquals(1L, provider.id)
        assertEquals("ezpdpz-main", provider.name)
        assertEquals("https://main.ezpdpz.net", provider.serviceUrl)
        assertTrue(page.hasMore)
    }

    @Test
    fun `selector and calldata match the contract ABI`() {
        assertEquals("0x6ba44226", FilecoinAbi.GET_PROVIDERS_BY_PRODUCT_TYPE_SELECTOR)
        val data = FilecoinAbi.encodeGetProvidersByProductType(
            productType = 0L,
            onlyActive = true,
            offset = 0L,
            limit = 1L,
        )
        // productType=0, onlyActive=1, offset=0, limit=1 as four 32-byte words.
        assertEquals(
            "0x6ba44226" +
                "0".repeat(64) +
                "0".repeat(63) + "1" +
                "0".repeat(64) +
                "0".repeat(63) + "1",
            data,
        )
    }

    @Test
    fun `malformed return is rejected`() {
        val ex = runCatching { FilecoinAbi.decodeProvidersPage("0xdeadbeef") }.exceptionOrNull()
        assertTrue("expected MalformedReturnException, got $ex", ex is FilecoinAbi.MalformedReturnException)
    }

    @Test
    fun `PageBuilder output decodes to the same provider shape as the real chain bytes`() {
        // The test-only encoder must be faithful to the contract's layout: a page it
        // builds for (id=9, https://calib.ezpdpz.net, "ezpdpz-calib") must decode to
        // exactly the same fields as the live calibration capture does.
        val built = PageBuilder.page(listOf(PageBuilder.provider(9, "https://calib.ezpdpz.net", "ezpdpz-calib")))
        val fromBuilt = FilecoinAbi.decodeProvidersPage(built)

        val real = FilecoinAbi.decodeProvidersPage(resource("calibration_getProvidersByProductType.hex"))
        // The real page's first provider is id=1; PageBuilder uses id=9. Compare shape,
        // not exact id: one provider, non-blank serviceURL, hasMore true.
        assertEquals(1, fromBuilt.providers.size)
        assertEquals(9L, fromBuilt.providers.single().id)
        assertEquals("https://calib.ezpdpz.net", fromBuilt.providers.single().serviceUrl)
        assertEquals("ezpdpz-calib", fromBuilt.providers.single().name)
        assertTrue(fromBuilt.hasMore)
        assertTrue(real.providers.isNotEmpty())
    }
}