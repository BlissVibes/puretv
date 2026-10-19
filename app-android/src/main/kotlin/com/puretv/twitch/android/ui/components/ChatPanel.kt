package com.puretv.twitch.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import com.puretv.twitch.android.ui.theme.PureTvMotion
import com.puretv.twitch.android.ui.theme.PureTvTheme
import com.puretv.twitch.android.ui.theme.PureTvType
import com.puretv.twitch.core.chat.ComposerSuggestion
import com.puretv.twitch.core.chat.completeWord
import com.puretv.twitch.core.chat.composerSuggestions
import com.puretv.twitch.core.chat.insertAtCursor
import com.puretv.twitch.core.chat.recentChatters
import com.puretv.twitch.core.chat.wordAtCursor
import com.puretv.twitch.core.emotes.EmoteSource
import com.puretv.twitch.core.emotes.PickableEmote
import com.puretv.twitch.core.model.ChatMessage
import com.puretv.twitch.core.model.MessagePart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SECTION 06.4 / 05: live chat list + composer, Material 3 Expressive. Each row
 * is its own rounded container (mirrors the desktop `ChatMessageRow`): sender in
 * their IRC colour, a mono clock, then the body with emotes drawn inline as
 * images. Third-party (7TV/BTTV/FFZ) emotes are matched by word against
 * [emotes] since the live IRC parser only tags first-party emotes in
 * [ChatMessage.parsedParts]; Twitch emotes come from those parsed parts
 * directly. This duplicates the small inline-content builder in [EmoteText]
 * rather than reusing it, because that composable welds the sender name into
 * the same run as the body, and the mock calls for the name and body to carry
 * two different type styles.
 *
 * The composer mirrors desktop's: a suggestion strip above it completes the
 * word being typed (emotes, and recent chatters for "@"), and the emote button
 * opens a searchable grid picker built from [pickableEmotes]. Both share core's
 * `com.puretv.twitch.core.chat` composer helpers with the desktop app.
 */
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    emotes: Map<String, String> = emptyMap(),
    pickableEmotes: List<PickableEmote> = emptyList(),
    selfLogin: String? = null,
    canSend: Boolean = true,
    /** Settings > Chat > Text size. Scales the whole message list, emotes included. */
    textScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTheme.colors
    val listState = rememberLazyListState()
    // A TextFieldValue (not a bare String) so completion and picker inserts know
    // where the cursor is. The selection survives losing focus to the picker.
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var pickerOpen by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Key on the newest message id, not messages.size: the list is capped at 200
    // (takeLast(200) in the VM), so size saturates and a size-keyed effect would
    // stop firing, freezing auto-scroll in any busy channel.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Trimmed like desktop's submit(): a completion leaves a trailing space.
    val send: () -> Unit = {
        val trimmed = draft.text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            draft = TextFieldValue("")
        }
    }

    // Suggestions for the word the cursor sits in. Skipped for an empty word so a
    // busy chat doesn't rescan its 200 rows on every message while you're idle.
    val (word, _) = wordAtCursor(draft.text, draft.selection.start)
    val suggestions = remember(word, pickableEmotes, messages, selfLogin) {
        if (word.isEmpty()) {
            emptyList()
        } else {
            composerSuggestions(word, pickableEmotes, recentChatters(messages, selfLogin))
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The picker takes about half the chat pane, capped so a tall portrait pane
        // still shows plenty of chat. Derived from this pane's own height, so the
        // same rule fits portrait under the player and the landscape side panel.
        val pickerHeight = (maxHeight * 0.5f).coerceAtMost(280.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * textScale, density.fontScale)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageRow(message = message, emotes = emotes, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (canSend) {
                if (pickerOpen) {
                    EmotePicker(
                        emotes = pickableEmotes,
                        onPick = { code ->
                            val (text, cursor) = insertAtCursor(draft.text, draft.selection.start, code)
                            draft = TextFieldValue(text, TextRange(cursor))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(pickerHeight)
                            .padding(start = 8.dp, end = 8.dp, top = 6.dp),
                    )
                }

                // Autocomplete strip, only while typing a recognised partial. Tapping a
                // chip is the touch stand-in for desktop's Tab.
                if (suggestions.isNotEmpty() && fieldFocused) {
                    SuggestionStrip(
                        suggestions = suggestions,
                        onPick = { s ->
                            val (text, cursor) = completeWord(draft.text, draft.selection.start, s.completion)
                            draft = TextFieldValue(text, TextRange(cursor))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                // The composer itself: a surfaceHigh pill that squares off to 16dp the
                // moment the field takes focus, echoing the press-morph language with a
                // focus-driven trigger since a text field is held, not tapped-and-released.
                val composerRadius by animateDpAsState(
                    targetValue = if (fieldFocused) PureTvTheme.shapes.md else PureTvTheme.shapes.pill,
                    animationSpec = PureTvMotion.MorphSpring,
                    label = "composerRadius",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(composerRadius.coerceAtLeast(0.dp)))
                        .background(c.surfaceHigh)
                        .padding(start = 18.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (draft.text.isEmpty()) {
                            Text("Send a message", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = c.onSurface,
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                            ),
                            cursorBrush = SolidColor(c.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.fillMaxWidth().onFocusChanged {
                                fieldFocused = it.isFocused
                                // Picker and keyboard take turns, like Twitch's app: tapping
                                // back into the field brings the keyboard and folds the picker.
                                if (it.isFocused) pickerOpen = false
                            },
                        )
                    }
                    ExpressiveIconButton(
                        icon = ExpressiveIcons.Emote,
                        contentDescription = "Emotes",
                        onClick = {
                            pickerOpen = !pickerOpen
                            // Opening the picker drops the keyboard so the grid gets the
                            // room; the draft keeps its cursor for the insert.
                            if (pickerOpen) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }
                        },
                        boxSize = 44.dp,
                        iconSize = 22.dp,
                        tint = if (pickerOpen) c.primary else c.onSurfaceVariant,
                    )
                    ExpressiveIconButton(
                        icon = ExpressiveIcons.Send,
                        contentDescription = "Send message",
                        onClick = send,
                        style = ExpressiveButtonStyle.Filled,
                        boxSize = 44.dp,
                        iconSize = 22.dp,
                        enabled = draft.text.isNotBlank(),
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Log in to chat",
                        color = c.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * One chat message: its own rounded [PureTvTheme.shapes.md] container that fills
 * [PureTvAndroidColors.surfaceHigh] under the finger, or [PureTvAndroidColors.primaryContainer]
 * standing (not just on press) when it @-mentions the viewer, so a ping is
 * legible while scrolling past rather than only while touched.
 */
@Composable
private fun ChatMessageRow(message: ChatMessage, emotes: Map<String, String>, modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val shapes = PureTvTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            message.mentionsSelf -> c.primaryContainer
            pressed -> c.surfaceHigh
            else -> Color.Transparent
        },
        animationSpec = tween(PureTvMotion.Fast),
        label = "chatRowFill",
    )
    val nameColor = remember(message.color) { parseChatColor(message.color, c.primary) }
    val timestamp = remember(message.timestamp) {
        runCatching { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)) }.getOrDefault("")
    }
    val (bodyText, bodyInline) = remember(message.id, emotes) { buildChatBody(message, emotes) }

    Column(
        modifier = modifier
            .clip(shapes.mdShape)
            .background(fill)
            // No destination exists for a tap yet (no reply feature on Android), so
            // onClick is a no-op: the interaction source only drives the press fill.
            .clickable(interactionSource = interaction, indication = null, onClick = {})
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (message.isBroadcaster) {
                ChatBadgeChip("HOST", c.primary, c.onPrimary)
            } else if (message.isModerator) {
                ChatBadgeChip("MOD", c.tertiaryContainer, c.onTertiaryContainer)
            }
            if (message.isSubscriber) {
                ChatBadgeChip("SUB", c.secondaryContainer, c.onSecondaryContainer)
            }
            Text(
                message.displayName,
                color = nameColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(timestamp, style = PureTvType.dataSmall, color = c.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Text(
            text = bodyText,
            inlineContent = bodyInline,
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurface,
        )
    }
}

@Composable
private fun ChatBadgeChip(text: String, bg: Color, fg: Color) {
    Text(
        text,
        style = PureTvType.dataSmall,
        color = fg,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Section headings in the picker, matching desktop's `EmotePickerPanel`. */
private fun EmoteSource.pickerLabel(): String = when (this) {
    EmoteSource.TWITCH_CHANNEL -> "Channel"
    EmoteSource.TWITCH_GLOBAL -> "Global"
    EmoteSource.BTTV -> "BetterTTV"
    EmoteSource.FFZ -> "FrankerFaceZ"
    EmoteSource.SEVENTV -> "7TV"
}

/**
 * The emote picker: a search field over a multi-row grid, sectioned by provider
 * (channel Twitch emotes first, in [com.puretv.twitch.core.emotes.buildPickableEmotes]
 * order). The caller bounds its height; the grid scrolls inside it. Tapping a
 * tile hands the emote's code to [onPick], which inserts it at the draft's cursor.
 */
@Composable
private fun EmotePicker(emotes: List<PickableEmote>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = PureTvTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    val grouped = remember(query, emotes) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) emotes else emotes.filter { it.code.contains(q, ignoreCase = true) }
        filtered.groupBy { it.source }
    }

    Column(
        modifier = modifier
            .clip(PureTvTheme.shapes.mdShape)
            .background(c.surfaceContainer)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(PureTvTheme.shapes.pillShape)
                .background(c.surfaceHigh)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text("Search emotes", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = c.onSurface, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))

        if (grouped.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (emotes.isEmpty()) "Emotes are still loading" else "No emotes match",
                    color = c.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 44.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                grouped.forEach { (source, group) ->
                    item(key = "header:" + source.name, span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            source.pickerLabel().uppercase(),
                            style = PureTvType.dataSmall,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    // buildPickableEmotes de-dupes codes, so source + code is unique.
                    items(group, key = { it.source.name + ":" + it.code }) { emote ->
                        AsyncImage(
                            model = emote.imageUrl,
                            contentDescription = emote.code,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(PureTvTheme.shapes.smShape)
                                .clickable { onPick(emote.code) }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Horizontally scrolling completion chips for the word being typed: an emote chip
 * shows its image and code, a chatter chip shows the "@Name" it will insert.
 */
@Composable
private fun SuggestionStrip(
    suggestions: List<ComposerSuggestion>,
    onPick: (ComposerSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PureTvTheme.colors
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(suggestions, key = { (if (it is ComposerSuggestion.User) "u:" else "e:") + it.completion }) { s ->
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(PureTvTheme.shapes.pillShape)
                    .background(c.surfaceHigh)
                    .clickable { onPick(s) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s is ComposerSuggestion.Emote) {
                    AsyncImage(
                        model = s.emote.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(s.completion, color = c.onSurfaceVariant, style = PureTvType.dataSmall, maxLines = 1)
            }
        }
    }
}

// twitchEmoteUrl(id) comes from EmoteText.kt (same package): the CDN URL builder
// for first-party Twitch emotes.

private fun buildChatBody(message: ChatMessage, emotes: Map<String, String>): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        message.parsedParts.forEach { part ->
            when (part) {
                is MessagePart.Text -> appendChatWords(part.content, emotes, inline)
                is MessagePart.TwitchEmote -> appendChatEmote("tw_${part.id}", part.name, twitchEmoteUrl(part.id), inline)
                is MessagePart.ThirdPartyEmote -> appendChatEmote("tp_${part.name}", part.name, part.url, inline)
            }
        }
    }
    return text to inline
}

private fun AnnotatedString.Builder.appendChatWords(
    content: String,
    emotes: Map<String, String>,
    inline: HashMap<String, InlineTextContent>,
) {
    val tokens = content.split(" ")
    tokens.forEachIndexed { i, token ->
        val url = if (token.isNotBlank()) emotes[token] else null
        if (url != null) appendChatEmote("tp_$token", token, url, inline) else append(token)
        if (i < tokens.lastIndex) append(" ")
    }
}

private fun AnnotatedString.Builder.appendChatEmote(
    id: String,
    name: String,
    url: String,
    inline: HashMap<String, InlineTextContent>,
) {
    appendInlineContent(id, name)
    if (!inline.containsKey(id)) {
        inline[id] = InlineTextContent(
            Placeholder(width = 1.8.em, height = 1.8.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
        ) {
            AsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Twitch sends chat colors as `#RRGGBB` hex strings (or empty for the default). */
private fun parseChatColor(hex: String, fallback: Color): Color =
    if (hex.isBlank()) {
        fallback
    } else {
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
    }
