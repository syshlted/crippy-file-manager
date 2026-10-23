package com.systemhalted.encri.files.transfer

import android.net.Uri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.NameConflict
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Which way a copy goes. */
enum class Direction {
    IN,
    OUT,
}

/** Why a file was not copied, which the screen says in the user's terms (F6). */
enum class Why {
    /** Something with that name is already in the folder. */
    NAME_TAKEN,

    /** encri will not take the name. */
    BAD_NAME,

    /** The app the file came from gives it no name. */
    NO_NAME,
    NO_SPACE,
    LOCKED,
    NOT_ALLOWED,

    /** Copying in, the folder is no longer in the vault; copying out, the document is not. */
    GONE,
    ACCESS_OFF,
    ENDED,

    /** The app the file came from would not hand it over, or stopped part way. */
    UNREADABLE,

    /** encri stopped handing the document over part way, as it does when the vault locks. */
    VAULT_STOPPED,

    /** The place chosen for a copy out would not take it. */
    DESTINATION,

    /** The place chosen for a copy out holds less than was written. */
    SHORT,

    /** The user stopped the copy. */
    STOPPED,
    FAILED,
}

/** A file a copy in did not bring, and why. [name] is null for a file whose app gave it none. */
data class NotCopied(
    val name: String?,
    val why: Why,
)

/** How a copy ended. */
sealed interface Report {
    /** A copy in: how many arrived, each that did not, and how many are left with *(unfinished)* names. */
    data class In(
        val copied: Int,
        val notCopied: List<NotCopied>,
        val leftUnfinished: Int,
    ) : Report

    /** Nothing was copied in: the files need [neededBytes] and the vault can take [usableBytes] more. */
    data class NoRoom(
        val neededBytes: Long,
        val usableBytes: Long,
    ) : Report

    /** A copy out of [name]: [why] is null when the copy is whole at the place chosen. */
    data class Out(
        val name: String,
        val why: Why?,
    ) : Report
}

/** A copy running, or the one that last ended, which stays until the user dismisses it. */
sealed interface Transfer {
    data class Running(
        val direction: Direction,
        /** Files finished, copied or not. */
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        /** Null when an app a file came from did not say how long it is. */
        val bytesTotal: Long?,
        /** True once a foreground service holds the copy, so it carries on when the user leaves the app (F8). */
        val held: Boolean = false,
    ) : Transfer

    data class Finished(
        val report: Report,
    ) : Transfer
}

/**
 * Copies into and out of the vault, one at a time, for the life of the process (AGENTS.md F6).
 *
 * Held by the application beside the [VaultLink], so leaving the screen does not stop a copy, and coming back
 * shows it.
 *
 * **Copying in never leaves a file that reads as whole and is not.** Each file is created as
 * `(unfinished) <name>`, written, and renamed to its own name only once encri has saved it, which is encri's
 * own rule for a share (encri D118). A copy that fails or is stopped removes what it made; one that cannot —
 * the vault locked under it — leaves the name that says what it is. encri saves a write only when the writer
 * finishes (encri-api L11), so a stopped copy is abandoned by throwing, never by returning early.
 *
 * **A name already in the folder is refused**, as every change here refuses one (F5): the file is reported,
 * never given a name the user did not choose.
 */
@Suppress("TooManyFunctions") // One per step of a copy, each with its own ending.
class Transfers(
    private val link: VaultLink,
    private val outside: Outside,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    /** How long a short length reported after a copy out is waited on before it is believed (encri B102). */
    private val pauseMillis: Long = LENGTH_PAUSE_MILLIS,
) {
    private val mutableState = MutableStateFlow<Transfer?>(null)
    val state: StateFlow<Transfer?> = mutableState.asStateFlow()

    private val stopping = AtomicBoolean(false)
    private var held = false
    private var screens = 0

    /** The folder a copy in goes into, while the user chooses files in the system's picker. */
    var pendingFolderId: String? = null

    /** The document a copy out is of, while the user chooses a place for it in the system's picker. */
    var pendingDocument: EncriDocument? = null

    val isRunning: Boolean get() = state.value is Transfer.Running

    /** True while a screen of this app is in front, where the copy bar already says how a copy ended (F8). */
    val isWatched: Boolean get() = screens > 0

    /** A screen came to the front. */
    fun screenStarted() {
        screens++
    }

    /** A screen went from the front. */
    fun screenStopped() {
        screens = (screens - 1).coerceAtLeast(0)
    }

    /**
     * Whether a foreground service holds the copy running (F8), which the bar says: held, it carries on when the
     * user leaves; not held, it may not.
     */
    fun hold(held: Boolean) {
        this.held = held
        (state.value as? Transfer.Running)?.let { mutableState.value = it.copy(held = held) }
    }

    /** Copies the files at [uris] into [folderId]; false, and nothing done, while another copy runs. */
    fun copyIn(
        folderId: String,
        uris: List<Uri>,
    ): Boolean =
        start(Transfer.Running(Direction.IN, 0, uris.size, 0, null)) {
            runIn(folderId, uris)
        }

    /** Copies [document] to [destination], a file the system's picker made; false while another copy runs. */
    fun copyOut(
        document: EncriDocument,
        destination: Uri,
    ): Boolean =
        start(Transfer.Running(Direction.OUT, 0, 1, 0, document.sizeBytes)) {
            runOut(document, destination)
        }

    /** Stops the copy running at its next block. What it had finished stays; what it was writing goes. */
    fun stop() = stopping.set(true)

    /** Forgets how the last copy ended, once the user has read it. */
    fun dismiss() {
        if (state.value is Transfer.Finished) mutableState.value = null
    }

    private fun start(
        first: Transfer.Running,
        run: suspend () -> Report,
    ): Boolean {
        if (isRunning) return false
        stopping.set(false)
        held = false
        mutableState.value = first
        scope.launch { mutableState.value = Transfer.Finished(run()) }
        return true
    }

    // ---- Copying in ----------------------------------------------------------------------------

    private suspend fun runIn(
        folderId: String,
        uris: List<Uri>,
    ): Report {
        val files = withContext(io) { uris.map(outside::describe) }
        val sizes = files.map(Picked::sizeBytes)
        val progress = Progress(Direction.IN, files.size, sizes.takeIf { null !in it }?.sumOf { it ?: 0L })
        progress.show()
        return when (val begun = begin(folderId, files)) {
            is Begun.Refused -> begun.report
            is Begun.Ready -> copyAll(folderId, files, begun.taken, progress)
        }
    }

    /** What a copy in starts from: the names already in the folder, or why nothing will be copied. */
    private sealed interface Begun {
        class Ready(
            val taken: MutableSet<String>,
        ) : Begun

        class Refused(
            val report: Report,
        ) : Begun
    }

    /** Lists the folder, and refuses the whole copy when the vault has no room for what is known of it. */
    private suspend fun begin(
        folderId: String,
        files: List<Picked>,
    ): Begun {
        val taken =
            when (val listed = link.ask { it.list(folderId) }) {
                is EncriResult.Ok -> listed.value.mapTo(HashSet(), EncriDocument::name)
                is EncriResult.Failed -> return Begun.Refused(everyFile(files, whyOf(listed.failure)))
            }
        val needed = files.sumOf { it.sizeBytes ?: 0L }
        val usable = (link.ask { it.usableBytes() } as? EncriResult.Ok)?.value
        return if (usable != null && needed > usable) {
            Begun.Refused(Report.NoRoom(needed, usable))
        } else {
            Begun.Ready(taken)
        }
    }

    private fun everyFile(
        files: List<Picked>,
        why: Why,
    ): Report = Report.In(0, files.map { NotCopied(it.name, why) }, 0)

    private suspend fun copyAll(
        folderId: String,
        files: List<Picked>,
        taken: MutableSet<String>,
        progress: Progress,
    ): Report {
        val outcome = InOutcome()
        var ended: Why? = null
        for (file in files) {
            val why =
                ended ?: notTried(file, taken) ?: copyOne(folderId, file, requireNotNull(file.name), progress, outcome)
            if (why == null) {
                outcome.copied++
                taken += requireNotNull(file.name)
            } else {
                outcome.notCopied += NotCopied(file.name, why)
                if (why in ENDS_A_COPY_IN) ended = why
            }
            progress.fileDone(file.sizeBytes)
        }
        return Report.In(outcome.copied, outcome.notCopied, outcome.leftUnfinished)
    }

    /** Why [file] is not even tried, or null to try it. */
    private fun notTried(
        file: Picked,
        taken: Set<String>,
    ): Why? =
        when {
            stopping.get() -> Why.STOPPED
            file.name == null -> Why.NO_NAME
            file.name in taken -> Why.NAME_TAKEN
            else -> null
        }

    /** Copies one file in; null when it arrived whole under its own name. */
    private suspend fun copyOne(
        folderId: String,
        file: Picked,
        name: String,
        progress: Progress,
        outcome: InOutcome,
    ): Why? =
        when (
            val created =
                link.ask { it.createFile(folderId, UNFINISHED + name, file.mimeType, NameConflict.PICK_FREE_NAME) }
        ) {
            is EncriResult.Failed -> whyOf(created.failure)
            is EncriResult.Ok -> fill(created.value, file, name, progress, outcome)
        }

    /** Writes [file] into [document], made for it under its unfinished name, and gives it its own name. */
    private suspend fun fill(
        document: EncriDocument,
        file: Picked,
        name: String,
        progress: Progress,
        outcome: InOutcome,
    ): Why? {
        var ending: Why? = null
        val stoppedBy: (Why) -> Unit = { ending = it }
        val written =
            link.ask { session ->
                session.write(document.id) { out -> pourIn(file.uri, out, progress, stoppedBy) }
            }
        if (written is EncriResult.Failed) return discard(document, ending ?: whyOf(written.failure), outcome)
        return when (val renamed = link.ask { it.rename(document.id, name) }) {
            is EncriResult.Ok -> {
                // After the rename, which encri stamps with the time it happened. A date not kept is not worth
                // failing a whole copy over: the file is in the vault, whole, under its own name.
                file.modifiedMillis?.let { modified -> link.ask { it.setModified(document.id, modified) } }
                null
            }

            is EncriResult.Failed -> {
                // Taken since the folder was listed: the copy goes, and the file is reported as every
                // taken name is. Anything else leaves a whole copy under the unfinished name.
                if (renamed.failure == Failure.NAME_TAKEN) {
                    discard(document, Why.NAME_TAKEN, outcome)
                } else {
                    outcome.leftUnfinished++
                    whyOf(renamed.failure)
                }
            }
        }
    }

    /** Pours [uri]'s bytes into [out], a block at a time; [stoppedBy] hears why, before it throws, when it stops. */
    @Suppress("TooGenericExceptionCaught") // A provider refuses a read with whatever it likes; all are its failure.
    private fun pourIn(
        uri: Uri,
        out: OutputStream,
        progress: Progress,
        stoppedBy: (Why) -> Unit,
    ) {
        val input =
            try {
                outside.openInput(uri)
            } catch (refused: Exception) {
                abandon(Why.UNREADABLE, refused, stoppedBy)
            }
        input.use {
            val buffer = ByteArray(PIECE)
            while (true) {
                if (stopping.get()) abandon(Why.STOPPED, null, stoppedBy)
                val read =
                    try {
                        input.read(buffer)
                    } catch (refused: Exception) {
                        abandon(Why.UNREADABLE, refused, stoppedBy)
                    }
                if (read < 0) return
                // A failure here is encri refusing the write, which the client answers with encri's reason.
                out.write(buffer, 0, read)
                progress.add(read)
            }
        }
    }

    /**
     * Abandons the write being made, which encri then saves nothing of: a writer that returns is a writer that
     * finished, so stopping one means throwing out of it (encri-api L11). [stoppedBy] hears why first.
     */
    private fun abandon(
        why: Why,
        cause: Throwable?,
        stoppedBy: (Why) -> Unit,
    ): Nothing {
        stoppedBy(why)
        throw Abandoned(cause)
    }

    /** Deletes a copy that will not be kept; one that cannot be deleted is left with its unfinished name. */
    private suspend fun discard(
        document: EncriDocument,
        why: Why,
        outcome: InOutcome,
    ): Why {
        if (link.ask { it.delete(document.id) } is EncriResult.Failed) outcome.leftUnfinished++
        return why
    }

    // ---- Copying out ---------------------------------------------------------------------------

    private suspend fun runOut(
        document: EncriDocument,
        destination: Uri,
    ): Report {
        val progress = Progress(Direction.OUT, 1, document.sizeBytes)
        val input =
            when (val opened = link.ask { it.openRead(document.id) }) {
                is EncriResult.Ok -> opened.value
                is EncriResult.Failed -> return Report.Out(document.name, whyOf(opened.failure))
            }
        val poured = withContext(io) { input.use { pourOut(it, destination, progress) } }
        val why =
            when (poured) {
                is Poured.Stopped -> {
                    poured.why
                }

                is Poured.Whole -> {
                    val kept = keptLength(destination, poured.bytes)
                    if (kept != null && kept < poured.bytes) Why.SHORT else null
                }
            }
        progress.fileDone(document.sizeBytes)
        return Report.Out(document.name, why)
    }

    /**
     * Pours the vault's [input] into [destination], a block at a time. A block is 64 KiB, so no single write
     * is larger than a FUSE request that can cross an AppFuse bridge safely, which a destination served by
     * another app's provider may be behind (encri D88).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A refusal is the destination failing, said so.
    private fun pourOut(
        input: InputStream,
        destination: Uri,
        progress: Progress,
    ): Poured {
        val output =
            try {
                outside.openOutput(destination)
            } catch (refused: Exception) {
                return Poured.Stopped(Why.DESTINATION)
            }
        var bytes = 0L
        var closed = false
        val stopped =
            try {
                pourBlocks(input, output, progress) { bytes += it }
            } finally {
                // Closing is when an app that keeps a file of its own records what arrived, so a close that
                // fails is the destination failing.
                closed = runCatching { output.close() }.isSuccess
            }
        return when {
            stopped != null -> Poured.Stopped(stopped)
            !closed -> Poured.Stopped(Why.DESTINATION)
            else -> Poured.Whole(bytes)
        }
    }

    // The destination's failures, whatever they are, and the vault's, each said as which side failed.
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")
    private fun pourBlocks(
        input: InputStream,
        output: OutputStream,
        progress: Progress,
        counted: (Int) -> Unit,
    ): Why? {
        val buffer = ByteArray(PIECE)
        while (true) {
            if (stopping.get()) return Why.STOPPED
            val read =
                try {
                    input.read(buffer)
                } catch (stopped: IOException) {
                    return Why.VAULT_STOPPED
                }
            if (read < 0) return null
            try {
                output.write(buffer, 0, read)
            } catch (refused: Exception) {
                return Why.DESTINATION
            }
            counted(read)
            progress.add(read)
        }
    }

    /**
     * The length [destination] reports once it reaches [expected], or the last it reported in about three
     * seconds (encri B102): cloud apps answer 0 straight after the close and the whole length a moment later,
     * so a short first answer is asked again before it is believed.
     */
    private suspend fun keptLength(
        destination: Uri,
        expected: Long,
    ): Long? {
        var reported = withContext(io) { outside.keptLength(destination) }
        var attempt = 1
        while (reported != null && reported < expected && attempt < LENGTH_ATTEMPTS) {
            delay(pauseMillis)
            reported = withContext(io) { outside.keptLength(destination) }
            attempt++
        }
        return reported
    }

    private sealed interface Poured {
        data class Whole(
            val bytes: Long,
        ) : Poured

        data class Stopped(
            val why: Why,
        ) : Poured
    }

    // ---- What both share -----------------------------------------------------------------------

    private class InOutcome {
        var copied = 0
        val notCopied = mutableListOf<NotCopied>()
        var leftUnfinished = 0
    }

    /** How far a copy has got, told to [state] at most once a mebibyte and at the end of every file. */
    private inner class Progress(
        private val direction: Direction,
        private val total: Int,
        private val bytesTotal: Long?,
    ) {
        private var done = 0
        private var bytes = 0L
        private var fileStart = 0L
        private var told = 0L

        fun add(read: Int) {
            bytes += read
            if (bytes - told >= TELL_EVERY_BYTES) show()
        }

        /** A file finished, whether or not it was copied: the bar moves past all of it. */
        fun fileDone(sizeBytes: Long?) {
            done++
            bytes = fileStart + (sizeBytes ?: (bytes - fileStart))
            fileStart = bytes
            show()
        }

        fun show() {
            told = bytes
            mutableState.value = Transfer.Running(direction, done, total, bytes, bytesTotal, held)
        }
    }

    /** Thrown out of a writer to abandon its write, which encri then saves nothing of. */
    private class Abandoned(
        cause: Throwable?,
    ) : RuntimeException(cause)

    companion object {
        /** The name a file is copied in under until it is whole, as encri's share screen names one (encri D118). */
        const val UNFINISHED = "(unfinished) "

        /** A block: encri's own (encri D2), and what a pipe's buffer holds. */
        private const val PIECE = 64 * 1024

        private const val TELL_EVERY_BYTES = 1L shl 20
        private const val LENGTH_PAUSE_MILLIS = 250L
        private const val LENGTH_ATTEMPTS = 13

        /** Endings after which the rest of a copy in would fail the same way, so it is not tried. */
        val ENDS_A_COPY_IN =
            setOf(Why.NO_SPACE, Why.LOCKED, Why.NOT_ALLOWED, Why.GONE, Why.ACCESS_OFF, Why.ENDED, Why.STOPPED)

        /** What encri's refusal means for a copy. */
        fun whyOf(failure: Failure): Why =
            when (failure) {
                Failure.NAME_TAKEN -> Why.NAME_TAKEN
                Failure.INVALID -> Why.BAD_NAME
                Failure.NO_SPACE -> Why.NO_SPACE
                Failure.LOCKED -> Why.LOCKED
                Failure.NOT_AUTHORIZED -> Why.NOT_ALLOWED
                Failure.NOT_FOUND -> Why.GONE
                Failure.ACCESS_OFF -> Why.ACCESS_OFF
                Failure.ENDED, Failure.DISCONNECTED -> Why.ENDED
                Failure.UNSUPPORTED, Failure.FAILED, Failure.CANCELLED -> Why.FAILED
            }
    }
}
