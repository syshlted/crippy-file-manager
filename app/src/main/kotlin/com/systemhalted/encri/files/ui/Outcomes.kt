package com.systemhalted.encri.files.ui

import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.R

/** A change the user asked encri to make (F5). */
enum class Operation {
    CREATE,
    RENAME,
    MOVE,
    DELETE,
}

/**
 * What to tell the user when encri refuses a change, in their terms rather than encri's (F5). encri's own
 * message is for a developer (encri-api's `PROTOCOL.md`), so it is never shown. A pure function, so each
 * answer has a test that needs no phone.
 */
object Outcomes {
    fun titleOf(operation: Operation): Int =
        when (operation) {
            Operation.CREATE -> R.string.failed_create
            Operation.RENAME -> R.string.failed_rename
            Operation.MOVE -> R.string.failed_move
            Operation.DELETE -> R.string.failed_delete
        }

    fun reasonOf(
        operation: Operation,
        failure: Failure,
    ): Int =
        when (failure) {
            Failure.NAME_TAKEN -> R.string.reason_name_taken
            Failure.INVALID -> invalid(operation)
            Failure.NOT_AUTHORIZED -> R.string.reason_not_allowed
            Failure.NOT_FOUND -> R.string.reason_gone
            Failure.LOCKED -> R.string.locked
            Failure.NO_SPACE -> R.string.reason_no_space
            Failure.ACCESS_OFF -> R.string.reason_access_off
            Failure.ENDED, Failure.DISCONNECTED -> R.string.reason_ended
            else -> R.string.reason_failed
        }

    /** encri calls a request it will not take invalid, and which request says why it would not. */
    private fun invalid(operation: Operation): Int =
        when (operation) {
            Operation.CREATE, Operation.RENAME -> R.string.reason_bad_name
            Operation.MOVE -> R.string.reason_bad_move
            Operation.DELETE -> R.string.reason_bad_delete
        }
}
