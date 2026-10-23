package com.systemhalted.encri.files.transfer

import android.net.Uri
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.FakeOpener
import com.systemhalted.encri.files.FakeOutside
import com.systemhalted.encri.files.FakeSession
import com.systemhalted.encri.files.document
import com.systemhalted.encri.files.folder
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Copying into and out of the vault (AGENTS.md F6), against a stand-in for encri and for the apps the system's
 * picker reaches. The half that matters is what is left when a copy does not finish: never a file in the vault
 * that reads as whole and is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransfersTest {
    private val root = folder("root", "Vault")
    private val notes = document("notes", "notes.txt", "root")
    private val session = FakeSession(root, mapOf("root" to listOf(notes)))
    private val outside = FakeOutside()
    private val place = Uri.parse("content://elsewhere/out")

    private fun TestScope.transfers(): Transfers {
        val link = VaultLink(FakeOpener(Opening.Open(session)), backgroundScope)
        link.open()
        return Transfers(link, outside, backgroundScope, EmptyCoroutineContext, pauseMillis = 0)
    }

    private fun Transfers.ended(): Report = (state.value as Transfer.Finished).report

    private fun names(): List<String> = session.tree.getValue("root").map { it.name }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    // ---- In ------------------------------------------------------------------------------------

    @Test
    fun `a file arrives whole under its own name, written under an unfinished one, with its date`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val content = bytes(200_000)
            transfers.copyIn("root", listOf(outside.add("trip.jpg", content, "image/jpeg", modified = 1_234)))
            assertEquals(Report.In(1, emptyList(), 0), transfers.ended())
            assertEquals(
                listOf("createFile root (unfinished) trip.jpg", "write file-1", "rename file-1 trip.jpg"),
                session.changes,
            )
            assertArrayEquals(content, session.contents.getValue("file-1"))
            assertEquals(listOf("notes.txt", "trip.jpg"), names())
            // After the rename, which encri stamps with the time it happened.
            assertEquals(
                1_234L,
                session.tree
                    .getValue("root")
                    .single { it.name == "trip.jpg" }
                    .modifiedMillis,
            )
        }

    @Test
    fun `a file whose app gives no date keeps the date it was copied`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10))))
            assertTrue(session.modified.isEmpty())
        }

    @Test
    fun `several files arrive in turn, and the bar reaches the end`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val seen = mutableListOf<Transfer.Running>()
            outside.whileReading = { (transfers.state.value as? Transfer.Running)?.let(seen::add) }
            val first = outside.add("a.txt", bytes(3_000_000))
            val second = outside.add("b.txt", bytes(10))
            transfers.copyIn("root", listOf(first, second))
            assertEquals(Report.In(2, emptyList(), 0), transfers.ended())
            assertEquals(3_000_010L, seen.last().bytesTotal)
            assertTrue("the bar moved while the first was copied", seen.map { it.bytesDone }.distinct().size > 2)
            assertEquals(1, seen.last().done)
        }

    @Test
    fun `a name already in the folder is refused, and nothing is made for it`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("notes.txt", bytes(10))))
            assertEquals(Report.In(0, listOf(NotCopied("notes.txt", Why.NAME_TAKEN)), 0), transfers.ended())
            assertTrue(session.changes.isEmpty())
        }

    @Test
    fun `two files of one name in one choice, and the second is refused`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10)), outside.add("a.txt", bytes(20))))
            assertEquals(Report.In(1, listOf(NotCopied("a.txt", Why.NAME_TAKEN)), 0), transfers.ended())
            assertEquals(10, session.contents.getValue("file-1").size)
        }

    @Test
    fun `a name taken while the file was copied leaves no copy behind`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            outside.whileReading = {
                if (session.tree.getValue("root").none { it.name == "a.txt" }) {
                    session.tree.getValue("root") += document("other", "a.txt", "root")
                }
            }
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10))))
            assertEquals(Report.In(0, listOf(NotCopied("a.txt", Why.NAME_TAKEN)), 0), transfers.ended())
            assertEquals(listOf("notes.txt", "a.txt"), names())
        }

    @Test
    fun `files that need more room than the vault has are not copied at all`() =
        runTest(UnconfinedTestDispatcher()) {
            session.usable = 100
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(60)), outside.add("b.txt", bytes(60))))
            assertEquals(Report.NoRoom(120, 100), transfers.ended())
            assertTrue(session.changes.isEmpty())
        }

    @Test
    fun `a file its app gives no name is reported, and the rest still arrive`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val gone = Uri.parse("content://elsewhere/gone")
            transfers.copyIn("root", listOf(gone, outside.add("b.txt", bytes(10))))
            assertEquals(Report.In(1, listOf(NotCopied(null, Why.NO_NAME)), 0), transfers.ended())
        }

    @Test
    fun `a file that cannot be read at all is removed from the vault again`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val named = outside.add("a.txt", bytes(10), failAfter = 0)
            transfers.copyIn("root", listOf(named))
            assertEquals(Report.In(0, listOf(NotCopied("a.txt", Why.UNREADABLE)), 0), transfers.ended())
            assertEquals(listOf("notes.txt"), names())
            assertNull("nothing part-read is saved", session.contents["file-1"])
        }

    @Test
    fun `a file whose app refuses to open it is reported, and nothing of it stays`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val refused = outside.add("a.txt", bytes(10))
            outside.unopenable += refused
            transfers.copyIn("root", listOf(refused, outside.add("b.txt", bytes(10))))
            assertEquals(Report.In(1, listOf(NotCopied("a.txt", Why.UNREADABLE)), 0), transfers.ended())
            assertEquals(listOf("notes.txt", "b.txt"), names())
        }

    @Test
    fun `a file whose app stops part way is not saved, and goes`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(300_000), failAfter = 100_000)))
            assertEquals(Report.In(0, listOf(NotCopied("a.txt", Why.UNREADABLE)), 0), transfers.ended())
            assertNull(session.contents["file-1"])
            assertEquals(listOf("notes.txt"), names())
        }

    @Test
    fun `a full vault stops the copy, and the files after it are not tried`() =
        runTest(UnconfinedTestDispatcher()) {
            session.refuseWriteAfter = 70_000 to Failure.NO_SPACE
            val transfers = transfers()
            val later = outside.add("b.txt", bytes(10))
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(200_000)), later))
            assertEquals(
                Report.In(0, listOf(NotCopied("a.txt", Why.NO_SPACE), NotCopied("b.txt", Why.NO_SPACE)), 0),
                transfers.ended(),
            )
            assertFalse("the second was never opened", later in outside.opened)
            assertEquals(listOf("notes.txt"), names())
        }

    @Test
    fun `a stop ends the copy at its next block, and keeps only what was finished`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            val second = outside.add("b.txt", bytes(200_000))
            outside.whileReading = { if (outside.opened.last() == second) transfers.stop() }
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10)), second, outside.add("c.txt", bytes(10))))
            assertEquals(
                Report.In(1, listOf(NotCopied("b.txt", Why.STOPPED), NotCopied("c.txt", Why.STOPPED)), 0),
                transfers.ended(),
            )
            assertEquals(listOf("notes.txt", "a.txt"), names())
            assertNull("the one stopped saved nothing", session.contents["file-2"])
        }

    @Test
    fun `a copy that cannot be removed is counted, its unfinished name left to say what it is`() =
        runTest(UnconfinedTestDispatcher()) {
            session.refuseWriteAfter = 0 to Failure.LOCKED
            session.failChanges = Failure.LOCKED
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10))))
            assertEquals(Report.In(0, listOf(NotCopied("a.txt", Why.LOCKED)), 1), transfers.ended())
            assertEquals(listOf("notes.txt", "(unfinished) a.txt"), names())
        }

    @Test
    fun `a folder that cannot be listed reports every file with why`() =
        runTest(UnconfinedTestDispatcher()) {
            session.failNext = Failure.NOT_FOUND
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10))))
            assertEquals(Report.In(0, listOf(NotCopied("a.txt", Why.GONE)), 0), transfers.ended())
        }

    @Test
    fun `a name encri will not take is reported, and the copy goes on`() =
        runTest(UnconfinedTestDispatcher()) {
            session.failCreate = Failure.INVALID
            val transfers = transfers()
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10)), outside.add("b.txt", bytes(10))))
            assertEquals(
                Report.In(0, listOf(NotCopied("a.txt", Why.BAD_NAME), NotCopied("b.txt", Why.BAD_NAME)), 0),
                transfers.ended(),
            )
        }

    @Test
    fun `one copy at a time`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            var second: Boolean? = null
            outside.whileReading = { if (second == null) second = transfers.copyIn("root", emptyList()) }
            assertTrue(transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10)))))
            assertEquals(false, second)
        }

    @Test
    fun `an ending is kept until it is dismissed, and a running copy cannot be`() =
        runTest(UnconfinedTestDispatcher()) {
            val transfers = transfers()
            outside.whileReading = {
                transfers.dismiss()
                assertTrue(transfers.isRunning)
            }
            transfers.copyIn("root", listOf(outside.add("a.txt", bytes(10))))
            assertTrue(transfers.state.value is Transfer.Finished)
            transfers.dismiss()
            assertNull(transfers.state.value)
        }

    // ---- Out -----------------------------------------------------------------------------------

    private fun stored(size: Int): ByteArray = bytes(size).also { session.contents["notes"] = it }

    @Test
    fun `a document arrives whole at the place chosen`() =
        runTest(UnconfinedTestDispatcher()) {
            val content = stored(300_000)
            val transfers = transfers()
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", null), transfers.ended())
            assertArrayEquals(content, outside.written.getValue(place))
        }

    @Test
    fun `a document encri will not open is reported`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(10)
            session.failNext = Failure.LOCKED
            val transfers = transfers()
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.LOCKED), transfers.ended())
        }

    @Test
    fun `encri stopping part way is not called a copy`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(300_000)
            session.failReadAfter = 100_000
            val transfers = transfers()
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.VAULT_STOPPED), transfers.ended())
        }

    @Test
    fun `a place that will not open, take the bytes or close is the place failing`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(300_000)
            val transfers = transfers()
            outside.refuseOutput = true
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.DESTINATION), transfers.ended())
            outside.refuseOutput = false
            outside.refuseWriteAfter = 100_000
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.DESTINATION), transfers.ended())
            outside.refuseWriteAfter = null
            outside.refuseClose = true
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.DESTINATION), transfers.ended())
        }

    @Test
    fun `a stop ends a copy out`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(300_000)
            val transfers = transfers()
            outside.whileWriting = { transfers.stop() }
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.STOPPED), transfers.ended())
        }

    @Test
    fun `a place that says it holds less is asked again before it is believed`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(1_000)
            val transfers = transfers()
            var answers = 0
            outside.keptLength = { if (++answers < 3) 0 else 1_000 }
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", null), transfers.ended())
            assertEquals(3, outside.keptAsked)
        }

    @Test
    fun `a place that goes on holding less holds a copy that is not whole`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(1_000)
            val transfers = transfers()
            outside.keptLength = { 10 }
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", Why.SHORT), transfers.ended())
            assertEquals(13, outside.keptAsked)
        }

    @Test
    fun `a place that will not say how long it is claims nothing either way`() =
        runTest(UnconfinedTestDispatcher()) {
            stored(1_000)
            val transfers = transfers()
            outside.keptLength = { null }
            transfers.copyOut(notes, place)
            assertEquals(Report.Out("notes.txt", null), transfers.ended())
            assertEquals(1, outside.keptAsked)
        }
}
