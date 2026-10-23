package com.systemhalted.encri.files.ui

import android.app.Activity
import android.app.AlertDialog
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.settings.Settings

/**
 * What leaves the vault from a document's long press: a share (AGENTS.md F7) and a copy out (F6).
 *
 * Plaintext handed to another app is plaintext gone (encri's invariant 2), so neither starts until the user has
 * read what it costs, every time; [copyOut] and [share] are only called once they have agreed.
 */
class Outgoing(
    private val activity: Activity,
    private val settings: Settings,
    /** Asks the system's picker where a copy of the document goes. */
    private val copyOut: (EncriDocument) -> Unit,
    /** Hands the document to the share sheet. */
    private val share: (EncriDocument) -> Unit,
) {
    /**
     * Says, before the share sheet opens, that the app chosen gets the plaintext and may keep it, and that it reads
     * through a handoff that ends with the vault's lock or this app's session (encri D117, D118).
     */
    fun confirmShare(document: EncriDocument) =
        AlertDialog
            .Builder(activity)
            .setTitle(activity.getString(R.string.share_title, document.name))
            .setMessage(R.string.share_explain)
            .setPositiveButton(R.string.action_share) { _, _ -> share(document) }
            .setNegativeButton(android.R.string.cancel, null)
            .showFollowing(settings)

    /**
     * Says, before anything leaves, that the copy is plaintext in another app's keeping (encri D123), and only then
     * asks the picker where it goes.
     */
    fun confirmCopyOut(document: EncriDocument) =
        AlertDialog
            .Builder(activity)
            .setTitle(activity.getString(R.string.copy_out_title, document.name))
            .setMessage(R.string.copy_out_explain)
            .setPositiveButton(R.string.copy_out_choose) { _, _ -> copyOut(document) }
            .setNegativeButton(android.R.string.cancel, null)
            .showFollowing(settings)
}
