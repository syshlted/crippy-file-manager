package com.systemhalted.encri.files.browse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.EncriResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.link.LinkState
import com.systemhalted.encri.files.link.VaultLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Previews of what is in the vault, kept in memory and nowhere else (AGENTS.md F4, encri D112).
 *
 * encri makes each preview and encodes it; this app decodes encri's encoding and never an original. The
 * decoded pictures live in an [LruCache] for the process and are never written to the phone's storage —
 * the common image libraries keep a disk cache by default, which is why none is used (F1). They are all
 * dropped when the vault locks or the session goes, and when the user turns previews off, after which none
 * is asked for at all.
 */
class Previews(
    private val link: VaultLink,
    private val scope: CoroutineScope,
    budgetBytes: Int = defaultBudget(),
    private val decodeContext: CoroutineContext = Dispatchers.Default,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
) {
    /** Whether previews are shown at all. Turning them off drops every one held. */
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) clear()
        }

    private val cache =
        object : LruCache<String, Bitmap>(budgetBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

    /** Previews encri cannot make, so they are not asked for again until the next [clear]. */
    private val refused = HashSet<String>()
    private val loading = HashSet<Job>()

    /** Bumped by every [clear], so an answer to a request made before it is thrown away. */
    private var generation = 0

    init {
        scope.launch { link.state.collect { state -> if (state !is LinkState.Open || state.locked) clear() } }
    }

    /** Whether [document] gets a preview at all: previews are on, and encri can make one of it. */
    fun shows(document: EncriDocument): Boolean = enabled && document.thumbnailable && !document.isFolder

    /** The preview of [document] at [sizePx], if one is held. */
    fun held(
        document: EncriDocument,
        sizePx: Int,
    ): Bitmap? = cache.get(keyOf(document, sizePx))

    /**
     * Asks encri for [document]'s preview at [sizePx] and hands it to [ready], unless it is held already or
     * cannot be made. The job is the caller's to cancel when the preview is no longer wanted — a row scrolled
     * away — and cancelling it cancels encri's work too.
     */
    fun load(
        document: EncriDocument,
        sizePx: Int,
        ready: (Bitmap) -> Unit,
    ): Job? {
        val key = keyOf(document, sizePx)
        val held = cache.get(key)
        return when {
            !shows(document) || key in refused -> {
                null
            }

            held != null -> {
                ready(held)
                null
            }

            else -> {
                ask(document, sizePx, key, ready)
            }
        }
    }

    private fun ask(
        document: EncriDocument,
        sizePx: Int,
        key: String,
        ready: (Bitmap) -> Unit,
    ): Job {
        val asked = generation
        val job =
            scope.launch {
                val answer = link.thumbnail(document.id, sizePx, sizePx)
                val bitmap = (answer as? EncriResult.Ok)?.value?.let { withContext(decodeContext) { decode(it) } }
                if (asked != generation || !enabled) return@launch
                when {
                    bitmap != null -> {
                        cache.put(key, bitmap)
                        ready(bitmap)
                    }

                    // encri's answer did not decode, which asking again will not change.
                    answer is EncriResult.Ok -> {
                        refused += key
                    }

                    (answer as EncriResult.Failed).failure in FINAL -> {
                        refused += key
                    }
                }
            }
        loading += job
        job.invokeOnCompletion { loading -= job }
        return job
    }

    /** Drops every preview held and every request in flight. */
    fun clear() {
        generation++
        loading.toList().forEach(Job::cancel)
        cache.evictAll()
        refused.clear()
    }

    private companion object {
        /** Failures that will not change by asking again for the same version of the document. */
        val FINAL = setOf(Failure.UNSUPPORTED, Failure.NOT_FOUND, Failure.INVALID)

        // A preview is a few tens of kilobytes decoded; an eighth of the heap, and never more than 16 MiB,
        // holds a few hundred of them.
        const val MAX_BUDGET = 16 * 1024 * 1024
        const val HEAP_SHARE = 8

        fun defaultBudget(): Int = minOf(MAX_BUDGET.toLong(), Runtime.getRuntime().maxMemory() / HEAP_SHARE).toInt()

        /** A document's id, version and the size asked for: a rewritten document is a new preview. */
        fun keyOf(
            document: EncriDocument,
            sizePx: Int,
        ): String = "${document.id}:${document.modifiedMillis}:${document.sizeBytes}:$sizePx"
    }
}
