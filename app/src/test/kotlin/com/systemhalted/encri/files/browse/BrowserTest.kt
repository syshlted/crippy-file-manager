package com.systemhalted.encri.files.browse

import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.FakeOpener
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.document
import com.systemhalted.encri.files.folder
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserTest {
    private val root = folder("root", "Vault", children = 3)
    private val photos = folder("photos", "Photos", "root", children = 1)
    private val notes = document("notes", "notes.txt", "root")
    private val apple = document("apple", "apple.txt", "root")
    private val beach = document("beach", "beach.jpg", "photos", "image/jpeg")

    private val tree =
        mapOf(
            "root" to listOf(notes, photos, apple),
            "photos" to listOf(beach),
        )

    private class Opened(
        val link: VaultLink,
        val browser: Browser,
        val session: FakeSession,
    )

    private fun TestScope.opened(session: FakeSession = FakeSession(root, tree)): Opened {
        val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
        val browser = Browser(link, backgroundScope)
        link.open()
        return Opened(link, browser, session)
    }

    @Test
    fun `with no session there is nothing to show`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = VaultLink(FakeOpener(Opening.NotAuthorized), backgroundScope)
            val browser = Browser(link, backgroundScope)
            link.open()
            assertEquals(Listing.Waiting, browser.listing.value)
            assertEquals(emptyList<Any>(), browser.path.value)
        }

    @Test
    fun `a session starts where the user was let in, folders first`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            assertEquals(listOf(root), opened.browser.path.value)
            assertEquals(Listing.Listed(listOf(photos, apple, notes)), opened.browser.listing.value)
        }

    @Test
    fun `going into a folder and back up lists each again`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            opened.browser.enter(photos)
            assertEquals(listOf(root, photos), opened.browser.path.value)
            assertEquals(Listing.Listed(listOf(beach)), opened.browser.listing.value)
            assertTrue(opened.browser.up())
            assertEquals(listOf(root), opened.browser.path.value)
            assertEquals(listOf("root", "photos", "root"), opened.session.listed)
        }

    @Test
    fun `there is no going above where the user was let in`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            assertFalse(opened.browser.up())
            assertEquals(listOf(root), opened.browser.path.value)
        }

    @Test
    fun `a document is not a folder to go into`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            assertThrows<IllegalArgumentException> { opened.browser.enter(notes) }
        }

    @Test
    fun `a listing that fails says why`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            opened.session.failNext = Failure.FAILED
            opened.browser.reload()
            assertEquals(Listing.Failed(Failure.FAILED), opened.browser.listing.value)
        }

    @Test
    fun `nothing is shown while the vault is locked, and the place is kept for the unlock`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            opened.browser.enter(photos)
            opened.session.events.emit(VaultEvent.Locked)
            assertEquals(Listing.Waiting, opened.browser.listing.value)
            opened.session.events.emit(VaultEvent.Unlocked)
            assertEquals(listOf(root, photos), opened.browser.path.value)
            assertEquals(Listing.Listed(listOf(beach)), opened.browser.listing.value)
        }

    @Test
    fun `a change to the folder being looked at lists it again, and a change elsewhere does not`() =
        runTest(UnconfinedTestDispatcher()) {
            val opened = opened()
            opened.session.events.emit(VaultEvent.FolderChanged("photos"))
            assertEquals(listOf("root"), opened.session.listed)
            opened.session.events.emit(VaultEvent.FolderChanged("root"))
            assertEquals(listOf("root", "root"), opened.session.listed)
        }

    @Test
    fun `a session reopened at the same place keeps the user there`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = FakeSession(root, tree)
            val second = FakeSession(root, tree)
            val link = VaultLink(FakeOpener(Opening.Open(first), Opening.Open(second)), backgroundScope)
            val browser = Browser(link, backgroundScope)
            link.open()
            browser.enter(photos)
            first.events.emit(VaultEvent.Ended)
            assertEquals(listOf(root, photos), browser.path.value)
            assertEquals(listOf("photos"), second.listed)
        }

    @Test
    fun `a session rooted somewhere else starts at its own root`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = FakeSession(root, tree)
            val second = FakeSession(photos, tree)
            val link = VaultLink(FakeOpener(Opening.Open(first), Opening.Open(second)), backgroundScope)
            val browser = Browser(link, backgroundScope)
            link.open()
            first.events.emit(VaultEvent.Ended)
            assertEquals(listOf(photos), browser.path.value)
            assertEquals(Listing.Listed(listOf(beach)), browser.listing.value)
        }

    @Test
    fun `names sort as the language sorts them, not by character code`() {
        val upper = document("u", "Zebra.txt")
        val lower = document("l", "apple.txt")
        assertEquals(listOf(lower, upper), Ordering.of(listOf(upper, lower)))
    }
}
