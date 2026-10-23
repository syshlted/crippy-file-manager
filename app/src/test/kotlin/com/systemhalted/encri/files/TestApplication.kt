package com.systemhalted.encri.files

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.systemhalted.encri.files.browse.Browser
import com.systemhalted.encri.files.browse.Previews
import com.systemhalted.encri.files.link.Opener
import com.systemhalted.encri.files.link.Opening
import com.systemhalted.encri.files.link.VaultLink
import com.systemhalted.encri.files.transfer.Transfers
import kotlinx.coroutines.MainScope
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.fakes.RoboMenu
import kotlin.coroutines.EmptyCoroutineContext

/** The application under test: the real link, browser and previews over a stand-in for encri. */
class TestApplication :
    Application(),
    Vaults {
    override lateinit var link: VaultLink
    override lateinit var browser: Browser
    override lateinit var previews: Previews
    override lateinit var transfers: Transfers

    /** The phone's storage the copies reach, which a test fills before it copies. */
    val outside = FakeOutside()

    fun use(opener: Opener) {
        val scope = MainScope()
        link = VaultLink(opener, scope)
        browser = Browser(link, scope)
        previews =
            Previews(link, scope, decodeContext = EmptyCoroutineContext) {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            }
        transfers = Transfers(link, outside, scope, io = EmptyCoroutineContext, pauseMillis = 0)
    }
}

/** Starts the browsing screen over a stand-in that answers each request for a session with the next of [answers]. */
fun start(vararg answers: Opening): ActivityController<FilesActivity> {
    (RuntimeEnvironment.getApplication() as TestApplication).use(FakeOpener(*answers))
    val controller = Robolectric.buildActivity(FilesActivity::class.java).setup()
    settle()
    return controller
}

/** Runs everything the main thread has been handed. */
fun settle() = shadowOf(Looper.getMainLooper()).idle()

fun Activity.body(): ViewGroup = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup

fun Activity.message(): TextView = body().getChildAt(0) as TextView

fun Activity.remedy(): Button = body().getChildAt(1) as Button

fun Activity.list(): ListView = body().getChildAt(2) as ListView

/** The bar shown while a copy runs or until its ending is read: its sentence, a progress bar, and one button. */
fun Activity.transferBar(): ViewGroup = body().getChildAt(4) as ViewGroup

fun Activity.transferText(): String = (transferBar().getChildAt(0) as TextView).text.toString()

fun Activity.transferButton(): Button = transferBar().getChildAt(2) as Button

/** The bar shown while a document is moved: its sentence, then a row of Cancel and Move here. */
fun Activity.movingBar(): ViewGroup = body().getChildAt(3) as ViewGroup

fun Activity.moveHereButton(): Button = (movingBar().getChildAt(1) as ViewGroup).getChildAt(1) as Button

fun Activity.cancelMoveButton(): Button = (movingBar().getChildAt(1) as ViewGroup).getChildAt(0) as Button

fun Activity.rowNames(): List<String> =
    (0 until list().adapter.count).map { position ->
        val row = list().adapter.getView(position, null, list())
        row.findViewById<TextView>(android.R.id.text1).text.toString()
    }

fun Activity.tap(position: Int) {
    list().performItemClick(list().adapter.getView(position, null, list()), position, position.toLong())
    settle()
}

fun Activity.hold(position: Int) {
    val row = list().adapter.getView(position, null, list())
    list().onItemLongClickListener.onItemLongClick(list(), row, position, position.toLong())
    settle()
}

fun Activity.rowImage(position: Int): ImageView {
    val row = list().adapter.getView(position, null, list()) as ViewGroup
    settle()
    return row.getChildAt(0) as ImageView
}

/** The options menu as the screen would prepare it now. */
fun Activity.menu(): RoboMenu =
    RoboMenu(this).also {
        onCreateOptionsMenu(it)
        onPrepareOptionsMenu(it)
    }
