package com.puretv.twitch.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.puretv.twitch.desktop.ui.WindowDestination
import com.puretv.twitch.desktop.ui.openInNewWindow
import com.puretv.twitch.desktop.ui.theme.PureTvTheme

/** One line of a context menu. */
data class MenuAction(val label: String, val onClick: () -> Unit)

/** A menu waiting to be shown at [position] (window pixels). [maxRight] keeps it left of that x, when set. */
data class MenuRequest(val position: Offset, val actions: List<MenuAction>, val maxRight: Float? = null)

/**
 * Where the one context menu of a window is drawn. The window's root shows [request] with
 * [ContextMenuPopup]; anything below it asks with [show] (see [contextMenu]). One menu per
 * window means opening a second closes the first, and no card needs to own a popup.
 */
@Stable
class ContextMenuHost {
    var request by mutableStateOf<MenuRequest?>(null)
        private set

    fun show(request: MenuRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

val LocalContextMenuHost = compositionLocalOf<ContextMenuHost?> { null }

/**
 * Right-click shows a small menu of [actions]; middle-click runs [onMiddleClick] (if any). Both
 * are taken before the card's own click, so they never also open the card. Does nothing
 * outside a window that provides a [ContextMenuHost].
 *
 * [keepLeftOf] limits how far right the menu may reach, in dp from the window's left edge,
 * for rows beside a video: a menu must not slide under the native video surface.
 */
fun Modifier.contextMenu(
    actions: () -> List<MenuAction>,
    onMiddleClick: (() -> Unit)? = null,
    keepLeftOf: Int? = null,
): Modifier = composed {
    val host = LocalContextMenuHost.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latestActions by rememberUpdatedState(actions)
    val latestMiddle by rememberUpdatedState(onMiddleClick)
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .pointerInput(host) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val change = event.changes.firstOrNull() ?: continue
                    when {
                        event.buttons.isSecondaryPressed && host != null -> {
                            val shown = latestActions()
                            if (shown.isNotEmpty()) {
                                host.show(
                                    MenuRequest(origin + change.position, shown, keepLeftOf?.let { with(density) { it.dp.toPx() } }),
                                )
                                event.changes.forEach { it.consume() }
                            }
                        }
                        event.buttons.isTertiaryPressed && latestMiddle != null -> {
                            latestMiddle?.invoke()
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
}

/**
 * "Open in new window" for a card or row: right-click offers [primary] (and [secondary], for
 * example a stream card also offers the channel page), middle-click opens [primary] at once.
 * Ctrl+click is handled where the card's click lands (see App), so every card has it.
 */
fun Modifier.openInNewWindowMenu(
    primary: WindowDestination,
    primaryLabel: String = "Open in new window",
    secondary: WindowDestination? = null,
    secondaryLabel: String = "Open channel in new window",
    keepLeftOf: Int? = null,
): Modifier = contextMenu(
    actions = {
        buildList {
            add(MenuAction(primaryLabel) { openInNewWindow(primary) })
            if (secondary != null) add(MenuAction(secondaryLabel) { openInNewWindow(secondary) })
        }
    },
    onMiddleClick = { openInNewWindow(primary) },
    keepLeftOf = keepLeftOf,
)

/** A channel's card or row: a live one offers its stream first, with the channel page second. */
fun Modifier.channelWindowMenu(login: String, live: Boolean, keepLeftOf: Int? = null): Modifier =
    if (live) {
        openInNewWindowMenu(
            primary = WindowDestination.Stream(login),
            primaryLabel = "Watch in new window",
            secondary = WindowDestination.Channel(login),
            keepLeftOf = keepLeftOf,
        )
    } else {
        openInNewWindowMenu(primary = WindowDestination.Channel(login), keepLeftOf = keepLeftOf)
    }

/** Draws the host's menu, if one is asked for. Call once, near the root of a window, below everything else. */
@Composable
fun ContextMenuPopup(host: ContextMenuHost) {
    val request = host.request ?: return
    val c = PureTvTheme.colors
    val shapes = PureTvTheme.shapes
    val provider = remember(request) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                var x = request.position.x.toInt()
                var y = request.position.y.toInt()
                val right = request.maxRight?.toInt() ?: windowSize.width
                if (x + popupContentSize.width > right) x = right - popupContentSize.width
                if (y + popupContentSize.height > windowSize.height) y = windowSize.height - popupContentSize.height
                return IntOffset(x.coerceAtLeast(0), y.coerceAtLeast(0))
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { host.dismiss() },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .width(MENU_WIDTH)
                .padding(4.dp)
                .background(c.surfaceHighest, shapes.cardShape)
                .padding(vertical = 6.dp, horizontal = 6.dp),
        ) {
            request.actions.forEach { action ->
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .expressiveClickable(
                            interaction = interaction,
                            onClick = {
                                host.dismiss()
                                action.onClick()
                            },
                            restRadius = 12.dp,
                            hoverRadius = 8.dp,
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            hoverColor = c.surfaceHigh,
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val MENU_WIDTH = 220.dp
