package com.puretv.twitch.desktop.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

// The composer text helpers (wordAtCursor, matchEmotes, completeWord,
// insertAtCursor, @username suggestions) moved to core and are tested there in
// core/src/commonTest/.../chat/ChatComposerTest.kt. Only the desktop key mapping
// is left to test here.
class ChatComposerLogicTest {

    // ── composerKeyAction (Enter-to-send) ─────────────────────────────────────────

    @Test fun enterSendsTheMessage() {
        assertEquals(ComposerKeyAction.SEND, composerKeyAction(isEnter = true, isTab = false, hasSuggestions = false))
    }

    @Test fun enterSendsEvenWhileAutocompleteIsOpen() {
        // Twitch parity: Tab accepts a suggestion, Enter still sends.
        assertEquals(ComposerKeyAction.SEND, composerKeyAction(isEnter = true, isTab = false, hasSuggestions = true))
    }

    @Test fun tabCompletesOnlyWhenSuggestionsExist() {
        assertEquals(ComposerKeyAction.COMPLETE, composerKeyAction(isEnter = false, isTab = true, hasSuggestions = true))
        assertEquals(ComposerKeyAction.NONE, composerKeyAction(isEnter = false, isTab = true, hasSuggestions = false))
    }

    @Test fun otherKeysDoNothingSpecial() {
        assertEquals(ComposerKeyAction.NONE, composerKeyAction(isEnter = false, isTab = false, hasSuggestions = true))
    }
}
