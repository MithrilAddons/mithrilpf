package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.ui.UpdatePromptModel.Action
import kotlin.test.*

class UpdatePromptModelTest {
    @Test
    fun `each state offers only the actions that make sense`() {
        assertEquals(
            listOf(Action.INSTALL, Action.REMIND, Action.SKIP),
            UpdatePromptModel.actions("available"),
        )
        assertEquals(listOf(Action.INSTALL, Action.REMIND), UpdatePromptModel.actions("skipped"))
        assertEquals(
            listOf(Action.INSTALL_ALL, Action.DONE),
            UpdatePromptModel.actions("dependencies"),
        )
        assertEquals(listOf(Action.CANCEL, Action.DONE), UpdatePromptModel.actions("ready"))
        assertEquals(listOf(Action.CANCEL, Action.DONE), UpdatePromptModel.actions("downloading"))
        assertEquals(listOf(Action.RETRY, Action.DONE), UpdatePromptModel.actions("failed"))
        assertEquals(listOf(Action.DONE), UpdatePromptModel.actions("incompatible"))
        assertEquals(1, Action.entries.count { it.danger })
        assertNull(UpdatePromptModel.statusKey("available"))
        assertEquals("update.mithrilpf.ready", UpdatePromptModel.statusKey("ready"))
    }

    @Test
    fun `wide windows use a button column and narrow ones stack below the notes`() {
        val wide = UpdatePromptModel.panel(960, 540)
        assertEquals(PanelLayout(270, 140, 420, 260), wide)
        val columns = UpdatePromptModel.layout(wide, 0, 3, 2)
        assertEquals(128, columns.column.width)
        assertTrue(columns.notes.x + columns.notes.width < columns.column.x)
        assertEquals(columns.column.y, columns.firstButton)
        assertEquals(columns.column.y + columns.column.height - 48, columns.firstLink)

        val narrow = UpdatePromptModel.panel(300, 400)
        val stacked = UpdatePromptModel.layout(narrow, 16, 2, 2)
        assertEquals(narrow.width - 24, stacked.column.width)
        assertEquals(stacked.column.y + 16, stacked.firstButton)
        assertEquals(stacked.firstButton + 2 * 24 + 8, stacked.firstLink)
        assertTrue(stacked.notes.y + stacked.notes.height < stacked.column.y)
        assertTrue(stacked.notes.height >= 32)
    }
}
