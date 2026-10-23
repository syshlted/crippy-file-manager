package com.systemhalted.encri.files.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.link.VaultLink

/**
 * Opens a document with another app, through a handoff encri makes for it (encri D117; AGENTS.md F2).
 *
 * The other app is given a `content://` URI on encri's handoff provider, granted to this app and passed
 * on with read permission. It names neither the document's id nor its name, and it stops working when the
 * vault locks or this app's session ends.
 */
class Viewer(
    private val activity: Activity,
    private val link: VaultLink,
) {
    suspend fun open(document: EncriDocument) {
        when (val handed = link.handOff(document.id)) {
            is EncriResult.Ok -> show(handed.value, document.mimeType)
            is EncriResult.Failed -> say(activity.getString(R.string.handoff_failed))
        }
    }

    @Suppress("SwallowedException") // No app for the type is an answer to give the user, not a fault.
    private fun show(
        uri: Uri,
        mimeType: String,
    ) {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (none: ActivityNotFoundException) {
            say(activity.getString(R.string.no_viewer, mimeType))
        }
    }

    private fun say(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
