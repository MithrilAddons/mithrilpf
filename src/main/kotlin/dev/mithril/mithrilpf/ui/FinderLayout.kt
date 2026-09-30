package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.finder.DungeonRole

/** GUI units, independent of framebuffer resolution and Minecraft's selected GUI scale. */
data class FinderLayout(val panel: PanelLayout, val wide: Boolean) {
    val contentX
        get() = panel.x + 8

    val contentY
        get() = panel.y + if (wide) 34 else 54

    val contentWidth
        get() = (panel.width - 16).coerceAtLeast(1)

    val contentHeight
        get() = (panel.height - if (wide) 60 else 80).coerceAtLeast(1)

    // ScrollableLayout reserves ten units on both sides for its scrollbar.
    val pageWidth
        get() = (contentWidth - 20).coerceAtLeast(1)

    val columnWidth
        get() = if (wide) (pageWidth - 12) / 2 else pageWidth

    companion object {
        fun fit(width: Int, height: Int): FinderLayout {
            val w = (width - 16).coerceIn(1, 900)
            val h = (height - 16).coerceIn(1, 540)
            return FinderLayout(PanelLayout((width - w) / 2, (height - h) / 2, w, h), width >= 640)
        }
    }
}

enum class FinderTab(val key: String) {
    PARTIES("parties"),
    PARTY("party"),
    RECORDS("records"),
    SETTINGS("settings"),
}

/** Screen choices survive reopening, but never leak between Minecraft accounts. */
class FinderNavigation {
    private var account: String? = null
    var tab = FinderTab.PARTIES
    var floor = "M7"
    var roles = emptySet<DungeonRole>()
    var reserveRole: DungeonRole? = null
    var showUnavailable = false
    var sort = 0
    var maximumTime = ""
    val drafts = mutableMapOf<String, FinderDraft>()
    var chatParty: String? = null
    var chatDraft = ""
    var chatRequest: String? = null

    fun forAccount(value: String) {
        if (account == value) return
        account = value
        tab = FinderTab.PARTIES
        floor = "M7"
        roles = emptySet()
        reserveRole = null
        showUnavailable = false
        sort = 0
        maximumTime = ""
        drafts.clear()
        chatParty = null
        chatDraft = ""
        chatRequest = null
    }
}
