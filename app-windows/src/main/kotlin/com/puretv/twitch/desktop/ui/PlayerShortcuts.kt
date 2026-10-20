package com.puretv.twitch.desktop.ui

import java.awt.event.KeyEvent

/** What a keyboard shortcut on the live player screen does. */
enum class ShortcutAction {
    ToggleFullscreen,
    ToggleTheater,
    ToggleChat,
    TogglePlayPause,
    ExitImmersive,
    ToggleStats,
    FlipNext,
    FlipPrevious,
    VolumeUp,
    VolumeDown,
    ToggleMute,
    CycleSleepTimer,
    ToggleHelp,
}

/**
 * The live player's keyboard table, in one place so it is tested and the shortcuts
 * sheet cannot drift from what the keys do. The screen reads keys at the AWT level
 * (the video surface is a native window that can take focus from Compose) and asks
 * this table what to do.
 *
 * F fullscreen, T theater, C chat, Space play/pause, Esc leaves fullscreen or theater
 * (and closes the shortcuts sheet), F3 upscaling stats (mpv), PageUp/PageDown change
 * channel, Up/Down volume, M mute, Z sleep timer, ? (Shift+/) or F1 this list.
 * Hold X compares the upscaler off and is handled separately because it needs the
 * release event too.
 */
object PlayerShortcuts {
    /** Volume change for one Up or Down press. */
    const val VOLUME_STEP = 5

    /**
     * The action for a key press, or null when it is not ours. [keyChar] is the typed
     * character (layout aware, which is how `?` is found on any keyboard); Ctrl and Alt
     * combinations are left alone so system and menu shortcuts keep working.
     * [immersive] is true in theater or fullscreen, when Esc has something to leave.
     * [helpOpen] lets Esc close the shortcuts sheet first.
     */
    fun actionFor(
        keyCode: Int,
        keyChar: Char,
        ctrlOrAlt: Boolean,
        immersive: Boolean,
        helpOpen: Boolean = false,
        // A text box (chat, emote search) has focus: every key is the viewer typing.
        textInputFocused: Boolean = false,
        // The Playback menu is open: Up and Down belong to its sliders and segments.
        menuOpen: Boolean = false,
        // The key is being held (auto-repeat). Only volume repeats; toggles fire once.
        isRepeat: Boolean = false,
    ): ShortcutAction? {
        if (textInputFocused) return null
        val action = actionForKey(keyCode, keyChar, ctrlOrAlt, immersive, helpOpen) ?: return null
        val volume = action == ShortcutAction.VolumeUp || action == ShortcutAction.VolumeDown
        if (volume && menuOpen) return null
        if (isRepeat && !volume) return null
        return action
    }

    private fun actionForKey(
        keyCode: Int,
        keyChar: Char,
        ctrlOrAlt: Boolean,
        immersive: Boolean,
        helpOpen: Boolean,
    ): ShortcutAction? {
        if (ctrlOrAlt) return null
        if (keyChar == '?') return ShortcutAction.ToggleHelp
        return when (keyCode) {
            KeyEvent.VK_F -> ShortcutAction.ToggleFullscreen
            KeyEvent.VK_T -> ShortcutAction.ToggleTheater
            KeyEvent.VK_C -> ShortcutAction.ToggleChat
            KeyEvent.VK_SPACE -> ShortcutAction.TogglePlayPause
            // Esc only acts when there is something to leave, so a stray Esc elsewhere passes through.
            KeyEvent.VK_ESCAPE -> when {
                helpOpen -> ShortcutAction.ToggleHelp
                immersive -> ShortcutAction.ExitImmersive
                else -> null
            }
            KeyEvent.VK_F3 -> ShortcutAction.ToggleStats
            KeyEvent.VK_F1 -> ShortcutAction.ToggleHelp
            KeyEvent.VK_PAGE_UP -> ShortcutAction.FlipNext
            KeyEvent.VK_PAGE_DOWN -> ShortcutAction.FlipPrevious
            KeyEvent.VK_UP -> ShortcutAction.VolumeUp
            KeyEvent.VK_DOWN -> ShortcutAction.VolumeDown
            KeyEvent.VK_M -> ShortcutAction.ToggleMute
            KeyEvent.VK_Z -> ShortcutAction.CycleSleepTimer
            else -> null
        }
    }

    /** The volume after one Up or Down press from [volume] (0 to 100). */
    fun steppedVolume(volume: Int, up: Boolean): Int =
        (volume + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0, 100)

    /** One row of the shortcuts sheet. */
    data class Help(val keys: String, val what: String)

    val help: List<Help> = listOf(
        Help("Space", "Play or pause"),
        Help("F", "Fullscreen"),
        Help("T", "Theater mode"),
        Help("C", "Show or hide chat"),
        Help("Esc", "Leave fullscreen or theater"),
        Help("Page Up / Page Down", "Next or previous channel"),
        Help("Up / Down", "Volume"),
        Help("M", "Mute"),
        Help("Z", "Sleep timer"),
        Help("Hold X", "Compare with upscaling off (mpv)"),
        Help("F3", "Upscaling stats (mpv)"),
        Help("?", "This list"),
    )
}
