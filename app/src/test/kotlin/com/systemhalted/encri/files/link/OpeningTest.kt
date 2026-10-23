package com.systemhalted.encri.files.link

import com.systemhalted.encri.client.ConnectResult
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.SessionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OpeningTest {
    @Test
    fun `every refusal to connect keeps what the user can be told`() {
        assertEquals(Opening.NotInstalled, Opening.refusalOf(ConnectResult.NotInstalled))
        assertEquals(Opening.NotTrusted("ab12"), Opening.refusalOf(ConnectResult.NotTrusted("ab12")))
        assertEquals(Opening.TooOld(0), Opening.refusalOf(ConnectResult.TooOld(0)))
    }

    @Test
    fun `a reason meant for a developer is not carried to the screen`() {
        assertEquals(Opening.Unavailable, Opening.refusalOf(ConnectResult.Unavailable("bind timed out after 10 s")))
    }

    @Test
    fun `every refusal of a session says which`() {
        assertEquals(Opening.Locked, Opening.refusalOf(SessionOutcome.Locked))
        assertEquals(Opening.NotAuthorized, Opening.refusalOf(SessionOutcome.NotAuthorized))
        assertEquals(Opening.AccessOff, Opening.refusalOf(SessionOutcome.AccessOff))
        assertEquals(Opening.Unavailable, Opening.refusalOf(SessionOutcome.Failed(Failure.DISCONNECTED, "gone")))
    }
}
