package com.puretv.twitch.core.playback

/** The remote keys the TV player cares about, independent of the UI toolkit. */
enum class RemoteKey {
    Up, Down, Left, Right, Center, Back,
    PlayPause, FastForward, Rewind,
    ChannelUp, ChannelDown, MediaNext, MediaPrevious,
    Menu, Other,
}

/** What the TV player screen should do for a key. */
sealed interface RemoteAction {
    /** Not ours: let Compose move focus (or press the focused control). */
    data object PassThrough : RemoteAction

    /** Ours, but there is nothing to do. */
    data object Ignore : RemoteAction

    data class Flip(val direction: FlipDirection) : RemoteAction

    /** Show the controls with focus on Play/Pause. */
    data object ShowControls : RemoteAction
    data object HideControls : RemoteAction
    data object OpenChat : RemoteAction
    data object CloseChat : RemoteAction
    data object ToggleChat : RemoteAction
    data object TogglePlayPause : RemoteAction

    /** Playback failed: OK asks for a fresh session. */
    data object Retry : RemoteAction
    data class StepQuality(val up: Boolean) : RemoteAction
    data object Leave : RemoteAction
}

/** The part of the screen's state a key decision depends on. */
data class RemoteState(
    val controlsVisible: Boolean,
    val chatVisible: Boolean,
    /** Playback could not start, so OK is the retry key. */
    val hasError: Boolean = false,
    /** Settings > Up and Down change channel. Off, they show the controls instead. */
    val upDownFlips: Boolean = true,
)

/**
 * The TV remote decision table, in one place so it is tested and the screen only obeys it.
 *
 * Controls hidden: Up and Down flip channels (Up is channel up), OK shows the controls,
 * Right opens chat, Left closes it. Controls visible: the D-pad moves focus between the
 * controls (quality, chat, sleep timer) and OK presses the focused one. Channel Up and Down
 * and Media Next and Previous flip whatever is on screen, Play/Pause and fast forward/rewind
 * work everywhere, and Back closes chat, then hides the controls, then leaves.
 */
object RemoteKeys {
    fun decide(key: RemoteKey, state: RemoteState): RemoteAction = when (key) {
        RemoteKey.Back -> when {
            state.chatVisible -> RemoteAction.CloseChat
            state.controlsVisible -> RemoteAction.HideControls
            else -> RemoteAction.Leave
        }
        RemoteKey.ChannelUp, RemoteKey.MediaNext -> RemoteAction.Flip(FlipDirection.NEXT)
        RemoteKey.ChannelDown, RemoteKey.MediaPrevious -> RemoteAction.Flip(FlipDirection.PREVIOUS)
        RemoteKey.PlayPause -> if (state.hasError) RemoteAction.Retry else RemoteAction.TogglePlayPause
        RemoteKey.FastForward -> RemoteAction.StepQuality(up = true)
        RemoteKey.Rewind -> RemoteAction.StepQuality(up = false)
        RemoteKey.Menu -> RemoteAction.ToggleChat
        RemoteKey.Center -> when {
            state.hasError -> RemoteAction.Retry
            state.controlsVisible -> RemoteAction.PassThrough
            else -> RemoteAction.ShowControls
        }
        RemoteKey.Up, RemoteKey.Down -> when {
            state.controlsVisible -> RemoteAction.PassThrough
            state.upDownFlips ->
                RemoteAction.Flip(if (key == RemoteKey.Up) FlipDirection.NEXT else FlipDirection.PREVIOUS)
            else -> RemoteAction.ShowControls
        }
        RemoteKey.Right -> when {
            state.controlsVisible -> RemoteAction.PassThrough
            !state.chatVisible -> RemoteAction.OpenChat
            else -> RemoteAction.Ignore
        }
        RemoteKey.Left -> when {
            state.controlsVisible -> RemoteAction.PassThrough
            state.chatVisible -> RemoteAction.CloseChat
            else -> RemoteAction.Ignore
        }
        RemoteKey.Other -> RemoteAction.PassThrough
    }

    /**
     * Whether pressing [key] counts as the viewer being there: it extends the sleep timer
     * (and, except for Back, restarts the controls' auto-hide, see TvStreamScreen).
     */
    fun isActivity(key: RemoteKey): Boolean = key != RemoteKey.Other
}
