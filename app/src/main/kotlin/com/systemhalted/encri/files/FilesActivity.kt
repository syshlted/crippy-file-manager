package com.systemhalted.encri.files

import android.annotation.TargetApi
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.systemhalted.encri.files.ui.Edits
import com.systemhalted.encri.files.ui.FilesViews
import com.systemhalted.encri.files.ui.Remedy
import com.systemhalted.encri.files.ui.Screen
import com.systemhalted.encri.files.ui.Viewer
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
 */
@Suppress("TooManyFunctions") // Eight are the platform's lifecycle, menu, result and back callbacks.
class FilesActivity :
    Activity(),
    FilesViews.Actions {
    private val ui = MainScope()
    private lateinit var vaults: Vaults
    private lateinit var views: FilesViews
    private lateinit var viewer: Viewer
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
        viewer = Viewer(this, vaults.link)
        edits = Edits(this, vaults, settings, ui)
        setContentView(views.root)
        ui.launch {
            combine(
                vaults.link.state,
                vaults.browser.path,
                vaults.browser.listing,
                vaults.browser.moving,
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
        this.screen = screen
        views.render(screen)
        title = screen.title ?: getString(R.string.app_name)
        actionBar?.setDisplayHomeAsUpEnabled(screen.canGoUp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) followBack(screen.canGoUp)
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_NEW_FOLDER, Menu.NONE, R.string.new_folder)
        menu.add(Menu.NONE, MENU_MORE_RIGHTS, Menu.NONE, R.string.menu_more_rights)
        menu.add(Menu.NONE, MENU_RELOAD, Menu.NONE, R.string.menu_reload)
        menu.add(Menu.NONE, MENU_LOCK, Menu.NONE, R.string.menu_lock)
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.settings)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_NEW_FOLDER).isVisible = Right.CREATE in screen.rights && screen.moving == null
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

    @Deprecated("A framework Activity's only way to hear a result; this app has no AndroidX.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode == REQUEST_AUTHORIZE && Encri.authorizationResult(resultCode, data) == null) {
            Toast.makeText(this, R.string.not_let_in, Toast.LENGTH_SHORT).show()
        }
        vaults.link.open()
    }

    override fun onDocument(document: EncriDocument) {
        if (document.isFolder) {
            vaults.browser.enter(document)
        } else {
            ui.launch { viewer.open(document) }
        }
    }

    override fun onDocumentHeld(document: EncriDocument) = edits.offer(document, screen.rights)

    override fun onMoveHere() {
        vaults.browser.path.value
            .lastOrNull()
            ?.let(edits::moveHere)
    }

    override fun onMoveCancelled() = vaults.browser.endMove()

    /**
     * Back goes up a folder before it leaves. From API 33 the platform asks for a callback instead, and
     * from API 36 for an app targeting it this method is never called; below 33 it is the only way.
     */
    @Deprecated("The way back below API 33; followBack is the way from 33.")
    override fun onBackPressed() {
        if (!vaults.browser.up()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /** Registers [upOnBack] while there is a folder to go up to, so back at the top leaves as usual. */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun followBack(canGoUp: Boolean) {
        val held = upOnBack as OnBackInvokedCallback?
        when {
            canGoUp && held == null -> {
                val callback = OnBackInvokedCallback { vaults.browser.up() }
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback,
                )
                upOnBack = callback
            }

            !canGoUp && held != null -> {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(held)
                upOnBack = null
            }
        }
    }

    internal companion object {
        private const val REQUEST_AUTHORIZE = 1
        private const val REQUEST_UNLOCK = 2
        const val MENU_RELOAD = 1
        const val MENU_LOCK = 2
        const val MENU_SETTINGS = 3
        const val MENU_NEW_FOLDER = 4
        const val MENU_MORE_RIGHTS = 5
    }
}
