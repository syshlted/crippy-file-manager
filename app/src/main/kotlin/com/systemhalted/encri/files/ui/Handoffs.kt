package com.systemhalted.encri.files.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.systemhalted.encri.client.Encri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.link.VaultLink

/**
 * Hands a document to another app through a handoff encri makes for it (encri D117): to open it (AGENTS.md F2), or
 * through the share sheet (F7).
 *
 * The other app is given a `content://` URI on encri's handoff provider, granted to this app and passed on with
 * read permission. It names neither the document's id nor its name, and it stops working when the vault locks or
 * this app's session ends.
 */
class Handoffs(
    private val activity: Activity,
    private val link: VaultLink,
) {
    /** Opens [document] with whichever app the user has for its type. */
    suspend fun open(document: EncriDocument) {
        when (val handed = link.handOff(document.id)) {
            is EncriResult.Ok -> view(handed.value, document.mimeType)
            is EncriResult.Failed -> say(activity.getString(R.string.handoff_failed))
        }
    }

    /**
     * Shares [document] through the share sheet, once the user has read what that costs (F7). The URI travels in the
     * intent's clip as well as its stream extra, which is what carries the read grant through the share sheet to the
     * app the user picks; the clip's label is empty, so the document's name goes only where the share sheet asks the
     * handoff provider for it.
     *
     * **encri is left out of the share sheet**: its share screen refuses a URI on its own authorities (encri D118),
     * so a document from the vault offered back to it is a choice certain to fail. Its components are asked of the
     * package manager rather than named here, so this holds whatever encri calls them.
     */
    suspend fun share(document: EncriDocument) {
        when (val handed = link.handOff(document.id)) {
            is EncriResult.Ok -> send(handed.value, document.mimeType)
            is EncriResult.Failed -> say(activity.getString(R.string.handoff_failed))
        }
    }

    @Suppress("SwallowedException") // No app for the type is an answer to give the user, not a fault.
    private fun view(
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

    @Suppress("SwallowedException") // A phone with no share sheet is an answer to give the user, not a fault.
    private fun send(
        uri: Uri,
        mimeType: String,
    ) {
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uri)
        val chooser = Intent.createChooser(intent, null)
        val vault = encriTargetsOf(intent)
        if (vault.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, vault.toTypedArray())
        try {
            activity.startActivity(chooser)
        } catch (none: ActivityNotFoundException) {
            say(activity.getString(R.string.no_sharer))
        }
    }

    /** encri's own activities that would take [intent], which a share of encri's document must not offer. */
    @Suppress("DEPRECATION") // The flags overload is API 33; this one answers the same at every level.
    private fun encriTargetsOf(intent: Intent): List<ComponentName> =
        activity.packageManager
            .queryIntentActivities(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName == Encri.PACKAGE }

    private fun say(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
