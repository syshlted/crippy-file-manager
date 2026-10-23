package com.systemhalted.encri.files

import com.systemhalted.encri.files.link.ClientOpener
import com.systemhalted.encri.files.link.Opening
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class FilesApplicationTest {
    @Test
    fun `the session and the place are made once, for the process`() {
        val app = RuntimeEnvironment.getApplication() as FilesApplication
        assertSame(app.link, app.link)
        assertSame(app.browser, app.browser)
    }

    @Test
    fun `with no encri on the phone the real opener says it is not installed`() {
        val opening = runBlocking { ClientOpener(RuntimeEnvironment.getApplication()).open() }
        assertEquals(Opening.NotInstalled, opening)
    }
}
