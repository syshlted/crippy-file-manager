package com.systemhalted.encri.files.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.files.browse.Previews
import kotlinx.coroutines.Job

/**
 * One row per document: a preview where there is one (F4), its name, and what [Rows.detail] says about it.
 *
 * A row asks for its preview when it is drawn and cancels the request when it is drawn for another document
 * or scrolled away ([recycled]), so a long folder flung past asks encri for what is on screen and little
 * else. The slot is there for every row while previews are on, so names line up, and gone while they are
 * off.
 */
class DocumentAdapter(
    private val activity: Activity,
    private val previews: Previews,
) : BaseAdapter() {
    private var documents: List<EncriDocument> = emptyList()
    private var nowMillis = 0L
    private val density = activity.resources.displayMetrics.density

    /** The preview's size in pixels: a square, which encri fits each preview inside. */
    val slotPx = (SLOT_DP * density).toInt()

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
        val row = convertView ?: newRow()
        bind(row.tag as RowViews, documents[position])
        return row
    }

    /** Stops a row's request for a preview, when the list stops showing the row. */
    fun recycled(row: View) {
        (row.tag as? RowViews)?.let { views ->
            views.loading?.cancel()
            views.loading = null
            views.document = null
        }
    }

    private fun bind(
        views: RowViews,
        document: EncriDocument,
    ) {
        views.loading?.cancel()
        views.loading = null
        views.document = document.id
        views.name.text = document.name
        views.detail.text = Rows.detail(activity, document, nowMillis)
        views.image.visibility = if (previews.enabled) View.VISIBLE else View.GONE
        val held = if (previews.shows(document)) previews.held(document, slotPx) else null
        views.image.setImageBitmap(held)
        if (held == null && previews.shows(document)) {
            views.loading =
                previews.load(document, slotPx) { bitmap ->
                    if (views.document == document.id) views.image.setImageBitmap(bitmap)
                }
        }
    }

    private fun newRow(): View {
        val pad = (PAD_DP * density).toInt()
        val image =
            ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(slotPx, slotPx).apply { marginEnd = pad }
            }
        val name =
            TextView(activity).apply {
                id = android.R.id.text1
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            }
        val detail =
            TextView(activity).apply {
                id = android.R.id.text2
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            }
        val text =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(detail)
            }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(image)
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tag = RowViews(image, name, detail)
        }
    }

    /** A row's views, and what it is showing and asking for now. */
    private class RowViews(
        val image: ImageView,
        val name: TextView,
        val detail: TextView,
    ) {
        var document: String? = null
        var loading: Job? = null
    }

    private companion object {
        const val SLOT_DP = 48
        const val PAD_DP = 8
    }
}
