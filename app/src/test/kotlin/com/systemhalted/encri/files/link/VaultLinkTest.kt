package com.systemhalted.encri.files.link

import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.VaultEvent
import com.systemhalted.encri.files.FakeOpener
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.folder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VaultLinkTest {
    private val root = folder("root", "Vault")

    private fun session() = FakeSession(root, mapOf("root" to emptyList()))

    @Test
    fun `nothing is asked until something opens the link`() =
        runTest(UnconfinedTestDispatcher()) {
            val opener = FakeOpener(Opening.Open(session()))
            val link = VaultLink(opener, backgroundScope)
            assertEquals(LinkState.Idle, link.state.value)
            assertEquals(0, opener.opened)
        }

    @Test
    fun `a session that opens is the state, unlocked`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = session()
            val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
            link.open()
            assertEquals(LinkState.Open(session, locked = false), link.state.value)
        }

    @Test
    fun `while encri is being asked the state says so, and a second open asks nothing more`() =
        runTest(UnconfinedTestDispatcher()) {
            val answer = CompletableDeferred<Opening>()
            var asked = 0
            val link =
                VaultLink(
                    {
                        asked++
                        answer.await()
                    },
                    backgroundScope,
                )
            link.open()
            link.open()
            assertEquals(LinkState.Connecting, link.state.value)
            answer.complete(Opening.NotAuthorized)
            assertEquals(LinkState.Refused(Opening.NotAuthorized), link.state.value)
            assertEquals(1, asked)
        }

    @Test
    fun `an open link is not asked for again`() =
        runTest(UnconfinedTestDispatcher()) {
            val opener = FakeOpener(Opening.Open(session()))
            val link = VaultLink(opener, backgroundScope)
            link.open()
            link.open()
            assertEquals(1, opener.opened)
        }

    @Test
    fun `a refusal can be asked about again, and the second answer replaces it`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = session()
            val link = VaultLink(FakeOpener(Opening.Locked, Opening.Open(session)), backgroundScope)
            link.open()
            assertEquals(LinkState.Refused(Opening.Locked), link.state.value)
            link.open()
            assertEquals(LinkState.Open(session, locked = false), link.state.value)
        }

    @Test
    fun `a lock and an unlock reach the state without ending the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = session()
            val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
            link.open()
            session.events.emit(VaultEvent.Locked)
            assertEquals(LinkState.Open(session, locked = true), link.state.value)
            session.events.emit(VaultEvent.Unlocked)
            assertEquals(LinkState.Open(session, locked = false), link.state.value)
            assertFalse(session.closed)
        }

    @Test
    fun `a folder encri says changed is passed on`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = session()
            val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
            val heard = mutableListOf<String>()
            backgroundScope.launch { link.changes.collect { heard += it } }
            link.open()
            session.events.emit(VaultEvent.FolderChanged("root"))
            assertEquals(listOf("root"), heard)
        }

    @Test
    fun `a session encri ends is closed and asked for again at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = session()
            val opener = FakeOpener(Opening.Open(first), Opening.NotAuthorized)
            val link = VaultLink(opener, backgroundScope)
            link.open()
            first.events.emit(VaultEvent.Ended)
            assertTrue(first.closed)
            assertEquals(2, opener.opened)
            assertEquals(LinkState.Refused(Opening.NotAuthorized), link.state.value)
        }

    @Test
    fun `an event from a session the link has moved past changes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = session()
            val second = session()
            val link = VaultLink(FakeOpener(Opening.Open(first), Opening.Open(second)), backgroundScope)
            link.open()
            first.events.emit(VaultEvent.Ended)
            assertEquals(LinkState.Open(second, locked = false), link.state.value)
            first.events.emit(VaultEvent.Locked)
            assertEquals(LinkState.Open(second, locked = false), link.state.value)
        }

    @Test
    fun `locking asks the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = session()
            val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
            link.lock()
            assertEquals(0, session.locks)
            link.open()
            link.lock()
            assertEquals(1, session.locks)
        }

    @Test
    fun `a handoff with no session says the session is over`() =
        runTest(UnconfinedTestDispatcher()) {
            val link = VaultLink(FakeOpener(Opening.Locked), backgroundScope)
            link.open()
            val handed = link.handOff("doc")
            assertEquals(Failure.ENDED, (handed as EncriResult.Failed).failure)
        }
}
