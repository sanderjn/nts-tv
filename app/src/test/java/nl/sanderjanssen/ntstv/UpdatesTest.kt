package nl.sanderjanssen.ntstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun releaseTagWithoutTheV() {
        assertEquals("1.2.0", Updates.parseVersion("""{"tag_name":"v1.2.0","name":"1.2.0"}"""))
    }

    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(Updates.isNewer("1.2.0", than = "1.1.0"))
        assertTrue(Updates.isNewer("1.10.0", than = "1.9.0"))
        assertFalse(Updates.isNewer("1.2", than = "1.2.0"))
        assertFalse(Updates.isNewer("1.0.0", than = "1.1.0"))
    }
}
