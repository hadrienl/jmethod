package fr.jmethod.app

import androidx.compose.ui.unit.dp
import fr.jmethod.app.ui.components.NavigationMode
import fr.jmethod.app.ui.components.TwoColumnWidth
import fr.jmethod.app.ui.components.WindowLayout
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class WindowLayoutTest {
    @Test
    fun navigationReservesOnlyItsOwnWidth() {
        val widths = listOf(599, 600, 1199, 1200, 1599, 1600, 1920, 2560)
        val expected = listOf(599, 519, 1118, 960, 1359, 1360, 1680, 2320)
        assertEquals(expected.map { it.dp }, widths.map { WindowLayout(it.dp, 800.dp).contentWidth })
        assertEquals(NavigationMode.BOTTOM, WindowLayout(599.dp, 800.dp).navigationMode)
        assertEquals(NavigationMode.RAIL, WindowLayout(600.dp, 800.dp).navigationMode)
        assertEquals(NavigationMode.SIDEBAR, WindowLayout(1200.dp, 320.dp).navigationMode)
    }

    @Test
    fun detailRequiresRoomForBothPanesAndTakesPriorityOverDashboardColumns() {
        assertFalse(WindowLayout(1021.dp, 800.dp).twoPane)
        assertTrue(WindowLayout(1022.dp, 800.dp).twoPane)
        assertFalse(WindowLayout(1200.dp, 479.dp).twoPane)
        val layout = WindowLayout(1200.dp, 480.dp)
        assertTrue(layout.twoPane)
        assertEquals(TwoColumnWidth, layout.listWidth(docked = false))
        assertEquals(499.dp, layout.listWidth(docked = true))
        assertTrue(WindowLayout(1920.dp, 800.dp).listWidth(docked = true) >= TwoColumnWidth)
    }
}
