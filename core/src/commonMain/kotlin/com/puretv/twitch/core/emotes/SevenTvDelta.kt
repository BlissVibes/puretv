package com.puretv.twitch.core.emotes

import com.puretv.twitch.core.model.ChannelEmote
import com.puretv.twitch.core.model.EmoteProvider

/**
 * Folds a live 7TV EventAPI [delta] into a channel's third-party emote list, so a
 * streamer adding or removing an emote shows up in chat without reopening the
 * stream. Shared by every app so the rules cannot drift:
 * - removals match 7TV emotes by name and never touch a BTTV or FFZ emote of the
 *   same name;
 * - additions are de-duped against the 7TV names already present, so a repeated
 *   push cannot double-insert;
 * - a rename arrives as remove-old plus add-new (see [SevenTvEventClient]).
 *
 * Returns null when the delta changes nothing, so the caller can skip rebuilding
 * its emote index and re-rendering chat. An emote already drawn in an old line
 * stays drawn: rebuilding re-scans text, it does not undo resolved emotes.
 */
fun applySevenTvDelta(current: List<ChannelEmote>, delta: SevenTvEmoteDelta): List<ChannelEmote>? {
    val removedNames = delta.removed.mapTo(HashSet()) { it.name }
    val afterRemoval =
        if (removedNames.isEmpty()) current
        else current.filterNot { it.provider == EmoteProvider.SEVENTV && it.name in removedNames }
    val present7tv = afterRemoval.filter { it.provider == EmoteProvider.SEVENTV }.mapTo(HashSet()) { it.name }
    val additions = delta.added.filter { it.name.isNotBlank() && present7tv.add(it.name) }
    if (afterRemoval.size == current.size && additions.isEmpty()) return null
    return afterRemoval + additions
}
