package com.puretv.twitch.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.puretv.twitch.core.adblock.AdBlockStatus
import com.puretv.twitch.core.model.AppSettings
import com.puretv.twitch.core.model.ChatTextSize
import com.puretv.twitch.desktop.data.DesktopSettingsStore
import com.puretv.twitch.desktop.ui.LocalAppShell
import com.puretv.twitch.desktop.ui.chat.ChatAppearance
import com.puretv.twitch.desktop.ui.chat.ChatScale
import com.puretv.twitch.desktop.ui.chat.LocalChatAppearance
import com.puretv.twitch.desktop.ui.components.LocalBadgeIndex
import com.puretv.twitch.desktop.ui.theme.PureTvMotion
import com.puretv.twitch.desktop.player.VlcPlayerView
import com.puretv.twitch.desktop.ui.FollowedRailViewModel
import com.puretv.twitch.desktop.ui.MultiTile
import com.puretv.twitch.desktop.ui.MultiViewLayout
import com.puretv.twitch.desktop.ui.MultiViewState
import com.puretv.twitch.desktop.ui.MultiViewUiState
import com.puretv.twitch.desktop.ui.MultiViewViewModel
import com.puretv.twitch.desktop.ui.TileInfo
import com.puretv.twitch.desktop.ui.components.AdBlockPill
import com.puretv.twitch.desktop.ui.components.Avatar
import com.puretv.twitch.desktop.ui.components.ExpressiveButton
import com.puretv.twitch.desktop.ui.components.ExpressiveButtonSize
import com.puretv.twitch.desktop.ui.components.ExpressiveButtonStyle
import com.puretv.twitch.desktop.ui.components.ExpressiveIconButton
import com.puretv.twitch.desktop.ui.components.ExpressiveIcons
import com.puretv.twitch.desktop.ui.components.ExpressiveSlider
import com.puretv.twitch.desktop.ui.components.formatViewerCount
import com.puretv.twitch.desktop.ui.rememberDesktopViewModel
import com.puretv.twitch.desktop.ui.theme.PureTvTheme
import com.puretv.twitch.desktop.ui.theme.PureTvType
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt

private val TILE_GAP = 8.dp
private val TILE_FRAME = 3.dp
private val TILE_STRIP_HEIGHT = 48.dp

/**
 * Watch two to four live channels at once. All of them play; only the active one is
 * audible and outlined, and clicking another tile moves the sound to it.
 *
 * AWT heavyweight components (each tile's VLC canvas) always paint above Compose, so
 * nothing is drawn over a video: the active outline is a padded frame around it, and
 * the channel name, ad-block pill and close button sit in a strip below it. For the
 * same reason the add-channel panel pushes the grid down instead of floating over it.
 *
 * @param onWatchSingle called with the one channel left when a close leaves a single
 *   tile, so multi-stream hands back to the normal stream screen.
 */
@Composable
fun MultiViewContent(
    koin: Koin,
    initialLogin: String,
    onBack: () -> Unit,
    onWatchSingle: (String) -> Unit,
    onRequestSignIn: () -> Unit = {},
) {
    val viewModel = rememberDesktopViewModel(initialLogin) {
        koin.get<MultiViewViewModel> { parametersOf(initialLogin) }
    }
    val state by viewModel.state.collectAsState()
    val layout = state.layout
    val c = PureTvTheme.colors

    // With a single tile there is nothing to compare yet: start with the picker open.
    var pickerOpen by remember { mutableStateOf(layout.count < 2) }

    fun closeTile(id: Int) {
        val left = viewModel.closeTile(id)
        when (left.size) {
            0 -> onBack()
            1 -> onWatchSingle(left.first().login)
        }
    }

    val appSettings by remember { koin.get<DesktopSettingsStore>() }.settings.collectAsState()
    val shell = LocalAppShell.current
    val chatOpen = shell.isChatOpen
    val chatWidth by animateDpAsState(
        targetValue = if (chatOpen) CHAT_WIDTH else 0.dp,
        animationSpec = tween(PureTvMotion.Medium),
        label = "multiChatWidth",
    )
    val chatGap by animateDpAsState(
        targetValue = if (chatOpen) TILE_GAP else 0.dp,
        animationSpec = tween(PureTvMotion.Medium),
        label = "multiChatGap",
    )

    Row(modifier = Modifier.fillMaxSize().background(c.surfaceLowest).padding(8.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MultiTopBar(
                state = state,
                pickerOpen = pickerOpen,
                chatOpen = chatOpen,
                onToggleChat = { shell.toggleChat() },
                onBack = onBack,
                onTogglePicker = { pickerOpen = !pickerOpen },
                onVolume = viewModel::setVolume,
                onToggleMute = viewModel::toggleMute,
            )

            AnimatedVisibility(
                visible = pickerOpen && layout.canAdd,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                AddChannelPanel(
                    koin = koin,
                    shown = layout,
                    onAdd = { login ->
                        if (viewModel.addChannel(login) && viewModel.state.value.layout.count >= 2) pickerOpen = false
                    },
                )
            }

            if (state.fatalError != null) {
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text(
                        state.fatalError!!,
                        color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                TileGrid(count = layout.count, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Keyed by tile id so a tile keeps its video surface when the grid is
                    // rearranged: moving a surface to a new parent would destroy its window.
                    layout.tiles.forEach { tile ->
                        key(tile.id) {
                            val info = state.info[tile.id]
                            if (info != null) {
                                Tile(
                                    tile = tile,
                                    info = info,
                                    isActive = tile.id == layout.activeId,
                                    onActivate = { viewModel.setActive(tile.id) },
                                    onClose = { closeTile(tile.id) },
                                )
                            } else {
                                Box(Modifier)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(chatGap))

        // Chat for the active tile only; it follows the sound. Width animates to 0 when
        // closed, and the connection stays so reopening shows the room as it is.
        Box(Modifier.width(chatWidth).fillMaxHeight().clipToBounds()) {
            MultiChatColumn(
                viewModel = viewModel,
                appSettings = appSettings,
                channelName = layout.active?.let { state.info[it.id]?.displayName ?: it.login },
                onClose = { shell.toggleChat() },
                onRequestSignIn = onRequestSignIn,
            )
        }
    }
}

private val CHAT_WIDTH = 392.dp
private val CHAT_RADIUS = 24.dp

@Composable
private fun MultiChatColumn(
    viewModel: MultiViewViewModel,
    appSettings: AppSettings,
    channelName: String?,
    onClose: () -> Unit,
    onRequestSignIn: () -> Unit,
) {
    val c = PureTvTheme.colors
    val chat by viewModel.chatState.collectAsState()
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(CHAT_RADIUS))
                .background(c.surfaceContainer)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Chat", style = MaterialTheme.typography.titleMedium, color = c.onSurface, maxLines = 1)
                Text(
                    channelName ?: "No channel",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ExpressiveIconButton(
                icon = ExpressiveIcons.Close,
                contentDescription = "Close chat",
                onClick = onClose,
                boxSize = 48.dp,
                iconSize = 22.dp,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(CHAT_RADIUS))
                .background(c.surfaceContainer),
        ) {
            CompositionLocalProvider(
                LocalBadgeIndex provides chat.badges,
                LocalChatAppearance provides ChatAppearance(
                    showTimestamps = appSettings.chatTimestamps,
                    showBadges = appSettings.showBadges,
                    compact = appSettings.compactMode,
                ),
            ) {
                ChatScale(ChatTextSize.from(appSettings.chatFontSize).scale) {
                    // The room changes with the active tile: a fresh list state per channel.
                    key(chat.channel) {
                        ChatMessageList(
                            messages = chat.messages,
                            onReply = viewModel::startReply,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        RoomModeChips(chat.modes)
        ChatInputBar(
            canChat = chat.canChat,
            emotes = chat.emotes,
            recentMessages = chat.messages,
            selfLogin = chat.selfLogin,
            replyingTo = chat.replyingTo,
            onCancelReply = viewModel::cancelReply,
            onSend = viewModel::sendChat,
            onRequestSignIn = onRequestSignIn,
        )
    }
}

/** Places the tiles in the grid for their count. One flat layout, so nothing is reparented. */
@Composable
private fun TileGrid(count: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val shape = MultiViewLayout.shape(count)
        val gap = TILE_GAP.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val cellWidth = ((width - gap * (shape.columns - 1)) / shape.columns).coerceAtLeast(0)
        val cellHeight = ((height - gap * (shape.rows - 1)) / shape.rows).coerceAtLeast(0)
        val placed = measurables.mapIndexed { index, measurable ->
            val cell = MultiViewLayout.cell(index, measurables.size)
            val w = cellWidth * cell.columnSpan + gap * (cell.columnSpan - 1)
            Triple(cell, w, measurable.measure(Constraints.fixed(w, cellHeight)))
        }
        layout(width, height) {
            placed.forEach { (cell, _, placeable) ->
                placeable.place(cell.column * (cellWidth + gap), cell.row * (cellHeight + gap))
            }
        }
    }
}

@Composable
private fun Tile(
    tile: MultiTile,
    info: TileInfo,
    isActive: Boolean,
    onActivate: () -> Unit,
    onClose: () -> Unit,
) {
    val c = PureTvTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        // The outline: a frame of padding around the video, in the accent colour while active.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (isActive) c.primary else c.surfaceHigh)
                .padding(TILE_FRAME)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val player = info.player
            when {
                info.message != null -> Text(
                    info.message,
                    color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                player == null -> Text("Starting...", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                else -> VlcPlayerView(
                    vlcPlayer = player,
                    modifier = Modifier.fillMaxSize(),
                    onClick = onActivate,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TILE_STRIP_HEIGHT)
                .background(c.surfaceContainer)
                .clickable(onClick = onActivate)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(displayName = info.displayName, imageUrl = info.avatarUrl, size = 28)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = when {
                    isActive -> "You are listening"
                    info.isBuffering && info.message == null -> "Loading..."
                    else -> "Click to listen"
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = if (isActive) c.primary else c.onSurfaceVariant, maxLines = 1)
            }
            if (info.adBlockStatus != AdBlockStatus.UNKNOWN || info.player != null) AdBlockPill(info.adBlockStatus)
            ExpressiveIconButton(
                icon = ExpressiveIcons.Close,
                contentDescription = "Close ${info.displayName}",
                onClick = onClose,
                boxSize = 36.dp,
                iconSize = 18.dp,
            )
        }
    }
}

@Composable
private fun MultiTopBar(
    state: MultiViewUiState,
    pickerOpen: Boolean,
    chatOpen: Boolean,
    onToggleChat: () -> Unit,
    onBack: () -> Unit,
    onTogglePicker: () -> Unit,
    onVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
) {
    val c = PureTvTheme.colors
    val layout = state.layout
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExpressiveIconButton(
            icon = ExpressiveIcons.Back,
            contentDescription = "Back",
            onClick = onBack,
            boxSize = 48.dp,
            iconSize = 24.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("Multi-stream", style = MaterialTheme.typography.titleLarge, color = c.onSurface, maxLines = 1)
            Text(
                "${layout.count} of ${MultiViewState.MAX_TILES} channels",
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                maxLines = 1,
            )
        }
        ExpressiveIconButton(
            icon = if (state.soundOff || state.volume == 0) ExpressiveIcons.VolumeOff else ExpressiveIcons.VolumeUp,
            contentDescription = if (state.soundOff) "Unmute" else "Mute",
            onClick = onToggleMute,
            boxSize = 44.dp,
            iconSize = 22.dp,
        )
        ExpressiveSlider(
            value = if (state.soundOff) 0f else state.volume / 100f,
            onValueChange = { onVolume((it * 100f).roundToInt()) },
            modifier = Modifier.width(150.dp),
        )
        ExpressiveButton(
            text = if (pickerOpen) "Hide" else "Add channel",
            onClick = onTogglePicker,
            style = ExpressiveButtonStyle.Tonal,
            size = ExpressiveButtonSize.Medium,
            icon = ExpressiveIcons.Add,
            enabled = layout.canAdd,
        )
        ExpressiveIconButton(
            icon = ExpressiveIcons.Chat,
            contentDescription = if (chatOpen) "Hide chat" else "Show chat",
            onClick = onToggleChat,
            boxSize = 48.dp,
            iconSize = 24.dp,
            tint = if (chatOpen) c.primary else c.onSurfaceVariant,
        )
    }
}

/**
 * Pick a channel to add: the followed channels that are live right now, or any
 * channel by name.
 */
@Composable
private fun AddChannelPanel(koin: Koin, shown: MultiViewState, onAdd: (String) -> Unit) {
    val c = PureTvTheme.colors
    val rail = rememberDesktopViewModel { koin.get<FollowedRailViewModel>() }
    val railState by rail.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(rail) { rail.refresh() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            var typed by remember { mutableStateOf("") }
            fun submit() {
                val login = typed.trim()
                if (login.isNotEmpty()) {
                    onAdd(login)
                    typed = ""
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(c.surfaceHigh)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (typed.isEmpty()) {
                    Text("Type a channel name", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ExpressiveButton(text = "Add", onClick = ::submit, size = ExpressiveButtonSize.Medium, icon = ExpressiveIcons.Add)
        }

        val candidates = railState.live.filterNot { shown.contains(it.login) }
        when {
            !railState.isLoggedIn -> Text(
                "Sign in to pick from the channels you follow, or type any channel name above.",
                color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            candidates.isEmpty() -> Text(
                if (railState.isLoading) "Loading the channels you follow..." else "None of the channels you follow are live right now.",
                color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> {
                Text("Live now from your follows", color = c.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidates.forEach { row ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(c.surfaceHigh)
                                .clickable { onAdd(row.login) }
                                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(displayName = row.displayName, imageUrl = row.avatarUrl, size = 32)
                            Column {
                                Text(row.displayName, style = MaterialTheme.typography.labelLarge, color = c.onSurface, maxLines = 1)
                                Text(
                                    "${formatViewerCount(row.viewerCount)} viewers",
                                    style = PureTvType.dataSmall,
                                    color = c.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
