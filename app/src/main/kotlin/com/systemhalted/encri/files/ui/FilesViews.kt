package com.systemhalted.encri.files.ui

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R
import com.systemhalted.encri.files.browse.Previews

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
    }

    private val message = TextView(activity)
    private val remedyButton = Button(activity)
    private val adapter = DocumentAdapter(activity, previews)
    private val list = ListView(activity)

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
            addView(message)
            addView(remedyButton)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    @Suppress("SpreadOperator") // A message has one argument at most; the copy costs nothing.
    fun render(screen: Screen) {
        message.visibility = if (screen.message == null) View.GONE else View.VISIBLE
        screen.message?.let { message.text = activity.getString(it, *screen.messageArgs.toTypedArray()) }
        remedy = screen.remedy
        remedyButton.visibility = if (screen.remedy == null) View.GONE else View.VISIBLE
        screen.remedy?.let { remedyButton.setText(labelOf(it)) }
        adapter.show(screen.documents)
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
