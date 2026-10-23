package com.systemhalted.encri.files.transfer

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** A file outside the vault, as the app that keeps it describes it. Anything it will not say is null. */
data class Picked(
    val uri: Uri,
    val name: String?,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedMillis: Long?,
)

/**
 * The phone's storage outside the vault: files the user chose in the system's picker, to copy in, and the
 * place they chose for a copy out (AGENTS.md F6).
 *
 * An interface so the copies run off a phone against a stand-in; [ResolverOutside] is the real one.
 * Everything here blocks, and is called off the main thread.
 */
interface Outside {
    /** What the app keeping [uri] says about it. A file it will not describe is described as unknown. */
    fun describe(uri: Uri): Picked

    fun openInput(uri: Uri): InputStream

    /** A stream that replaces whatever [uri] held. */
    fun openOutput(uri: Uri): OutputStream

    /** How long the app keeping [uri] says it is, or null when it will not say. */
    fun keptLength(uri: Uri): Long?
}
