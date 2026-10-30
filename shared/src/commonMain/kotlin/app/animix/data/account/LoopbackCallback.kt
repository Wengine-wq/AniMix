package app.animix.data.account

/**
 * A one-shot listener on 127.0.0.1 for the sign-in result (RFC 8252 loopback redirect). Desktop
 * signs in through the normal system browser: Google refuses browsers an app controls. Android
 * uses the `animix://` link instead, so there is no listener there.
 */
interface LoopbackCallback {
    /** The address the server sends the browser back to. */
    val returnUri: String

    /** The full callback URL once the browser arrives. */
    suspend fun await(): String

    fun close()
}

expect fun openLoopbackCallback(): LoopbackCallback?
