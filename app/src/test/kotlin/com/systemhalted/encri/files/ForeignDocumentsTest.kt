package com.systemhalted.encri.files

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * No source opens another app's file in a way that ties this app's life to that app's (F6, encri's B100).
 *
 * `ContentResolver.openFileDescriptor`, `openInputStream`, `openOutputStream` and the asset forms hand back a
 * descriptor holding a *stable* reference to the provider that served it, and Android kills every holder of one
 * when that provider's process dies. A cloud app stopped mid-copy would then take this app with it, and with it the
 * session and every handoff another app is reading. [com.systemhalted.encri.files.transfer.ResolverOutside] goes
 * through an unstable client instead, and this holds every other source to it. Source text rather than bytecode,
 * as encri's own check is.
 */
class ForeignDocumentsTest {
    @Test
    fun `nothing opens another app's file through the resolver`() {
        val strays =
            sources().flatMap { file ->
                val text = file.readText()
                STABLE_OPEN
                    .findAll(text)
                    .filterNot { isInComment(text, it.range.first) }
                    .map { "${file.path}: ${it.value}" }
                    .toList()
            }
        assertTrue(
            strays.isEmpty(),
            "these hold a stable reference to another app's provider:\n  ${strays.joinToString("\n  ")}",
        )
    }

    @Test
    fun `the scan reaches the one place files are opened`() {
        // A scan that silently stops seeing the sources passes on everything, so name what it must reach.
        val opener = sources().singleOrNull { it.name == "ResolverOutside.kt" }
        assertTrue(opener != null && "acquireUnstableContentProviderClient" in opener.readText())
    }

    private fun isInComment(
        text: String,
        at: Int,
    ): Boolean {
        val line = text.substring(text.lastIndexOf('\n', at) + 1, at).trimStart()
        return line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")
    }

    /** Unit tests run with the module directory as the working directory. */
    private fun sources(): List<File> = File("src/main").walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        /** The resolver's opening calls, allowing the line break ktlint puts before the dot. */
        val STABLE_OPEN =
            Regex(
                """\.\s*open(FileDescriptor|OutputStream|InputStream|AssetFileDescriptor|TypedAssetFileDescriptor)""" +
                    """\s*\(""",
            )
    }
}
