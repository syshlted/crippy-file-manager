package com.systemhalted.encri.files.ui

import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.browse.Listing
import com.systemhalted.encri.files.document
import com.systemhalted.encri.files.folder
import com.systemhalted.encri.files.link.LinkState
import com.systemhalted.encri.files.link.Opening
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenTest {
    private val root = folder("root", "Vault")
    private val photos = folder("photos", "Photos", "root")
    private val beach = document("beach", "beach.jpg", "photos")
    private val session = FakeSession(root, emptyMap())
    private val open = LinkState.Open(session, locked = false)

    private fun refused(why: Opening) = Screen.of(LinkState.Refused(why), emptyList(), Listing.Waiting)

    @Test
    fun `before encri answers the screen says it is asking`() {
        assertEquals(Screen(R.string.connecting), Screen.of(LinkState.Idle, emptyList(), Listing.Waiting))
        assertEquals(Screen(R.string.connecting), Screen.of(LinkState.Connecting, emptyList(), Listing.Waiting))
    }

    @Test
    fun `each refusal says why, with the remedy the user has`() {
        assertEquals(Screen(R.string.locked, remedy = Remedy.UNLOCK), refused(Opening.Locked))
        assertEquals(Screen(R.string.not_authorized, remedy = Remedy.ASK), refused(Opening.NotAuthorized))
        assertEquals(Screen(R.string.access_off, remedy = Remedy.ASK), refused(Opening.AccessOff))
        assertEquals(Screen(R.string.unavailable, remedy = Remedy.RETRY), refused(Opening.Unavailable))
    }

    @Test
    fun `a refusal nothing here can fix offers nothing to press`() {
        assertEquals(Screen(R.string.not_installed), refused(Opening.NotInstalled))
        assertEquals(Screen(R.string.not_trusted, listOf("ab12")), refused(Opening.NotTrusted("ab12")))
        assertEquals(Screen(R.string.too_old, listOf(0)), refused(Opening.TooOld(0)))
    }

    @Test
    fun `a session is never drawn as a refusal`() {
        assertEquals(Screen(R.string.connecting), refused(Opening.Open(session)))
    }

    @Test
    fun `a locked vault shows no name and no document`() {
        val screen =
            Screen.of(
                LinkState.Open(session, locked = true),
                listOf(root, photos),
                Listing.Listed(listOf(beach)),
            )
        assertEquals(Screen(R.string.locked, remedy = Remedy.UNLOCK), screen)
    }

    @Test
    fun `at the top the app's own name is the title and there is no going up`() {
        val screen = Screen.of(open, listOf(root), Listing.Listed(listOf(photos)))
        assertNull(screen.title)
        assertFalse(screen.canGoUp)
        assertNull(screen.message)
        assertEquals(listOf(photos), screen.documents)
        assertTrue(screen.canLock)
        assertTrue(screen.canReload)
    }

    @Test
    fun `inside a folder its name is the title and up goes back`() {
        val screen = Screen.of(open, listOf(root, photos), Listing.Listed(listOf(beach)))
        assertEquals("Photos", screen.title)
        assertTrue(screen.canGoUp)
    }

    @Test
    fun `a folder being listed, or with nothing in it, says so`() {
        assertEquals(R.string.loading, Screen.of(open, listOf(root), Listing.Loading).message)
        assertEquals(R.string.loading, Screen.of(open, listOf(root), Listing.Waiting).message)
        assertEquals(R.string.empty_folder, Screen.of(open, listOf(root), Listing.Listed(emptyList())).message)
    }

    @Test
    fun `a failed listing is put in the user's terms`() {
        fun failed(failure: Failure) = Screen.of(open, listOf(root), Listing.Failed(failure)).message
        assertEquals(R.string.folder_gone, failed(Failure.NOT_FOUND))
        assertEquals(R.string.locked, failed(Failure.LOCKED))
        assertEquals(R.string.session_ended, failed(Failure.ENDED))
        assertEquals(R.string.session_ended, failed(Failure.DISCONNECTED))
        assertEquals(R.string.list_failed, failed(Failure.FAILED))
    }
}
