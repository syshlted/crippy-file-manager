package com.systemhalted.encri.files.ui

import com.systemhalted.encri.files.transfer.Direction
import com.systemhalted.encri.files.transfer.NotCopied
import com.systemhalted.encri.files.transfer.Report
import com.systemhalted.encri.files.transfer.Transfer
import com.systemhalted.encri.files.transfer.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the copy bar says (F6): how far a copy has got, how it ended, and no name over a locked vault. */
@RunWith(RobolectricTestRunner::class)
class TransferWordsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun said(
        transfer: Transfer,
        namesShown: Boolean = true,
    ) = TransferWords.of(context, transfer, namesShown)

    private fun ended(
        report: Report,
        namesShown: Boolean = true,
    ) = said(Transfer.Finished(report), namesShown)

    @Test
    fun `a copy in says which file of how many, how much, and to stay`() {
        val words = said(Transfer.Running(Direction.IN, 1, 3, 2_000_000, 4_000_000))
        assertEquals(
            "Copying 2 of 3 into the vault…\n2.0 MB of 4.0 MB\n" +
                "Keep Encri Files open until it finishes: Android may pause or stop it while another app is in front.",
            words,
        )
    }

    @Test
    fun `a copy the service holds says it carries on when the user leaves, instead of asking them to stay`() {
        val words = said(Transfer.Running(Direction.IN, 0, 1, 0, null, held = true))
        assertEquals(
            "Copying 1 of 1 into the vault…\n" +
                "You can leave Encri Files: the copy carries on until it is done, or until the vault locks.",
            words,
        )
    }

    @Test
    fun `a copy whose total is unknown says how much so far, and nothing before it starts`() {
        assertTrue(said(Transfer.Running(Direction.IN, 0, 1, 5_000_000, null)).contains("5.0 MB so far"))
        assertFalse(said(Transfer.Running(Direction.IN, 0, 1, 0, null)).contains("so far"))
    }

    @Test
    fun `a copy out says it is copying out`() {
        assertTrue(said(Transfer.Running(Direction.OUT, 0, 1, 0, 10)).startsWith("Copying out of the vault…"))
    }

    @Test
    fun `the bar follows bytes when they are known, files when they are not, and spins otherwise`() {
        assertEquals(500, TransferWords.progressOf(Transfer.Running(Direction.IN, 0, 1, 50, 100)))
        assertEquals(1000, TransferWords.progressOf(Transfer.Running(Direction.IN, 0, 1, 150, 100)))
        assertEquals(250, TransferWords.progressOf(Transfer.Running(Direction.IN, 1, 4, 0, null)))
        assertNull(TransferWords.progressOf(Transfer.Running(Direction.IN, 0, 1, 0, null)))
    }

    @Test
    fun `everything copied in says so, and that the originals stay`() {
        assertEquals(
            "Copied 2 files into the vault.\nThe originals are still where they were, and not encrypted: copying a " +
                "file in does not remove it. Delete them there if they should be in the vault alone.",
            ended(Report.In(2, emptyList(), 0)),
        )
        assertTrue(ended(Report.In(1, emptyList(), 0)).startsWith("Copied 1 file into the vault."))
    }

    @Test
    fun `each file refused is named with why, and the ones a stop never reached are counted`() {
        val words =
            ended(
                Report.In(
                    1,
                    listOf(
                        NotCopied("notes.txt", Why.NAME_TAKEN),
                        NotCopied(null, Why.NO_NAME),
                        NotCopied("b.mp4", Why.LOCKED),
                        NotCopied("c.mp4", Why.LOCKED),
                    ),
                    1,
                ),
            )
        assertEquals(
            listOf(
                "Copied 1 of 5 into the vault.",
                "“notes.txt”: Something with that name is already there.",
                "A file with no name: The app it came from gives it no name.",
                "2 were not copied. The vault is locked.",
                "One is left in the vault with “(unfinished)” in its name: delete it, and copy that file again.",
            ),
            words.lines().dropLast(1),
        )
    }

    @Test
    fun `over a locked vault no file is named`() {
        val words = ended(Report.In(0, listOf(NotCopied("secret.pdf", Why.FAILED)), 0), namesShown = false)
        assertFalse(words.contains("secret.pdf"))
        assertEquals(
            "Nothing was copied into the vault.\nUnlock the vault to see which were not copied, and why.",
            words,
        )
        assertEquals("Copied out of the vault.", ended(Report.Out("secret.pdf", null), namesShown = false))
    }

    @Test
    fun `a copy that stopped before anything arrived says why once`() {
        val stopped = listOf(NotCopied("a.txt", Why.STOPPED), NotCopied("b.txt", Why.STOPPED))
        assertEquals("Nothing was copied into the vault.\nIt was stopped.", ended(Report.In(0, stopped, 0)))
    }

    @Test
    fun `a long list of refusals names ten and counts the rest`() {
        val refused = (1..12).map { NotCopied("f$it.txt", Why.NAME_TAKEN) }
        val words = ended(Report.In(0, refused, 0)).lines()
        assertEquals(12, words.size)
        assertEquals("2 more were not copied.", words.last())
    }

    @Test
    fun `no room says how much is needed and how much there is`() {
        assertEquals(
            "Nothing was copied: the files need 3.0 MB, and the vault can take 1.0 MB more.",
            ended(Report.NoRoom(3_000_000, 1_000_000)),
        )
    }

    @Test
    fun `a copy out that worked names it, and one that did not says what is left behind`() {
        assertEquals("Copied “notes.txt” out of the vault.", ended(Report.Out("notes.txt", null)))
        assertEquals(
            "It was not copied out. The place you chose would not take it.\n" +
                "The file made at the place you chose is left there, empty or part-written: delete it there.",
            ended(Report.Out("notes.txt", Why.DESTINATION)),
        )
        assertTrue(ended(Report.Out("notes.txt", Why.SHORT)).startsWith("The place you chose holds less"))
    }

    @Test
    fun `a folder gone is not a document gone`() {
        assertEquals(
            context.getString(com.systemhalted.encri.files.R.string.reason_folder_gone),
            context.getString(TransferWords.reasonOf(Why.GONE, Direction.IN)),
        )
        assertEquals(
            "It is no longer in the vault.",
            context.getString(TransferWords.reasonOf(Why.GONE, Direction.OUT)),
        )
    }

    @Test
    fun `every reason has a sentence`() {
        Why.entries.forEach { why ->
            Direction.entries.forEach { direction ->
                assertTrue(context.getString(TransferWords.reasonOf(why, direction)).isNotBlank())
            }
        }
    }
}
