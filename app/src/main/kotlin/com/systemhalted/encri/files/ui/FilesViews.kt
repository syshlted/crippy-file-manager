package com.systemhalted.encri.files.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.R

/**
 * The browsing screen's views, built in code as encri's screens are, with no layout and no AndroidX
 * (AGENTS.md F1). They draw a [Screen] and report what was tapped; what any of it means is the Activity's.
 */
class FilesViews(
    private val activity: Activity,
    private val actions: Actions,
) {
    interface Actions {
        fun onRemedy(remedy: Remedy)

        fun onDocument(document: EncriDocument)
    }

    private val message = TextView(activity)
    private val remedyButton = Button(activity)
    private val adapter = DocumentAdapter()
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

    private fun labelOf(remedy: Remedy): Int =
        when (remedy) {
            Remedy.ASK -> R.string.remedy_ask
            Remedy.UNLOCK -> R.string.remedy_unlock
            Remedy.RETRY -> R.string.remedy_retry
        }

    /** One row per document: its name, and what [Rows.detail] says about it. */
    private inner class DocumentAdapter : BaseAdapter() {
        private var documents: List<EncriDocument> = emptyList()
        private var nowMillis = 0L

        fun show(documents: List<EncriDocument>) {
            this.documents = documents
            nowMillis = System.currentTimeMillis()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = documents.size

        override fun getItem(position: Int): EncriDocument = documents[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val row = convertView ?: activity.layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val document = documents[position]
            row.findViewById<TextView>(android.R.id.text1).text = document.name
            row.findViewById<TextView>(android.R.id.text2).text = Rows.detail(activity, document, nowMillis)
            return row
        }
    }

    private companion object {
        const val PADDING_DP = 16
    }
}
