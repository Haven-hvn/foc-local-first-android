package cloud.filecoin.foc.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM: no Android, no filesystem. */
class AutoQuotaTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    @Test
    fun `half of free plus what the cache already holds`() {
        assertEquals(2 * gb + 3 * gb, AutoQuota().resolve(usedBytes = 2 * gb, freeBytes = 6 * gb))
    }

    @Test
    fun `clamped to min and max`() {
        assertEquals(512 * mb, AutoQuota().resolve(usedBytes = 0, freeBytes = 100 * mb))
        assertEquals(16 * gb, AutoQuota().resolve(usedBytes = 10 * gb, freeBytes = 100 * gb))
    }

    @Test
    fun `custom fraction and bounds`() {
        val q = AutoQuota(fractionOfFree = 0.25, minBytes = 1 * mb, maxBytes = 1 * gb)
        assertEquals(100 * mb, q.resolve(usedBytes = 0, freeBytes = 400 * mb))
    }

    @Test
    fun `negative free space is treated as none`() {
        assertEquals(512 * mb, AutoQuota().resolve(usedBytes = 0, freeBytes = -1))
    }

    @Test
    fun `rejects nonsense`() {
        assertThrows(IllegalArgumentException::class.java) { AutoQuota(fractionOfFree = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { AutoQuota(fractionOfFree = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { AutoQuota(minBytes = 10, maxBytes = 5) }
    }
}
