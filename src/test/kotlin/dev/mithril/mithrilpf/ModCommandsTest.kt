package dev.mithril.mithrilpf

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents

class ModCommandsTest {
    private class Recorder : ModActions {
        val calls = mutableListOf<String>()
        val replies = mutableListOf<Component>()

        override fun open() {
            calls += "open"
        }

        override fun records(floor: String?) {
            calls += "records $floor"
        }

        override fun status(): Component = Component.literal("status line")

        override fun invite() {
            calls += "invite"
        }

        override fun chat(message: String) {
            calls += "chat $message"
        }

        override fun games() {
            calls += "games"
        }

        override fun reply(message: Component) {
            replies += message
        }
    }

    private val actions = Recorder()
    private val dispatcher = CommandDispatcher<Any>().also { ModCommands.register(it, actions) }

    private fun run(command: String) = dispatcher.execute(command, Any())

    private fun key(component: Component) = (component.contents as TranslatableContents).key

    @Test
    fun `every command runs its action under mpf`() {
        for (command in
            listOf(
                "mpf",
                "mpf pbs",
                "mpf pbs m7",
                "mpf pbs E",
                "mpf invite",
                "mpf chat hello there",
                "mpf games",
            )) run(command)
        assertEquals(
            listOf(
                "open",
                "records null",
                "records M7",
                "records E",
                "invite",
                "chat hello there",
                "games",
            ),
            actions.calls,
        )
    }

    @Test
    fun `the short commands typed mid-game stay`() {
        run("mpc gg")
        run("games")
        run("mpc")
        assertEquals(listOf("chat gg", "games"), actions.calls)
        assertEquals("chat.mithrilpf.help", key(actions.replies.single()))
    }

    @Test
    fun `status and chat without a message reply in chat`() {
        run("mpf status")
        run("mpf chat")
        assertEquals("status line", actions.replies[0].string)
        assertEquals("chat.mithrilpf.help", key(actions.replies[1]))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `an unknown floor is explained instead of opening Records`() {
        run("mpf pbs F9")
        val reply = actions.replies.single()
        assertEquals("command.mithrilpf.unknown_floor", key(reply))
        assertEquals("F9", (reply.contents as TranslatableContents).args.single())
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `help lists only the new names`() {
        run("mpf help")
        val help = actions.replies.single()
        val lines = help.siblings.map { it.string }.filter { it.startsWith("/") }
        assertEquals(
            listOf(
                "/mpf",
                "/mpf pbs [floor]",
                "/mpf status",
                "/mpf invite",
                "/mpf chat <message>",
                "/mpf games",
            ),
            lines,
        )
        assertEquals("command.mithrilpf.help", key(help))
    }

    @Test
    fun `floors and subcommands complete`() {
        fun suggest(input: String) =
            dispatcher.getCompletionSuggestions(dispatcher.parse(input, Any())).get().list.map {
                it.text
            }
        assertEquals(listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7"), suggest("mpf pbs m"))
        assertEquals(15, suggest("mpf pbs ").size)
        assertEquals(
            listOf("chat", "games", "help", "invite", "pbs", "status"),
            suggest("mpf ").sorted(),
        )
    }

    @Test
    fun `old names are not registered`() {
        for (name in
            listOf(
                "mithrilpf",
                "mithrilpfchat",
                "mpfinvite",
                "mithrilpfinvite",
                "mithrilpfreinvite",
                "mpfgames",
                "mithrilpfpbs",
                "mithrilpfstatus",
            )) assertFailsWith<CommandSyntaxException> { run(name) }
    }

    @Test
    fun `old names still run when sent`() {
        for (command in
            listOf(
                "mithrilpf",
                "mithrilpfpbs",
                "mpfinvite",
                "mithrilpfinvite",
                "mithrilpfreinvite",
                "mithrilpfchat  hi all ",
                "mpfgames",
            )) assertTrue(ModCommands.legacy(command, actions))
        assertEquals(
            listOf("open", "records null", "invite", "invite", "invite", "chat hi all", "games"),
            actions.calls,
        )
        assertTrue(ModCommands.legacy("mithrilpfstatus", actions))
        assertTrue(ModCommands.legacy("mithrilpfchat", actions))
        assertEquals("status line", actions.replies[0].string)
        assertEquals("chat.mithrilpf.help", key(actions.replies[1]))
    }

    @Test
    fun `other commands are left alone`() {
        for (command in
            listOf("mpf", "mpc hi", "pc hi", "mithrilpfx", "warp dungeon_hub")) assertFalse(
            ModCommands.legacy(command, actions)
        )
        assertTrue(actions.calls.isEmpty())
    }
}
