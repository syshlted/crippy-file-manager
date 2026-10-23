package com.systemhalted.encri.files

import android.app.Application
import com.systemhalted.encri.files.browse.Browser
import com.systemhalted.encri.files.browse.Previews
import com.systemhalted.encri.files.link.ClientOpener
import com.systemhalted.encri.files.link.VaultLink
import com.systemhalted.encri.files.transfer.ResolverOutside
import com.systemhalted.encri.files.transfer.Transfers
import kotlinx.coroutines.MainScope

/** Where a screen finds the session and the user's place in the vault, which outlive the screen (AGENTS.md F2). */
interface Vaults {
    val link: VaultLink
    val browser: Browser
    val previews: Previews

    /** Copies into and out of the vault, which run on when the screen is left (F6). */
    val transfers: Transfers
}

/** The process's [Vaults], made on first use and kept until the process ends. */
class FilesApplication :
    Application(),
    Vaults {
    private val scope = MainScope()

    override val link: VaultLink by lazy { VaultLink(ClientOpener(this), scope) }

    override val browser: Browser by lazy { Browser(link, scope) }

    override val previews: Previews by lazy { Previews(link, scope) }

    override val transfers: Transfers by lazy { Transfers(link, ResolverOutside(contentResolver), scope) }
}
