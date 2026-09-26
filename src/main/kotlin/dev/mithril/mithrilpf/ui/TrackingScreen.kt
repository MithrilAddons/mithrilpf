package dev.mithril.mithrilpf.ui

import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.dungeontimer.TrackingSettings
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class TrackingScreen(private val parent: Screen?) : Screen(DungeonTimers.message("title")) {
    private var wasReady = false

    override fun init() {
        wasReady = DungeonTimers.ready
        val x = (width - 300) / 2
        val y = (height - 220) / 2
        val toggles:
            List<
                Triple<
                    String,
                    (TrackingSettings) -> Boolean,
                    (TrackingSettings) -> TrackingSettings,
                >
            > =
            listOf(
                Triple("enabled", { it.enabled }, { it.copy(enabled = !it.enabled) }),
                Triple("rooms", { it.rooms }, { it.copy(rooms = !it.rooms) }),
                Triple("solo", { it.solo }, { it.copy(solo = !it.solo) }),
                Triple("table", { it.table }, { it.copy(table = !it.table) }),
                Triple("split", { it.split }, { it.copy(split = !it.split) }),
                Triple("ticks", { it.ticks }, { it.copy(ticks = !it.ticks) }),
                Triple(
                    "dungeon_only",
                    { it.dungeonOnly },
                    { it.copy(dungeonOnly = !it.dungeonOnly) },
                ),
                Triple("paul", { it.paul }, { it.copy(paul = !it.paul) }),
            )
        toggles.forEachIndexed { index, (key, get, change) ->
            lateinit var button: FlatButton
            fun label() =
                DungeonTimers.message(
                    "toggle",
                    DungeonTimers.message(key),
                    Component.translatable(
                        if (get(DungeonTimers.settings)) "options.on" else "options.off"
                    ),
                )
            button =
                addRenderableWidget(
                    FlatButton(x + index % 2 * 154, y + 32 + index / 2 * 26, 146, label()) {
                        DungeonTimers.update(change(DungeonTimers.settings))
                        button.message = label()
                    }
                )
            button.active = DungeonTimers.ready
        }
        addRenderableWidget(
                FlatButton(x, y + 144, 300, DungeonTimers.message("edit")) {
                    minecraft.setScreen(TrackingHudEditor(this))
                }
            )
            .active = DungeonTimers.ready
        addRenderableWidget(
            FlatButton(x, y + 188, 300, Component.translatable("gui.done")) { onClose() }
        )
    }

    override fun tick() {
        if (wasReady != DungeonTimers.ready) rebuildWidgets()
    }

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        g.fill(0, 0, width, height, Palette.BACKGROUND)
        g.text(font, title, (width - 300) / 2, (height - 220) / 2 + 10, Palette.TEXT, false)
        g.text(
            font,
            DungeonTimers.message(if (DungeonTimers.ready) "local_only" else "storage_error"),
            (width - 300) / 2,
            (height - 220) / 2 + 172,
            Palette.MUTED,
            false,
        )
        super.extractRenderState(g, mouseX, mouseY, delta)
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen() = false
}
