package fr.jmethod.app

import fr.jmethod.app.data.AppDocumentCodec
import fr.jmethod.app.domain.ColorWheel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/** Vecteurs communs avec Swift : `native/shared/domain/color-vectors.json`. */
class ColorWheelTest {
    private fun triple(value: JsonElement) = value.jsonArray.map { it.jsonPrimitive.double }.let { ColorWheel.HSV(it[0], it[1], it[2]) }
    private fun assertClose(label: String, actual: ColorWheel.HSV?, expected: ColorWheel.HSV) {
        assertNotNull(label, actual)
        assertEquals(label, expected.hue, actual!!.hue, 1e-6)
        assertEquals(label, expected.saturation, actual.saturation, 1e-6)
        assertEquals(label, expected.value, actual.value, 1e-6)
    }

    @Test fun sharedVectors() {
        val text = javaClass.classLoader.getResource("domain/color-vectors.json")!!.readText()
        val fixture = AppDocumentCodec.json.parseToJsonElement(text).jsonObject
        fun items(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
        items("toHsv").forEach { val hex = it.getValue("hex").jsonPrimitive.content; assertClose("toHsv $hex", ColorWheel.hsv(hex), triple(it.getValue("hsv"))) }
        items("toHex").forEach { assertEquals("toHex ${it["hsv"]}", it.getValue("hex").jsonPrimitive.content, ColorWheel.hex(triple(it.getValue("hsv")))) }
        items("parse").forEach { assertEquals("parse ${it["input"]}", it.getValue("hex").jsonPrimitive.contentOrNull, ColorWheel.normalized(it.getValue("input").jsonPrimitive.content)) }
        items("ring").forEach {
            val hue = ColorWheel.hue(it.getValue("dx").jsonPrimitive.double, it.getValue("dy").jsonPrimitive.double)
            val expected = it.getValue("hue").jsonPrimitive.doubleOrNull
            if (expected == null) assertNull(hue) else {
                assertEquals(expected, hue!!, 1e-9)
                val (dx, dy) = ColorWheel.ringDirection(expected)
                assertEquals(expected, ColorWheel.hue(dx, dy)!!, 1e-9)
            }
        }
        items("square").forEach {
            fun n(key: String) = it.getValue(key).jsonPrimitive.double
            val (saturation, value) = ColorWheel.square(n("x"), n("y"), n("width"), n("height"))
            assertEquals(n("saturation"), saturation, 1e-9)
            assertEquals(n("value"), value, 1e-9)
        }
        items("keepHue").forEach {
            val hex = it.getValue("hex").jsonPrimitive.content
            assertClose("keepHue $hex", ColorWheel.hsv(hex, triple(it.getValue("previous"))), triple(it.getValue("hsv")))
        }
        items("contrast").forEach {
            assertEquals(it.getValue("ratio").jsonPrimitive.double, ColorWheel.contrast(it.getValue("a").jsonPrimitive.content, it.getValue("b").jsonPrimitive.content)!!, 1e-4)
        }
        items("step").forEach {
            fun n(key: String) = it.getValue(key).jsonPrimitive.double
            assertEquals("step $it", n("result"), ColorWheel.step(n("level"), n("delta")), 1e-9)
        }
        items("name").forEach { assertEquals("name ${it["hsv"]}", it.getValue("name").jsonPrimitive.content, ColorWheel.name(triple(it.getValue("hsv")))) }
        items("needsOutline").forEach {
            val hex = it.getValue("hex").jsonPrimitive.content
            assertEquals("outline $hex", it.getValue("outline").jsonPrimitive.boolean, ColorWheel.needsOutline(hex))
        }
    }

    /** Aller-retour exact sur une grille RGB, et tour complet de teinte à un canal près. */
    @Test fun roundTripAndHueTurn() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val hex = "#%02X%02X%02X".format(r, g, b)
            assertEquals(hex, ColorWheel.hex(ColorWheel.hsv(hex)!!))
        }
        for (degree in 0 until 360) {
            val back = ColorWheel.hsv(ColorWheel.hex(ColorWheel.HSV(degree.toDouble(), 1.0, 1.0)))!!
            val distance = min(abs(back.hue - degree), 360 - abs(back.hue - degree))
            assertTrue("teinte $degree", distance <= 360.0 / 255)
        }
    }
}
