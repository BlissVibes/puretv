package com.puretv.twitch.core.emotes

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Name paints from the 7TV EventAPI. The frames are trimmed from a read-only live
 * capture (2026-10-19, `wss://events.7tv.io/v3`, no presence sent): avatar, role
 * and file lists are cut, everything the parser reads is as 7TV sent it.
 */
class SevenTvCosmeticsTest {

    private val client = SevenTvEventClient(HttpClient(MockEngine { respond("") }))

    private fun parse(frame: String): Any? = client.parseFrame(frame, channelSetId = "channelSet")

    @Test fun colourIsPackedRgba() {
        assertEquals(0xFF000000.toInt(), sevenTvRgbaToArgb(255))
        assertEquals(0xFFFFFFFF.toInt(), sevenTvRgbaToArgb(-1))
        assertEquals(0x80112233.toInt(), sevenTvRgbaToArgb(0x11223380))
    }

    @Test fun parsesLinearGradientPaint() {
        val event = assertIs<SevenTvCosmeticEvent.PaintDefined>(parse(LINEAR_PAINT))
        val paint = event.paint
        assertEquals("01KQ92ZQRGHYMNGXYNTVYF5AEB", paint.id)
        assertEquals("City Lights", paint.name)
        assertEquals(SevenTvPaintFunction.LINEAR_GRADIENT, paint.function)
        assertEquals(360, paint.angle)
        assertFalse(paint.repeat)
        assertNull(paint.argb)
        assertEquals(listOf(0.25f, 0.4f, 0.5f, 0.59f, 0.8f), paint.stops.map { it.at })
        assertEquals(sevenTvRgbaToArgb(-33449217), paint.stops.first().argb)
    }

    @Test fun parsesRepeatingLinearPaint() {
        val paint = assertIs<SevenTvCosmeticEvent.PaintDefined>(parse(REPEATING_LINEAR_PAINT)).paint
        assertEquals(135, paint.angle)
        assertTrue(paint.repeat)
        assertEquals(4, paint.stops.size)
    }

    @Test fun parsesRadialEllipsePaint() {
        val paint = assertIs<SevenTvCosmeticEvent.PaintDefined>(parse(RADIAL_PAINT)).paint
        assertEquals(SevenTvPaintFunction.RADIAL_GRADIENT, paint.function)
        assertTrue(paint.ellipse)
        assertEquals(5, paint.stops.size)
    }

    @Test fun imagePaintKeepsNoColourWhenItHasNone() {
        // Every image paint in the capture had color null and no stops: it falls
        // back to the chatter's own name colour downstream.
        val paint = assertIs<SevenTvCosmeticEvent.PaintDefined>(parse(URL_PAINT)).paint
        assertEquals(SevenTvPaintFunction.URL, paint.function)
        assertNull(paint.argb)
        assertTrue(paint.stops.isEmpty())
    }

    @Test fun paintEntitlementIsKeyedByTwitchId() {
        val event = assertIs<SevenTvCosmeticEvent.PaintGranted>(parse(PAINT_ENTITLEMENT))
        // The Twitch connection's id, never the 7TV user id (01K3RT...).
        assertEquals("1296678698", event.twitchUserId)
        assertEquals("01JFQV4W300XYMWDJW7E1TVY34", event.paintId)
    }

    @Test fun paintRevokeIsParsed() {
        val event = assertIs<SevenTvCosmeticEvent.PaintRevoked>(parse(PAINT_REVOKE))
        assertEquals("1296678698", event.twitchUserId)
        assertEquals("01JFQV4W300XYMWDJW7E1TVY34", event.paintId)
    }

    @Test fun badgeAndEmoteSetCosmeticsAreIgnored() {
        assertNull(parse(BADGE_ENTITLEMENT))
        assertNull(parse(BADGE_ENTITLEMENT.replace("\"kind\":\"BADGE\"", "\"kind\":\"EMOTE_SET\"")))
        assertNull(parse(BADGE_COSMETIC))
    }

    @Test fun entitlementWithoutTwitchConnectionIsIgnored() {
        assertNull(parse(PAINT_ENTITLEMENT.replace("\"platform\":\"TWITCH\"", "\"platform\":\"YOUTUBE\"")))
    }

    // ---- Store ----

    private fun paint(id: String) = SevenTvPaint(id, id, SevenTvPaintFunction.LINEAR_GRADIENT)

    @Test fun grantBeforeDefinitionResolvesOnceDefined() {
        val store = SevenTvPaintStore()
        store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "p1"))
        assertNull(store.paintFor("u1"))
        assertTrue(store.snapshot().isEmpty())
        store.apply(SevenTvCosmeticEvent.PaintDefined(paint("p1")))
        assertEquals("p1", store.paintFor("u1")?.id)
        assertEquals("p1", store.snapshot()["u1"]?.id)
    }

    @Test fun revokeOfAnOldPaintKeepsTheNewOne() {
        val store = SevenTvPaintStore()
        store.apply(SevenTvCosmeticEvent.PaintDefined(paint("old")))
        store.apply(SevenTvCosmeticEvent.PaintDefined(paint("new")))
        store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "old"))
        store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "new"))
        assertFalse(store.apply(SevenTvCosmeticEvent.PaintRevoked("u1", "old")))
        assertEquals("new", store.paintFor("u1")?.id)
        assertTrue(store.apply(SevenTvCosmeticEvent.PaintRevoked("u1", "new")))
        assertNull(store.paintFor("u1"))
    }

    @Test fun repeatedGrantIsNotAChange() {
        val store = SevenTvPaintStore()
        assertTrue(store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "p1")))
        assertFalse(store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "p1")))
        assertTrue(store.apply(SevenTvCosmeticEvent.PaintDefined(paint("p1"))))
        assertFalse(store.apply(SevenTvCosmeticEvent.PaintDefined(paint("p1"))))
    }

    @Test fun usersAreCappedOldestFirst() {
        val store = SevenTvPaintStore(maxUsers = 3)
        store.apply(SevenTvCosmeticEvent.PaintDefined(paint("p")))
        listOf("a", "b", "c").forEach { store.apply(SevenTvCosmeticEvent.PaintGranted(it, "p")) }
        // Re-granting a refreshes it, so b is now the oldest.
        store.apply(SevenTvCosmeticEvent.PaintGranted("a", "p"))
        store.apply(SevenTvCosmeticEvent.PaintGranted("d", "p"))
        assertNull(store.paintFor("b"))
        assertEquals(setOf("a", "c", "d"), store.snapshot().keys)
    }

    @Test fun paintsAreCapped() {
        val store = SevenTvPaintStore(maxPaints = 2)
        listOf("p1", "p2", "p3").forEach { store.apply(SevenTvCosmeticEvent.PaintDefined(paint(it))) }
        store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "p1"))
        store.apply(SevenTvCosmeticEvent.PaintGranted("u3", "p3"))
        assertNull(store.paintFor("u1"))
        assertEquals("p3", store.paintFor("u3")?.id)
    }

    @Test fun snapshotDoesNotChangeAfterwards() {
        val store = SevenTvPaintStore()
        store.apply(SevenTvCosmeticEvent.PaintDefined(paint("p1")))
        store.apply(SevenTvCosmeticEvent.PaintGranted("u1", "p1"))
        val before = store.snapshot()
        store.apply(SevenTvCosmeticEvent.PaintRevoked("u1", "p1"))
        assertEquals("p1", before["u1"]?.id)
        assertNull(store.snapshot()["u1"])
    }

    private companion object {
        val LINEAR_PAINT = """
            {"d":{"type":"cosmetic.create","body":{"id":"01KQ92ZQRGHYMNGXYNTVYF5AEB","kind":10,"object":{"id":"01KQ92ZQRGHYMNGXYNTVYF5AEB","kind":"PAINT","data":{"id":"01KQ92ZQRGHYMNGXYNTVYF5AEB","name":"City Lights","color":null,"gradients":[],"shadows":[{"x_offset":0.0,"y_offset":0.0,"radius":4.0,"color":-16729089}],"text":null,"function":"LINEAR_GRADIENT","repeat":false,"angle":360,"shape":"circle","image_url":"","stops":[{"at":0.25,"color":-33449217},{"at":0.4,"color":-59732225},{"at":0.5,"color":-173638401},{"at":0.59,"color":-405512449},{"at":0.8,"color":-805370625}]}}}},"op":0,"t":1792470097361,"s":25}
        """.trimIndent()

        // North Star: same shape as City Lights, the stops below are trimmed to four
        // made-up colours because only repeat and angle matter here.
        val REPEATING_LINEAR_PAINT = """
            {"d":{"type":"cosmetic.create","body":{"id":"p2","kind":10,"object":{"id":"p2","kind":"PAINT","data":{"id":"p2","name":"North Star","color":null,"gradients":[],"shadows":[],"text":null,"function":"LINEAR_GRADIENT","repeat":true,"angle":135,"shape":"circle","image_url":"","stops":[{"at":0.0,"color":-1},{"at":0.1,"color":255},{"at":0.2,"color":-1},{"at":0.3,"color":255}]}}}},"op":0,"t":1792470101446,"s":46}
        """.trimIndent()

        val RADIAL_PAINT = """
            {"d":{"type":"cosmetic.create","body":{"id":"01K3ZXCM4S71MQ15Q5A3VPM9ZR","kind":10,"object":{"id":"01K3ZXCM4S71MQ15Q5A3VPM9ZR","kind":"PAINT","data":{"id":"01K3ZXCM4S71MQ15Q5A3VPM9ZR","name":"Nebula","color":null,"gradients":[],"shadows":[{"x_offset":0.0,"y_offset":0.0,"radius":0.1,"color":872459775},{"x_offset":0.0,"y_offset":0.0,"radius":4.0,"color":1949892607}],"text":null,"function":"RADIAL_GRADIENT","repeat":false,"angle":0,"shape":"ellipse","image_url":"","stops":[{"at":0.0,"color":1559560191},{"at":0.37,"color":-1996521217},{"at":0.47,"color":-2034565377},{"at":0.65,"color":1970995199},{"at":1.0,"color":-1971650561}]}}}},"op":0,"t":1792470101446,"s":45}
        """.trimIndent()

        val URL_PAINT = """
            {"d":{"type":"cosmetic.create","body":{"id":"01JFQV4W300XYMWDJW7E1TVY34","kind":10,"object":{"id":"01JFQV4W300XYMWDJW7E1TVY34","kind":"PAINT","data":{"id":"01JFQV4W300XYMWDJW7E1TVY34","name":"Winter Snowfall","color":null,"gradients":[],"shadows":[{"x_offset":1.0,"y_offset":1.0,"radius":0.1,"color":3632127},{"x_offset":0.0,"y_offset":0.0,"radius":4.0,"color":10603007}],"text":null,"function":"URL","repeat":false,"angle":0,"shape":"circle","image_url":"https://cdn.7tv.app/paint/01JFQV4W300XYMWDJW7E1TVY34/layer/01JX6ANAHY9JZXQTV4JGBPRYT9/1x.webp","stops":[]}}}},"op":0,"t":1792470094811,"s":15}
        """.trimIndent()

        val PAINT_ENTITLEMENT = """
            {"d":{"type":"entitlement.create","body":{"id":"00000000000000000000000000","kind":10,"object":{"id":"00000000000000000000000000","kind":"PAINT","ref_id":"01JFQV4W300XYMWDJW7E1TVY34","user":{"id":"01K3RT1XZNS0VB6JAN3NFYCP7A","username":"yostbl","display_name":"yostbl","style":{"color":-5635841,"paint_id":"01JFQV4W300XYMWDJW7E1TVY34","badge_id":"01KYQHQ7FVRR3X4S0F2KG74207"},"connections":[{"id":"1296678698","platform":"TWITCH","username":"yostbl","display_name":"yostbl","linked_at":1756400515061,"emote_capacity":1000,"emote_set_id":"01KKME4X99JX4VQ6Z09WNTMNX0"}]}}}},"op":0,"t":1792470094812,"s":21}
        """.trimIndent()

        val PAINT_REVOKE = PAINT_ENTITLEMENT.replace("entitlement.create", "entitlement.delete")

        val BADGE_ENTITLEMENT = """
            {"d":{"type":"entitlement.create","body":{"id":"00000000000000000000000000","kind":10,"object":{"id":"00000000000000000000000000","kind":"BADGE","ref_id":"01KYQHQ7FVRR3X4S0F2KG74207","user":{"id":"01K3RT1XZNS0VB6JAN3NFYCP7A","username":"yostbl","style":{"color":-5635841,"paint_id":"01JFQV4W300XYMWDJW7E1TVY34","badge_id":"01KYQHQ7FVRR3X4S0F2KG74207"},"connections":[{"id":"1296678698","platform":"TWITCH","username":"yostbl"}]}}}},"op":0,"t":1792470094812,"s":20}
        """.trimIndent()

        val BADGE_COSMETIC = """
            {"d":{"type":"cosmetic.create","body":{"id":"01KYQHQ7FVRR3X4S0F2KG74207","kind":10,"object":{"id":"01KYQHQ7FVRR3X4S0F2KG74207","kind":"BADGE","data":{"id":"01KYQHQ7FVRR3X4S0F2KG74207","name":"XDX","tag":"xdx","tooltip":"XDX badge","host":{"url":"//cdn.7tv.app/badge/01KYQHQ7FVRR3X4S0F2KG74207","files":[]}}}}},"op":0,"t":1792470094811,"s":14}
        """.trimIndent()
    }
}
