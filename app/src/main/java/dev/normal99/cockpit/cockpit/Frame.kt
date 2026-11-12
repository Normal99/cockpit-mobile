package dev.normal99.cockpit.cockpit

/**
 * Cockpit's WebSocket framing: every message is "<channel>\n<payload>".
 * The control channel has an empty name, so control messages start with "\n".
 */
object Frame {
    fun encode(channel: String, payload: String): String = channel + "\n" + payload

    fun decode(message: String): Pair<String, String>? {
        val nl = message.indexOf('\n')
        if (nl < 0) return null
        return message.substring(0, nl) to message.substring(nl + 1)
    }
}
