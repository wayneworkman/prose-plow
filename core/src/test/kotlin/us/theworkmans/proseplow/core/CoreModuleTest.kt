package us.theworkmans.proseplow.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreModuleTest {

    @Test
    fun `the module is wired into the build`() {
        assertEquals(":core", CoreModule.PATH)
    }
}
