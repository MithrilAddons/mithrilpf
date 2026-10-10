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

    @Test
    fun recordsStartOnTheFloorLastPlayedUntilAnotherIsPicked() {
        val navigation = FinderNavigation()
        navigation.forAccount("synthetic-one")
        assertEquals("M7", navigation.recordsFloor(null) { false })
        assertEquals("F5", navigation.recordsFloor(null) { it == "F5" || it == "E" })
        assertEquals("F7", navigation.recordsFloor("F7") { false })
        navigation.chooseRecordsFloor("M3")
        assertEquals("M3", navigation.recordsFloor("F7") { false })
        assertEquals("M3", navigation.recordsFloor(null) { false })
        // Playing another floor brings Records to it.
        assertEquals("M6", navigation.recordsFloor("M6") { false })
    }

    @Test
    fun recordsCommandOpensPbsOnTheFloorAsked() {
        val navigation = FinderNavigation()
        navigation.forAccount("synthetic-one")
        navigation.showRecords("F5", "M7")
        assertEquals(FinderTab.RECORDS, navigation.tab)
        assertEquals(RecordsView.SPLITS, navigation.recordsView)
        assertEquals("F5", navigation.recordsFloor("M7") { false })
        navigation.recordsView = RecordsView.ROOMS
        navigation.showRecords(null, "M7")
        assertEquals(RecordsView.ROOMS, navigation.recordsView)
        assertEquals("F5", navigation.recordsFloor("M7") { false })
        navigation.recordsMidclear = false
        navigation.recordsQuery = "altar"
        navigation.forAccount("synthetic-two")
        assertEquals(RecordsView.OVERVIEW, navigation.recordsView)
        assertTrue(navigation.recordsMidclear)
        assertEquals("", navigation.recordsQuery)
        assertEquals("M7", navigation.recordsFloor(null) { false })
    }
}
