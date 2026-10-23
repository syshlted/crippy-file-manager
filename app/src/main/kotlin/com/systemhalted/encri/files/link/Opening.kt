package com.systemhalted.encri.files.link

import com.systemhalted.encri.client.ConnectResult
import com.systemhalted.encri.client.SessionOutcome

/**
 * What asking encri for a session came to: a session, or the reason there is none (AGENTS.md F2).
 *
 * The file manager's own type rather than the client's, because a session is the client's
 * [com.systemhalted.encri.client.EncriSession], which only a real encri can make. Behind this type the
 * screens run against a stand-in, and every refusal below is reachable off a phone.
 */
sealed interface Opening {
    /** A session, which lasts until encri ends it. */
    data class Open(
        val session: VaultSession,
    ) : Opening

    /** The vault is locked. encri's unlock screen opens it. */
    data object Locked : Opening

    /** The user has not let this app in, or let in an earlier install or version of it (encri D120). */
    data object NotAuthorized : Opening

    /** Access for other apps is turned off in encri. encri's consent screen offers to turn it on. */
    data object AccessOff : Opening

    data object NotInstalled : Opening

    /** An encri is installed, signed by a key this app does not trust, so nothing is sent to it. */
    data class NotTrusted(
        val certificateSha256: String,
    ) : Opening

    /** The encri installed speaks an older contract than this app needs. */
    data class TooOld(
        val serviceVersion: Int,
    ) : Opening

    /** encri could not be reached, or answered with a failure. Worth trying again. */
    data object Unavailable : Opening

    companion object {
        /**
         * The refusal [result] carries, or null when it is a connection. An unavailable encri's reason is
         * for a developer and is not carried to the screen (encri-api's PROTOCOL.md).
         */
        fun refusalOf(result: ConnectResult): Opening? =
            when (result) {
                is ConnectResult.Connected -> null
                ConnectResult.NotInstalled -> NotInstalled
                is ConnectResult.NotTrusted -> NotTrusted(result.certificateSha256)
                is ConnectResult.TooOld -> TooOld(result.serviceVersion)
                is ConnectResult.Unavailable -> Unavailable
            }

        /** The refusal [outcome] carries, or null when it is a session. */
        fun refusalOf(outcome: SessionOutcome): Opening? =
            when (outcome) {
                is SessionOutcome.Opened -> null
                SessionOutcome.Locked -> Locked
                SessionOutcome.NotAuthorized -> NotAuthorized
                SessionOutcome.AccessOff -> AccessOff
                is SessionOutcome.Failed -> Unavailable
            }
    }
}

/** Asks encri for a session. */
fun interface Opener {
    suspend fun open(): Opening
}
