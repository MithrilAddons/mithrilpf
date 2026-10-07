package dev.mithril.mithrilpf.ui

/** What the update prompt offers in each state and where it goes; free of Minecraft for tests. */
internal object UpdatePromptModel {
    enum class Action(val key: String, val primary: Boolean = false, val danger: Boolean = false) {
        INSTALL("update.mithrilpf.install", primary = true),
        INSTALL_ALL("update.mithrilpf.install_all", primary = true),
        RETRY("update.mithrilpf.retry", primary = true),
        REMIND("update.mithrilpf.remind"),
        SKIP("update.mithrilpf.skip", danger = true),
        CANCEL("update.mithrilpf.cancel_install"),
        DONE("gui.done"),
    }

    fun actions(state: String): List<Action> =
        when (state) {
            "available" -> listOf(Action.INSTALL, Action.REMIND, Action.SKIP)
            "skipped" -> listOf(Action.INSTALL, Action.REMIND)
            "dependencies" -> listOf(Action.INSTALL_ALL, Action.DONE)
            "downloading",
            "ready" -> listOf(Action.CANCEL, Action.DONE)
            "failed" -> listOf(Action.RETRY, Action.DONE)
            else -> listOf(Action.DONE)
        }

    /** The status line shown above the buttons; none while the release is simply on offer. */
    fun statusKey(state: String): String? =
        if (state in setOf("available", "skipped")) null else "update.mithrilpf.$state"

    fun panel(screenWidth: Int, screenHeight: Int): PanelLayout {
        val width = (screenWidth - 16).coerceIn(1, 420)
        val height = (screenHeight - 16).coerceIn(1, 260)
        return PanelLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height)
    }

    /** Wide panels put buttons in a right-hand column; narrow ones stack them under the notes. */
    fun wide(panel: PanelLayout) = panel.width >= 320

    fun columnWidth(panel: PanelLayout) = if (wide(panel)) 128 else panel.width - 24

    data class Layout(
        val notes: PanelLayout,
        val column: PanelLayout,
        val firstButton: Int,
        val firstLink: Int,
    )

    fun layout(panel: PanelLayout, statusHeight: Int, buttons: Int, links: Int): Layout {
        val top = panel.y + 38
        val bottom = panel.y + panel.height - 12
        val width = columnWidth(panel)
        val column =
            if (wide(panel))
                PanelLayout(panel.x + panel.width - 12 - width, top, width, bottom - top)
            else {
                val stack = statusHeight + (buttons + links) * 24 + if (links > 0) 8 else 0
                val height = stack.coerceAtMost(bottom - top - 40)
                PanelLayout(panel.x + 12, bottom - height, width, height)
            }
        val notes =
            if (wide(panel)) PanelLayout(panel.x + 12, top, column.x - 24 - panel.x, bottom - top)
            else PanelLayout(panel.x + 12, top, panel.width - 24, column.y - 8 - top)
        val firstButton = column.y + statusHeight
        val firstLink =
            if (wide(panel)) column.y + column.height - 48 else firstButton + buttons * 24 + 8
        return Layout(notes, column, firstButton, firstLink)
    }
}
