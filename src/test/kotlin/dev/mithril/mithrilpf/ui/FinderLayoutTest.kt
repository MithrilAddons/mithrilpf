package dev.mithril.mithrilpf.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinderLayoutTest {
    @Test
    fun contentStaysBetweenNavigationAndFooterAtSupportedGuiScales() {
        for ((width, height) in
            listOf(320 to 240, 427 to 240, 480 to 270, 640 to 360, 960 to 540, 1920 to 1080)) {
            val layout = FinderLayout.fit(width, height)
            val p = layout.panel
            assertTrue(p.x >= 8 && p.y >= 8)
            assertTrue(p.x + p.width <= width - 8)
            assertTrue(p.y + p.height <= height - 8)
            assertTrue(layout.contentY >= p.y + if (layout.wide) 30 else 46)
            assertTrue(layout.contentY + layout.contentHeight <= p.y + p.height - 22)
            assertTrue(layout.pageWidth + 20 <= layout.contentWidth)
            val columns = if (layout.wide) 2 * layout.columnWidth + 12 else layout.columnWidth
            assertTrue(columns <= layout.pageWidth)
            assertTrue(layout.columnWidth >= 200)
        }
    }

    @Test
    fun compactModeUsesAvailableWidthAndSwitchesAt640GuiUnits() {
        assertFalse(FinderLayout.fit(480, 270).wide)
        assertFalse(FinderLayout.fit(639, 360).wide)
        assertTrue(FinderLayout.fit(640, 360).wide)
        val compact = FinderLayout.fit(480, 270)
        assertEquals(compact.pageWidth, compact.columnWidth)
    }

    @Test
    fun navigationSurvivesReopeningButResetsOnAccountSwitch() {
        val navigation = FinderNavigation()
        navigation.forAccount("synthetic-one")
        navigation.tab = FinderTab.SETTINGS
        navigation.floor = "F7"
        navigation.forAccount("synthetic-one")
        assertEquals(FinderTab.SETTINGS, navigation.tab)
        assertEquals("F7", navigation.floor)
        navigation.forAccount("synthetic-two")
        assertEquals(FinderTab.PARTIES, navigation.tab)
        assertEquals("M7", navigation.floor)
        navigation.forAccount("synthetic-one")
        assertEquals(FinderTab.PARTIES, navigation.tab)
    }
}
