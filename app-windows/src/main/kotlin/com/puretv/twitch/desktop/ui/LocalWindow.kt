package com.puretv.twitch.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.puretv.twitch.desktop.player.WindowPlayback
import java.awt.Window

/** The player of the window being composed; null means the main window's (the app-wide one). */
val LocalWindowPlayback = staticCompositionLocalOf<WindowPlayback?> { null }

/** The OS window being composed, so key handlers can tell whether a key press is theirs (see [AppWindows]). */
val LocalAwtWindow = staticCompositionLocalOf<Window?> { null }
