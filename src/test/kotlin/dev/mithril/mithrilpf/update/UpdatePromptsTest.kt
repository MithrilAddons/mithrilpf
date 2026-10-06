package dev.mithril.mithrilpf.update

import dev.mithril.mithrilpf.update.UpdatePrompts.Show
import kotlin.test.*

class UpdatePromptsTest {
    private fun status(state: String, version: String = "0.3.0") =
        UpdateStatus(loaded = true, state = state, version = version)

    @Test
    fun `first run asks once per launch and only over the title screen`() {
        val prompts = UpdatePrompts()
        assertEquals(Show.NONE, prompts.next(status("ask"), titleScreen = false, inWorld = true))
        assertEquals(Show.OPT_IN, prompts.next(status("ask"), titleScreen = true, inWorld = false))
        assertEquals(Show.NONE, prompts.next(status("ask"), titleScreen = true, inWorld = false))
    }

    @Test
    fun `an available release prompts once per version and never mid game`() {
        val prompts = UpdatePrompts()
        assertEquals(
            Show.NOTICE,
            prompts.next(status("available"), titleScreen = false, inWorld = true),
        )
        assertEquals(
            Show.NONE,
            prompts.next(status("available"), titleScreen = false, inWorld = true),
        )
        assertEquals(
            Show.PROMPT,
            prompts.next(status("available"), titleScreen = true, inWorld = false),
        )
        assertEquals(
            Show.NONE,
            prompts.next(status("available"), titleScreen = true, inWorld = false),
        )
        assertEquals(
            Show.PROMPT,
            prompts.next(status("available", "0.4.0"), titleScreen = true, inWorld = false),
        )
        for (state in
            listOf("skipped", "ready", "downloading", "current", "disabled")) assertEquals(
            Show.NONE,
            prompts.next(status(state, "0.5.0"), true, false),
            state,
        )
    }

    @Test
    fun `remind me later silences that version for the launch`() {
        val prompts = UpdatePrompts()
        prompts.remindLater("0.3.0")
        assertEquals(
            Show.NONE,
            prompts.next(status("available"), titleScreen = true, inWorld = false),
        )
        assertEquals(
            Show.NONE,
            prompts.next(status("available"), titleScreen = false, inWorld = true),
        )
        assertEquals(
            Show.PROMPT,
            prompts.next(status("available", "0.4.0"), titleScreen = true, inWorld = false),
        )
    }
}
