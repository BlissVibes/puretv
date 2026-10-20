package com.puretv.twitch.core.chat

import com.puretv.twitch.core.model.ChatMessage

/**
 * Pure helpers for Twitch chat moderation + mention state, shared by every app's
 * chat (through [ChatTimeline]) so the tombstoning/highlight rules are written
 * once and unit-testable in isolation (same pattern as [buildSelfEcho]).
 *
 * The list-returning functions are allocation-conservative: they return the SAME
 * list instance when nothing changed, so an unrelated CLEARCHAT/CLEARMSG doesn't
 * churn the Compose state or force a recomposition. The `InPlace` variants serve
 * the batch fold, which already owns a working copy and should not copy again.
 */
object ChatModeration {

    /**
     * Tombstone every message authored by [targetLogin] (CLEARCHAT timeout/ban).
     * Matching is case-insensitive on the IRC login. Already-deleted rows and
     * system rows are left untouched. Returns [messages] unchanged if no row matched.
     */
    fun markUserDeleted(messages: List<ChatMessage>, targetLogin: String): List<ChatMessage> {
        if (targetLogin.isBlank()) return messages
        var changed = false
        val out = messages.map {
            if (isByUser(it, targetLogin)) {
                changed = true
                it.copy(deleted = true)
            } else it
        }
        return if (changed) out else messages
    }

    /** Tombstone the single message with id [targetMessageId] (CLEARMSG). */
    fun markMessageDeleted(messages: List<ChatMessage>, targetMessageId: String): List<ChatMessage> {
        var changed = false
        val out = messages.map {
            if (isMessage(it, targetMessageId)) {
                changed = true
                it.copy(deleted = true)
            } else it
        }
        return if (changed) out else messages
    }

    /** [markUserDeleted] on a list the caller owns. Returns true if any row changed. */
    fun markUserDeletedInPlace(messages: MutableList<ChatMessage>, targetLogin: String): Boolean {
        if (targetLogin.isBlank()) return false
        var changed = false
        for (i in messages.indices) {
            val m = messages[i]
            if (isByUser(m, targetLogin)) {
                messages[i] = m.copy(deleted = true)
                changed = true
            }
        }
        return changed
    }

    /** [markMessageDeleted] on a list the caller owns. Returns true if a row changed. */
    fun markMessageDeletedInPlace(messages: MutableList<ChatMessage>, targetMessageId: String): Boolean {
        var changed = false
        for (i in messages.indices) {
            val m = messages[i]
            if (isMessage(m, targetMessageId)) {
                messages[i] = m.copy(deleted = true)
                changed = true
            }
        }
        return changed
    }

    private fun isByUser(m: ChatMessage, login: String): Boolean =
        !m.deleted && !m.isSystem && m.username.equals(login, ignoreCase = true)

    private fun isMessage(m: ChatMessage, id: String): Boolean = !m.deleted && m.id == id

    /**
     * True when [body] @-mentions the viewer identified by [selfLogin]. Matches the
     * "@login" token case-insensitively and only on a word boundary, so "@bob" pings
     * bob but "@bobby" does not. Blank login (anonymous / pre-USERSTATE) never matches.
     */
    fun mentionsSelf(body: String, selfLogin: String?): Boolean {
        val login = selfLogin?.takeIf { it.isNotBlank() } ?: return false
        val needle = "@$login"
        var from = 0
        while (true) {
            val at = body.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return false
            val after = at + needle.length
            // Word boundary: the char after the login must not continue an identifier
            // (Twitch logins are [a-z0-9_]), else "@bobby" would match login "bob".
            val boundary = after >= body.length || !body[after].isLoginChar()
            if (boundary) return true
            from = at + 1
        }
    }

    private fun Char.isLoginChar(): Boolean = isLetterOrDigit() || this == '_'
}
