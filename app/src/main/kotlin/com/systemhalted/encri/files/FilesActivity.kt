package com.systemhalted.encri.files

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.systemhalted.encri.client.Encri
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.Right
import com.systemhalted.encri.files.link.Asked
import com.systemhalted.encri.files.settings.Settings
import com.systemhalted.encri.files.settings.followScreenshotSetting
import com.systemhalted.encri.files.transfer.Transfer
import com.systemhalted.encri.files.ui.Edits
import com.systemhalted.encri.files.ui.FilesViews
import com.systemhalted.encri.files.ui.Handoffs
import com.systemhalted.encri.files.ui.Outgoing
import com.systemhalted.encri.files.ui.Remedy
import com.systemhalted.encri.files.ui.Screen
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The vault, a folder at a time (AGENTS.md F2).
 *
 * The screen draws [Screen.of] and turns taps into requests. The session and the user's place are the
 * application's ([Vaults]), so turning the phone round neither ends the session nor loses the place.
 *
 * **Screenshots are blocked unless the user allows them in [SettingsActivity]** (F3), for encri's reason
 * (encri D8): this screen shows the names of what is in the vault, and a screenshot or the recents
 * thumbnail would keep them outside it.
 *
 * **While a copy runs, back at the top sends the app to the background rather than closing it** (F6). A file
 * the system's picker handed over may be read for as long as this app's task is alive, so a copy in that is
 * still reading would lose the rest of its files if the task closed. From API 31 that is what back at the top
 * does anyway; below it, this is what keeps it so.
 */
@Suppress("TooManyFunctions") // Eight are the platform's lifecycle, menu, result and back callbacks.
class FilesActivity :
    Activity(),
    FilesViews.Actions {
    private val ui = MainScope()
    private lateinit var vaults: Vaults
    private lateinit var views: FilesViews
    private lateinit var handoffs: Handoffs
    private lateinit var settings: Settings
    private lateinit var edits: Edits
    private var screen = Screen(R.string.connecting)

    // A callback only from API 33, where predictive back replaced onBackPressed (see onBackPressed).
    private var upOnBack: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.followScreenshotSetting(settings.screenshotsAllowed)
        vaults = application as Vaults
        vaults.previews.enabled = settings.previewsShown
        views = FilesViews(this, this, vaults.previews)
        handoffs = Handoffs(this, vaults.link)
        val outgoing = Outgoing(this, settings, ::chooseWhereToCopy) { ui.launch { handoffs.share(it) } }
        edits = Edits(this, vaults, settings, ui, outgoing)
        setContentView(views.root)
        ui.launch {
            combine(
                vaults.link.state,
                vaults.browser.path,
                vaults.browser.listing,
                vaults.browser.moving,
                vaults.transfers.state,
                Screen::of,
            ).collect(::render)
        }
    }

    /**
     * Asks for a session if there is none, and lists the folder again if there is: encri's notice that a
     * folder changed is best effort (encri-api's PROTOCOL.md), and a screen the user comes back to should
     * not show what was there when they left it.
     */
    override fun onStart() {
        super.onStart()
        vaults.link.open()
        vaults.browser.reload()
    }

    /** The settings may have changed while [SettingsActivity] was in front. */
    override fun onResume() {
        super.onResume()
        window.followScreenshotSetting(settings.screenshotsAllowed)
        if (vaults.previews.enabled != settings.previewsShown) {
            vaults.previews.enabled = settings.previewsShown
            views.refreshRows()
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun render(screen: Screen) {
        // A copy that ended changed the folder, and encri's notice of that is best effort (F2).
        if (this.screen.transfer is Transfer.Running && screen.transfer is Transfer.Finished) vaults.browser.reload()
        this.screen = screen
        views.render(screen)
        title = screen.title ?: getString(R.string.app_name)
        actionBar?.setDisplayHomeAsUpEnabled(screen.canGoUp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) followBack(screen.canGoUp || !screen.canStartCopy)
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_NEW_FOLDER, Menu.NONE, R.string.new_folder)
        menu.add(Menu.NONE, MENU_COPY_IN, Menu.NONE, R.string.copy_in)
        menu.add(Menu.NONE, MENU_MORE_RIGHTS, Menu.NONE, R.string.menu_more_rights)
        menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.menu_reload)
        menu.add(Menu.NONE, MENU_LOCK, Menu.NONE, R.string.menu_lock)
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.settings)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_NEW_FOLDER).isVisible = Right.CREATE in screen.rights && screen.moving == null
        menu.findItem(MENU_COPY_IN).isVisible = screen.canCopyIn
        menu.findItem(MENU_MORE_RIGHTS).isVisible = screen.wantsMoreRights
        menu.findItem(MENU_RELOAD).isVisible = screen.canReload
        menu.findItem(MENU_LOCK).isVisible = screen.canLock
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                vaults.browser.up()
            }

            MENU_NEW_FOLDER -> {
                vaults.browser.path.value
                    .lastOrNull()
                    ?.let { edits.newFolder(it.id) }
            }

            MENU_COPY_IN -> {
                chooseFilesToCopy()
            }

            MENU_MORE_RIGHTS -> {
                ask(Encri.authorizationIntent(Asked.RIGHTS), REQUEST_AUTHORIZE)
            }

            MENU_RELOAD -> {
                vaults.browser.reload()
            }

            MENU_LOCK -> {
                vaults.link.lock()
            }

            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            else -> {
                return super.onOptionsItemSelected(item)
            }
        }
        return true
    }

    override fun onRemedy(remedy: Remedy) {
        when (remedy) {
            Remedy.ASK -> ask(Encri.authorizationIntent(Asked.RIGHTS), REQUEST_AUTHORIZE)
            Remedy.UNLOCK -> ask(Encri.unlockIntent(), REQUEST_UNLOCK)
            Remedy.RETRY -> vaults.link.open()
        }
    }

    /** Starts one of encri's screens for a result, which is how encri knows who asked (encri D120). */
    @Suppress("SwallowedException") // A missing encri is said by the refusal asking again brings.
    private fun ask(
        intent: Intent,
        request: Int,
    ) {
        try {
            startActivityForResult(intent, request)
        } catch (gone: ActivityNotFoundException) {
            // encri was uninstalled between the refusal and the tap; asking again says so.
            vaults.link.open()
        }
    }

    /** Asks the system's picker for files to copy into the folder being looked at (F6). */
    private fun chooseFilesToCopy() {
        val folder =
            vaults.browser.path.value
                .lastOrNull() ?: return
        vaults.transfers.pendingFolderId = folder.id
        pick(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            REQUEST_COPY_IN,
        )
    }

    /** Asks the system's picker where a copy of [document] goes (F6); the user has read what it costs. */
    private fun chooseWhereToCopy(document: EncriDocument) {
        vaults.transfers.pendingDocument = document
        pick(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(document.mimeType)
                .putExtra(Intent.EXTRA_TITLE, document.name),
            REQUEST_COPY_OUT,
        )
    }

    @Suppress("SwallowedException") // A phone with no picker is an answer to give the user.
    private fun pick(
        intent: Intent,
        request: Int,
    ) {
        try {
            startActivityForResult(intent, request)
        } catch (none: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("A framework Activity's only way to hear a result; this app has no AndroidX.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        when (requestCode) {
            REQUEST_COPY_IN -> {
                copyIn(if (resultCode == RESULT_OK) chosen(data) else emptyList())
            }

            REQUEST_COPY_OUT -> {
                copyOut(data?.data.takeIf { resultCode == RESULT_OK })
            }

            else -> {
                if (requestCode == REQUEST_AUTHORIZE && Encri.authorizationResult(resultCode, data) == null) {
                    Toast.makeText(this, R.string.not_let_in, Toast.LENGTH_SHORT).show()
                }
                vaults.link.open()
            }
        }
    }

    private fun copyIn(uris: List<Uri>) {
        val folderId = vaults.transfers.pendingFolderId
        vaults.transfers.pendingFolderId = null
        if (uris.isEmpty()) return
        // The process that asked is gone, and with it where the files were to go: they are not guessed at.
        if (folderId == null) {
            Toast.makeText(this, R.string.choose_again, Toast.LENGTH_LONG).show()
        } else {
            vaults.transfers.copyIn(folderId, uris)
        }
    }

    private fun copyOut(destination: Uri?) {
        val document = vaults.transfers.pendingDocument
        vaults.transfers.pendingDocument = null
        if (destination == null) return
        if (document == null) {
            Toast.makeText(this, R.string.choose_again, Toast.LENGTH_LONG).show()
        } else {
            vaults.transfers.copyOut(document, destination)
        }
    }

    override fun onDocument(document: EncriDocument) {
        if (document.isFolder) {
            vaults.browser.enter(document)
        } else {
            ui.launch { handoffs.open(document) }
        }
    }

    override fun onDocumentHeld(document: EncriDocument) = edits.offer(document, screen.rights, screen.canStartCopy)

    override fun onMoveHere() {
        vaults.browser.path.value
            .lastOrNull()
            ?.let(edits::moveHere)
    }

    override fun onMoveCancelled() = vaults.browser.endMove()

    override fun onTransferButton() {
        if (vaults.transfers.isRunning) vaults.transfers.stop() else vaults.transfers.dismiss()
    }

    /** Up a folder; at the top, while a copy runs, to the background rather than closed (see the class). */
    private fun back(): Boolean =
        when {
            vaults.browser.up() -> true
            vaults.transfers.isRunning -> moveTaskToBack(true).let { true }
            else -> false
        }

    /**
     * Back goes up a folder before it leaves. From API 33 the platform asks for a callback instead, and
     * from API 36 for an app targeting it this method is never called; below 33 it is the only way.
     */
    @Deprecated("The way back below API 33; followBack is the way from 33.")
    override fun onBackPressed() {
        if (!back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * Registers [upOnBack] while there is a folder to go up to or a copy running, so back at the top leaves as
     * usual otherwise.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun followBack(handlesBack: Boolean) {
        val held = upOnBack as OnBackInvokedCallback?
        when {
            handlesBack && held == null -> {
                val callback = OnBackInvokedCallback { back() }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback,
                )
                upOnBack = callback
            }

            !handlesBack && held != null -> {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(held)
                upOnBack = null
            }
        }
    }

    internal companion object {
        private const val REQUEST_AUTHORIZE = 1
        private const val REQUEST_UNLOCK = 2
        const val REQUEST_COPY_IN = 3
        const val REQUEST_COPY_OUT = 4
        const val MENU_RELOAD = 1
        const val MENU_LOCK = 2
        const val MENU_SETTINGS = 3
        const val MENU_NEW_FOLDER = 4
        const val MENU_MORE_RIGHTS = 5
        const val MENU_COPY_IN = 6

        /** The files the system's picker handed back: one in [Intent.getData], or several in its clip. */
        fun chosen(data: Intent?): List<Uri> {
            val clip = data?.clipData
            return if (clip != null) {
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
            } else {
                listOfNotNull(data?.data)
            }
        }
    }
}
