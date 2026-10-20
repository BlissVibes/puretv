package com.puretv.twitch.core.stream

import com.puretv.twitch.core.api.ChatBadgeSetDto
import com.puretv.twitch.core.api.ChatBadgeVersionDto
import com.puretv.twitch.core.api.twitchEmoteImageUrl
import com.puretv.twitch.core.model.ChannelEmote
import com.puretv.twitch.core.model.EmoteProvider
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the anonymous GQL chat reads (badges and Twitch emotes) in
 * [TwitchGqlDirectoryClient], mapped into the same models the Helix path returns.
 * Helix refuses both without a user token, so this is what a signed-out viewer
 * sees chat badges and the emote picker's Twitch emotes through.
 *
 * Every field is nullable: GQL nulls out whatever it cannot resolve, and one odd
 * entry must not sink the whole list.
 */

@Serializable
internal data class GqlBadge(
    val setID: String? = null,
    val version: String? = null,
    val title: String? = null,
    val imageURL: String? = null,
)

@Serializable
internal data class GqlEmote(
    val id: String? = null,
    val token: String? = null,
    val assetType: String? = null,
)

@Serializable
internal data class GqlSubProduct(val emotes: List<GqlEmote?>? = null)

@Serializable
internal data class GqlChatUser(
    val broadcastBadges: List<GqlBadge?>? = null,
    val subscriptionProducts: List<GqlSubProduct?>? = null,
)

@Serializable
internal data class GqlChatUserData(val user: GqlChatUser? = null)

@Serializable
internal data class GqlGlobalBadgesData(val badges: List<GqlBadge?>? = null)

@Serializable
internal data class GqlEmoteSet(val emotes: List<GqlEmote?>? = null)

@Serializable
internal data class GqlGlobalEmotesData(val emoteSet: GqlEmoteSet? = null)

/**
 * GQL returns one flat row per (set, version) at a single size; Helix returns a set with
 * its versions at three sizes. Regroups the rows into the Helix shape so the badge index
 * needs no second path. The one image fills the 2x slot, the one chat renders from.
 */
internal fun List<GqlBadge?>.toBadgeSets(): List<ChatBadgeSetDto> =
    filterNotNull()
        .filter { !it.setID.isNullOrBlank() && !it.version.isNullOrBlank() && !it.imageURL.isNullOrBlank() }
        .groupBy { it.setID!! }
        .map { (setId, rows) ->
            ChatBadgeSetDto(
                setId = setId,
                versions = rows.map { ChatBadgeVersionDto(id = it.version!!, imageUrl2x = it.imageURL!!, title = it.title.orEmpty()) },
            )
        }

/** The picker's Twitch emotes, deduplicated by id (every sub tier repeats the lower tiers). */
internal fun List<GqlEmote?>.toTwitchEmotes(): List<ChannelEmote> =
    filterNotNull()
        .filter { !it.id.isNullOrBlank() && !it.token.isNullOrBlank() }
        .distinctBy { it.id }
        .map {
            val id = it.id!!
            val animated = it.assetType.equals("ANIMATED", ignoreCase = true)
            ChannelEmote(id, it.token!!, twitchEmoteImageUrl(id, animated), EmoteProvider.TWITCH, animated)
        }
