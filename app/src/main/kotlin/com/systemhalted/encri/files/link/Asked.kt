package com.systemhalted.encri.files.link

import com.systemhalted.encri.client.Right

/** What this app asks encri to let it do. */
object Asked {
    /**
     * Every right, because this version uses every one: reading, renaming (which encri counts as writing),
     * making folders, deleting and moving (F5). A grant may hold fewer — one made for an earlier version, or
     * one the user narrowed — and the screen then offers to ask for the rest rather than failing.
     */
    val RIGHTS: Set<Right> = setOf(Right.READ, Right.WRITE, Right.CREATE, Right.DELETE, Right.MOVE)
}
