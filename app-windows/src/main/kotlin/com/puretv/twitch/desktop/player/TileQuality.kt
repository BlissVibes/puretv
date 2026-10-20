package com.puretv.twitch.desktop.player

import com.puretv.twitch.core.model.StreamQuality

/**
 * The picture quality of a multi-stream tile.
 *
 * The cap depends on how many tiles are on screen and never on which one is
 * audible, so moving the sound from tile to tile never reloads a stream: two tiles
 * play up to 1080p60, three or four up to 720p60. A lower quality saved in
 * Settings wins over the cap.
 */
object TileQuality {

    /** The best quality a tile may play, given [tileCount] tiles and the viewer's [preference]. */
    fun capFor(tileCount: Int, preference: StreamQuality): StreamQuality {
        val byCount = if (tileCount <= 2) StreamQuality.P1080P60 else StreamQuality.P720P60
        return when (preference) {
            // No usable preference: the cap alone decides.
            StreamQuality.AUTO, StreamQuality.SOURCE, StreamQuality.AUDIO_ONLY -> byCount
            // sortOrder grows as the picture gets smaller.
            else -> if (preference.sortOrder > byCount.sortOrder) preference else byCount
        }
    }

    /** The tallest picture [quality] allows, in lines. */
    fun maxHeight(quality: StreamQuality): Int = when (quality) {
        StreamQuality.P1080P60 -> 1080
        StreamQuality.P720P60 -> 720
        StreamQuality.P480P -> 480
        StreamQuality.P360P -> 360
        StreamQuality.SOURCE, StreamQuality.AUTO, StreamQuality.AUDIO_ONLY -> 1080
    }

    /**
     * [master] cut down to the single best rendition at or below [maxHeight] lines,
     * preferring the higher frame rate at the same height. When every rendition is
     * taller than the cap, the smallest one is kept; a master with no sized
     * renditions comes back unchanged. All header and media tags are kept so the
     * result is still a valid master. Giving the player one rendition is what makes
     * the cap hold: left to choose, VLC picks by bandwidth, often Source.
     */
    fun capMaster(master: String, maxHeight: Int): String {
        val lines = master.lines()
        class Variant(val infLine: Int, val urlLine: Int, val height: Int, val fps: Double)

        val variants = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val tag = lines[i].trim()
            if (tag.startsWith("#EXT-X-STREAM-INF")) {
                val urlIndex = (i + 1 until lines.size).firstOrNull {
                    val t = lines[it].trim()
                    t.isNotEmpty() && !t.startsWith("#")
                }
                if (urlIndex != null) {
                    variants += Variant(
                        infLine = i,
                        urlLine = urlIndex,
                        height = heightOf(tag),
                        fps = attribute(tag, "FRAME-RATE")?.toDoubleOrNull() ?: 0.0,
                    )
                    i = urlIndex
                }
            }
            i++
        }

        val sized = variants.filter { it.height > 0 }
        if (sized.isEmpty()) return master
        val chosen = sized.filter { it.height <= maxHeight }
            .maxWithOrNull(compareBy<Variant> { it.height }.thenBy { it.fps })
            ?: sized.minWithOrNull(compareBy<Variant> { it.height }.thenByDescending { it.fps })
            ?: return master

        val dropped = HashSet<Int>()
        for (v in variants) {
            if (v === chosen) continue
            dropped += v.infLine
            dropped += v.urlLine
        }
        return lines.filterIndexed { index, _ -> index !in dropped }.joinToString("\n")
    }

    private fun heightOf(streamInf: String): Int =
        attribute(streamInf, "RESOLUTION")?.substringAfter('x', "")?.trim()?.toIntOrNull() ?: 0

    private fun attribute(line: String, name: String): String? =
        Regex("(?:^|[,:])$name=([^,]+)").find(line)?.groupValues?.get(1)?.trim()?.trim('"')
}
