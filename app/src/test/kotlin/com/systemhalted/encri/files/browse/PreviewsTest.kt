package com.systemhalted.encri.files.browse

import android.graphics.Bitmap
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.FakeOpener
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.document
import com.systemhalted.encri.files.folder
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreviewsTest {
    private val root = folder("root", "Vault")
    private val photo = document("photo", "beach.jpg", "root", "image/jpeg", thumbnailable = true)
    private val notes = document("notes", "notes.txt", "root")
    private val session = FakeSession(root, mapOf("root" to listOf(photo, notes)))
    private var decoded: Bitmap? = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun TestScope.previews(): Previews {
        val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
        val previews = Previews(link, backgroundScope, BUDGET, EmptyCoroutineContext) { decoded }
        link.open()
        return previews
    }

    @Test
    fun `a preview is asked for at the size given, handed over, and held`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            var got: Bitmap? = null
            previews.load(photo, SIZE) { got = it }
            assertEquals(listOf(Triple("photo", SIZE, SIZE)), session.previewed)
            assertSame(decoded, got)
            assertSame(decoded, previews.held(photo, SIZE))
        }

    @Test
    fun `a preview held is not asked for again`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            previews.load(photo, SIZE) {}
            var again: Bitmap? = null
            assertNull(previews.load(photo, SIZE) { again = it })
            assertSame(decoded, again)
            assertEquals(1, session.previewed.size)
        }

    @Test
    fun `a folder, and a document encri cannot preview, are not asked about`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            assertFalse(previews.shows(root))
            assertFalse(previews.shows(notes))
            assertNull(previews.load(root, SIZE) {})
            assertNull(previews.load(notes, SIZE) {})
            assertTrue(session.previewed.isEmpty())
        }

    @Test
    fun `turned off, nothing is asked for and nothing is held`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            previews.load(photo, SIZE) {}
            previews.enabled = false
            assertNull(previews.held(photo, SIZE))
            assertFalse(previews.shows(photo))
            assertNull(previews.load(photo, SIZE) {})
            assertEquals(1, session.previewed.size)
        }

    @Test
    fun `a lock drops every preview, and after the unlock they are asked for again`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            previews.load(photo, SIZE) {}
            session.events.emit(VaultEvent.Locked)
            assertNull(previews.held(photo, SIZE))
            session.events.emit(VaultEvent.Unlocked)
            previews.load(photo, SIZE) {}
            assertEquals(2, session.previewed.size)
        }

    @Test
    fun `a preview in flight when the vault locks is cancelled, and never shown`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            session.previewGate = CompletableDeferred()
            var shown = false
            previews.load(photo, SIZE) { shown = true }
            session.events.emit(VaultEvent.Locked)
            assertEquals(1, session.previewsCancelled)
            assertFalse(shown)
            assertNull(previews.held(photo, SIZE))
        }

    @Test
    fun `a request the caller cancels cancels encri's work`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            session.previewGate = CompletableDeferred()
            previews.load(photo, SIZE) {}?.cancel()
            assertEquals(1, session.previewsCancelled)
        }

    @Test
    fun `a preview encri cannot make is not asked for again, and one that merely failed is`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            session.previewAnswer = { EncriResult.Failed(Failure.UNSUPPORTED, "no") }
            previews.load(photo, SIZE) {}
            assertNull(previews.load(photo, SIZE) {})
            val other = photo.copy(id = "other")
            session.previewAnswer = { EncriResult.Failed(Failure.FAILED, "busy") }
            previews.load(other, SIZE) {}
            previews.load(other, SIZE) {}
            assertEquals(listOf("photo", "other", "other"), session.previewed.map { it.first })
        }

    @Test
    fun `an answer that does not decode is not asked for again`() =
        runTest(UnconfinedTestDispatcher()) {
            decoded = null
            val previews = previews()
            previews.load(photo, SIZE) {}
            assertNull(previews.load(photo, SIZE) {})
            assertEquals(1, session.previewed.size)
        }

    @Test
    fun `a document written since is a new preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val previews = previews()
            previews.load(photo, SIZE) {}
            previews.load(photo.copy(modifiedMillis = 1), SIZE) {}
            assertEquals(2, session.previewed.size)
        }

    @Test
    fun `the decoder previews are read with by default is the platform's`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
            val previews = Previews(link, backgroundScope, BUDGET, EmptyCoroutineContext)
            link.open()
            val png = java.io.ByteArrayOutputStream()
            Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, png)
            session.previewAnswer = { EncriResult.Ok(png.toByteArray()) }
            previews.load(photo, SIZE) {}
            assertNotNull(previews.held(photo, SIZE))
        }

    private companion object {
        const val SIZE = 144
        const val BUDGET = 1024 * 1024
    }
}
