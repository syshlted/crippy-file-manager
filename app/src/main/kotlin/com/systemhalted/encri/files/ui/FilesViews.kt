package com.systemhalted.encri.files.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.browse.Previews
import com.systemhalted.encri.files.transfer.Transfer

/**
 * The browsing screen's views, built in code as encri's screens are, with no layout and no AndroidX
 * (AGENTS.md F1). They draw a [Screen] and report what was tapped; what any of it means is the Activity's.
 */
class FilesViews(
    private val activity: Activity,
    private val actions: Actions,
    previews: Previews,
) {
    interface Actions {
        fun onRemedy(remedy: Remedy)

        fun onDocument(document: EncriDocument)

        /** A long press: the changes a document offers (F5). */
        fun onDocumentHeld(document: EncriDocument)

        fun onMoveHere()

        fun onMoveCancelled()

        /** The copy bar's one button: Stop while a copy runs, and OK once it has ended (F6). */
        fun onTransferButton()
    }

    private val message = TextView(activity)
    private val remedyButton = Button(activity)
    private val adapter = DocumentAdapter(activity, previews)
    private val list = ListView(activity)
    private val movingText = TextView(activity)
    private val moveHere = Button(activity).apply { setText(R.string.move_here) }

    /** Shown at the foot of the screen while a document is being moved (F5). */
    private val movingBar =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val buttons =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val cancel =
                        Button(activity).apply {
                            setText(android.R.string.cancel)
                            setOnClickListener { actions.onMoveCancelled() }
                        }
                    moveHere.setOnClickListener { actions.onMoveHere() }
                    addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(moveHere, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            addView(movingText)
            addView(buttons)
        }

    private val transferText = TextView(activity)
    private val transferProgress =
        ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = TransferWords.PROGRESS_MAX
        }
    private val transferButton = Button(activity).apply { setOnClickListener { actions.onTransferButton() } }

    /** Shown at the foot of the screen while a copy runs, and until the user has read how it ended (F6). */
    private val transferBar =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(transferText)
            addView(transferProgress)
            addView(transferButton)
        }

    /** The remedy [remedyButton] offers, so a tap reports the one that was drawn. */
    private var remedy: Remedy? = null

    val root: View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            padForSystemBars(pad)
            remedyButton.setOnClickListener { remedy?.let(actions::onRemedy) }
            list.adapter = adapter
            list.setRecyclerListener(adapter::recycled)
            list.setOnItemClickListener { _, _, position, _ -> actions.onDocument(adapter.getItem(position)) }
            list.setOnItemLongClickListener { _, _, position, _ ->
                actions.onDocumentHeld(adapter.getItem(position))
                true
            }
            addView(message)
            addView(remedyButton)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(movingBar)
            addView(transferBar)
        }

    @Suppress("SpreadOperator") // A message has one argument at most; the copy costs nothing.
    fun render(screen: Screen) {
        message.visibility = if (screen.message == null) View.GONE else View.VISIBLE
        screen.message?.let { message.text = activity.getString(it, *screen.messageArgs.toTypedArray()) }
        remedy = screen.remedy
        remedyButton.visibility = if (screen.remedy == null) View.GONE else View.VISIBLE
        screen.remedy?.let { remedyButton.setText(labelOf(it)) }
        adapter.show(screen.documents)
        movingBar.visibility = if (screen.moving == null) View.GONE else View.VISIBLE
        screen.moving?.let { movingText.text = activity.getString(R.string.moving, it) }
        moveHere.isEnabled = screen.canMoveHere
        renderTransfer(screen.transfer, screen.namesShown)
    }

    private fun renderTransfer(
        transfer: Transfer?,
        namesShown: Boolean,
    ) {
        transferBar.visibility = if (transfer == null) View.GONE else View.VISIBLE
        if (transfer == null) return
        transferText.text = TransferWords.of(activity, transfer, namesShown)
        val running = transfer as? Transfer.Running
        transferProgress.visibility = if (running == null) View.GONE else View.VISIBLE
        if (running != null) {
            val progress = TransferWords.progressOf(running)
            transferProgress.isIndeterminate = progress == null
            progress?.let { transferProgress.progress = it }
        }
        transferButton.setText(if (running == null) android.R.string.ok else R.string.transfer_stop)
    }

    /** Draws every row again: whether previews are shown changed. */
    fun refreshRows() = adapter.notifyDataSetChanged()

    private fun labelOf(remedy: Remedy): Int =
        when (remedy) {
            Remedy.ASK -> R.string.remedy_ask
            Remedy.UNLOCK -> R.string.remedy_unlock
            Remedy.RETRY -> R.string.remedy_retry
        }

    private companion object {
        const val PADDING_DP = 16
    }
}
