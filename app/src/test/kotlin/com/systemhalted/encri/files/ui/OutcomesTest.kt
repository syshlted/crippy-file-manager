package com.systemhalted.encri.files.ui

import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.files.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutcomesTest {
    @Test
    fun `each change has a title of its own`() {
        assertEquals(R.string.failed_create, Outcomes.titleOf(Operation.CREATE))
        assertEquals(R.string.failed_rename, Outcomes.titleOf(Operation.RENAME))
        assertEquals(R.string.failed_move, Outcomes.titleOf(Operation.MOVE))
        assertEquals(R.string.failed_delete, Outcomes.titleOf(Operation.DELETE))
    }

    @Test
    fun `a refusal the user can do something about says what`() {
        assertEquals(R.string.reason_name_taken, Outcomes.reasonOf(Operation.RENAME, Failure.NAME_TAKEN))
        assertEquals(R.string.reason_not_allowed, Outcomes.reasonOf(Operation.DELETE, Failure.NOT_AUTHORIZED))
        assertEquals(R.string.reason_no_space, Outcomes.reasonOf(Operation.CREATE, Failure.NO_SPACE))
        assertEquals(R.string.reason_access_off, Outcomes.reasonOf(Operation.MOVE, Failure.ACCESS_OFF))
        assertEquals(R.string.locked, Outcomes.reasonOf(Operation.MOVE, Failure.LOCKED))
    }

    @Test
    fun `what encri calls invalid depends on what was asked`() {
        assertEquals(R.string.reason_bad_name, Outcomes.reasonOf(Operation.CREATE, Failure.INVALID))
        assertEquals(R.string.reason_bad_name, Outcomes.reasonOf(Operation.RENAME, Failure.INVALID))
        assertEquals(R.string.reason_bad_move, Outcomes.reasonOf(Operation.MOVE, Failure.INVALID))
        assertEquals(R.string.reason_bad_delete, Outcomes.reasonOf(Operation.DELETE, Failure.INVALID))
    }

    @Test
    fun `a document gone, a session ended and anything else each have words`() {
        assertEquals(R.string.reason_gone, Outcomes.reasonOf(Operation.RENAME, Failure.NOT_FOUND))
        assertEquals(R.string.reason_ended, Outcomes.reasonOf(Operation.RENAME, Failure.ENDED))
        assertEquals(R.string.reason_ended, Outcomes.reasonOf(Operation.RENAME, Failure.DISCONNECTED))
        assertEquals(R.string.reason_failed, Outcomes.reasonOf(Operation.RENAME, Failure.FAILED))
    }
}
