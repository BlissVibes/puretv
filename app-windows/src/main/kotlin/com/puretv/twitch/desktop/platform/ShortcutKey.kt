package com.puretv.twitch.desktop.platform

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import java.awt.event.KeyEvent

/**
 * The modifier PureTV's own shortcuts use (new window, close window, open in a new
 * window, move a tile): Command on macOS, as every Mac app does, and Ctrl elsewhere.
 */
object ShortcutKey {
    /** "Cmd" or "Ctrl", for hints shown on screen. */
    fun label(os: HostOs = HostOs.current): String = if (os == HostOs.MACOS) "Cmd" else "Ctrl"

    fun isDown(e: KeyEvent, os: HostOs = HostOs.current): Boolean = if (os == HostOs.MACOS) e.isMetaDown else e.isControlDown

    fun isHeld(modifiers: PointerKeyboardModifiers, os: HostOs = HostOs.current): Boolean =
        if (os == HostOs.MACOS) modifiers.isMetaPressed else modifiers.isCtrlPressed
}
