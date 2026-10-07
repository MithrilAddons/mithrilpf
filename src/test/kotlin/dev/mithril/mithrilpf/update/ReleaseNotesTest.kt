package dev.mithril.mithrilpf.update

import kotlin.test.*

class ReleaseNotesTest {
    @Test
    fun `release notes become plain lines and omit the website update steps`() {
        val body =
            """
            Party invites now start only when you click.

            **Changes**
            - Use `/mpfinvite` or **click** in chat.
            * Requires [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.2.

            **Updating**
            Open `/mpf → Updates`, then select **Check now**.

            [Full changelog](https://github.com/MithrilAddons/mithrilpf/compare/a...b)
            <!-- mithrilpf-release-metrics {"tests":1} -->
            """
                .trimIndent()
        assertEquals(
            listOf(
                NoteLine("Party invites now start only when you click."),
                NoteLine("Changes", heading = true),
                NoteLine("Use /mpfinvite or click in chat.", bullet = true),
                NoteLine("Requires Hypixel Mod API 1.0.2.", bullet = true),
            ),
            ReleaseNotes.parse(body),
        )
    }

    @Test
    fun `hidden comments and unknown markup never reach the screen`() {
        assertEquals(
            listOf(NoteLine("Fixes a crash.")),
            ReleaseNotes.parse("<!-- hidden\nmetadata -->\nFixes a <b>crash</b>.\n\n"),
        )
        assertTrue(ReleaseNotes.parse("").isEmpty())
        assertEquals(200, ReleaseNotes.parse("- line\n".repeat(500)).size)
    }
}
