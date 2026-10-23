package com.systemhalted.encri.files.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.Vaults
import com.systemhalted.encri.files.link.VaultSession
import com.systemhalted.encri.files.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The changes a user makes to the vault from this app: a new folder, a rename, a move and a delete
 * (AGENTS.md F5), and the share (F7) and copy out (F6) a long press offers beside them.
 *
 * Each is one request to encri, which checks the right and the name itself (encri D120), so nothing here
 * decides whether a change is allowed: it offers what the session's rights cover and reports what encri
 * says. A name already taken is refused and never changed into another, so a file is never silently renamed.
 *
 * Every dialog follows the screenshot setting (F3), because a dialog is a window of its own and shows a
 * document's name. A note that names nothing may be a toast; one that names a document never is, because a
 * toast is drawn by the system, outside this app's windows.
 */
class Edits(
    private val activity: Activity,
    private val vaults: Vaults,
    private val settings: Settings,
    private val scope: CoroutineScope,
    /** What leaves the vault from a long press, each said before it happens. */
    private val outgoing: Outgoing,
) {
    /**
     * What [document] offers: a share and, when [canCopy], a copy out, for a document rather than a folder; and as
     * far as [rights] allow, the changes.
     */
    fun offer(
        document: EncriDocument,
        rights: Set<Right>,
        canCopy: Boolean,
    ) {
        val actions =
            buildList {
                if (!document.isFolder) add(R.string.action_share to { outgoing.confirmShare(document) })
                if (canCopy &&
                    !document.isFolder
                ) {
                    add(R.string.action_copy_out to { outgoing.confirmCopyOut(document) })
                }
                if (Right.WRITE in rights) add(R.string.action_rename to { rename(document) })
                if (Right.MOVE in rights) add(R.string.action_move to { vaults.browser.startMove(document) })
                if (Right.DELETE in rights) add(R.string.action_delete to { confirmDelete(document) })
            }
        if (actions.isEmpty()) {
            Toast.makeText(activity, R.string.nothing_allowed, Toast.LENGTH_LONG).show()
            return
        }
        val labels = actions.map { activity.getString(it.first) }.toTypedArray()
        show(
            AlertDialog
                .Builder(activity)
                .setTitle(document.name)
                .setItems(labels) { _, which -> actions[which].second() },
        )
    }

    /** Asks for a name, and makes a folder of it in [parentId]. */
    fun newFolder(parentId: String) =
        askName(Operation.CREATE, R.string.new_folder, "", R.string.action_create) { name ->
            change(Operation.CREATE) { it.createFolder(parentId, name) }
        }

    /** Moves the document being moved into [folder], and stops moving it once encri has. */
    fun moveHere(folder: EncriDocument) {
        val moving = vaults.browser.moving.value ?: return
        change(Operation.MOVE) { it.move(moving.id, folder.id) }
    }

    private fun rename(document: EncriDocument) =
        askName(Operation.RENAME, R.string.rename, document.name, R.string.action_rename) { name ->
            if (name != document.name) change(Operation.RENAME) { it.rename(document.id, name) }
        }

    private fun confirmDelete(document: EncriDocument) {
        val explain = if (document.isFolder) R.string.delete_folder_explain else R.string.delete_document_explain
        show(
            AlertDialog
                .Builder(activity)
                .setTitle(activity.getString(R.string.delete_title, document.name))
                .setMessage(explain)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    change(Operation.DELETE) { it.delete(document.id) }
                }.setNegativeButton(android.R.string.cancel, null),
        )
    }

    /** A dialog with one field, prefilled with [current], which hands a name that is not blank to [done]. */
    private fun askName(
        operation: Operation,
        title: Int,
        current: String,
        confirm: Int,
        done: (String) -> Unit,
    ) {
        val field =
            EditText(activity).apply {
                id = android.R.id.edit
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setText(current)
                // The name without its extension, which is what a rename usually changes.
                setSelection(0, current.lastIndexOf('.').takeIf { it > 0 } ?: current.length)
            }
        val pad = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(activity).apply { setPadding(pad, pad / 2, pad, 0) }
        frame.addView(field)
        show(
            AlertDialog
                .Builder(activity)
                .setTitle(title)
                .setView(frame)
                .setPositiveButton(confirm) { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isEmpty()) report(operation, R.string.reason_bad_name) else done(name)
                }.setNegativeButton(android.R.string.cancel, null),
            typing = true,
        )
        field.requestFocus()
    }

    private fun change(
        operation: Operation,
        request: suspend (VaultSession) -> EncriResult<*>,
    ) {
        scope.launch {
            when (val result = vaults.link.ask(request)) {
                is EncriResult.Ok -> {
                    if (operation == Operation.MOVE) vaults.browser.endMove()
                    vaults.browser.reload()
                }

                is EncriResult.Failed -> {
                    report(operation, Outcomes.reasonOf(operation, result.failure))
                }
            }
        }
    }

    private fun report(
        operation: Operation,
        reason: Int,
    ) = show(
        AlertDialog
            .Builder(activity)
            .setTitle(Outcomes.titleOf(operation))
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null),
    )

    /** Shows [builder]'s dialog under the screenshot setting, with the keyboard up when it asks for [typing]. */
    private fun show(
        builder: AlertDialog.Builder,
        typing: Boolean = false,
    ) = builder.showFollowing(settings, typing)

    private companion object {
        const val PADDING_DP = 24
    }
}
