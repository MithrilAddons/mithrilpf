package dev.mithril.mithrilpf

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import dev.mithril.mithrilpf.ui.FloorPicker
import dev.mithril.mithrilpf.ui.Palette
import net.minecraft.network.chat.Component

/** What the commands do; replies are shown in chat with the MithrilPF prefix. */
interface ModActions {
    fun open()

    /** Opens Records, on [floor] when one is given. */
    fun records(floor: String?)

    fun status(): Component

    fun invite()

    fun chat(message: String)

    fun games()

    fun reply(message: Component)
}

/**
 * Every command lives under /mpf; /mpc and /games stay because they're typed mid-game. The old
 * names are not registered, so they stay out of tab completion and help, but [legacy] still runs
 * them when they're sent.
 */
object ModCommands {
    private val FLOORS = FloorPicker.FLOORS

    private val HELP =
        listOf(
            "/mpf" to "open",
            "/mpf pbs [floor]" to "pbs",
            "/mpf status" to "status",
            "/mpf invite" to "invite",
            "/mpf chat <message>" to "chat",
            "/mpf games" to "games",
        )

    fun <S> register(dispatcher: CommandDispatcher<S>, actions: ModActions) {
        fun run(action: () -> Unit) =
            Command<S> {
                action()
                Command.SINGLE_SUCCESS
            }
        fun chat() =
            argument<S, String>("message", StringArgumentType.greedyString()).executes {
                actions.chat(StringArgumentType.getString(it, "message"))
                Command.SINGLE_SUCCESS
            }
        val chatHelp = run { actions.reply(Component.translatable("chat.mithrilpf.help")) }
        dispatcher.register(
            literal<S>("mpf")
                .executes(run(actions::open))
                .then(literal<S>("help").executes(run { actions.reply(help()) }))
                .then(
                    literal<S>("pbs")
                        .executes(run { actions.records(null) })
                        .then(
                            argument<S, String>("floor", StringArgumentType.word())
                                .suggests { _, builder ->
                                    FLOORS.filter {
                                            it.startsWith(builder.remaining, ignoreCase = true)
                                        }
                                        .forEach(builder::suggest)
                                    builder.buildFuture()
                                }
                                .executes {
                                    pbs(StringArgumentType.getString(it, "floor"), actions)
                                    Command.SINGLE_SUCCESS
                                }
                        )
                )
                .then(literal<S>("status").executes(run { actions.reply(actions.status()) }))
                .then(literal<S>("invite").executes(run(actions::invite)))
                .then(literal<S>("chat").executes(chatHelp).then(chat()))
                .then(literal<S>("games").executes(run(actions::games)))
        )
        dispatcher.register(literal<S>("mpc").executes(chatHelp).then(chat()))
        // /mpf games still works if another mod or server claims /games.
        dispatcher.register(literal<S>("games").executes(run(actions::games)))
    }

    private fun pbs(floor: String, actions: ModActions) {
        val match = FLOORS.firstOrNull { it.equals(floor, ignoreCase = true) }
        if (match != null) actions.records(match)
        else
            actions.reply(
                Component.translatable("command.mithrilpf.unknown_floor", floor).withStyle {
                    it.withColor(Palette.DANGER and 0xFFFFFF)
                }
            )
    }

    fun help(): Component {
        val text =
            Component.translatable("command.mithrilpf.help").withStyle {
                it.withColor(Palette.ACCENT and 0xFFFFFF)
            }
        for ((command, key) in HELP) {
            text
                .append("\n")
                .append(
                    Component.literal(command).withStyle { it.withColor(Palette.TEXT and 0xFFFFFF) }
                )
                .append("  ")
                .append(
                    Component.translatable("command.mithrilpf.help.$key").withStyle {
                        it.withColor(Palette.MUTED and 0xFFFFFF)
                    }
                )
        }
        return text
    }

    /**
     * Runs a command sent under an old name (without the slash) and returns true, or returns false
     * for anything else.
     */
    fun legacy(command: String, actions: ModActions): Boolean {
        val name = command.substringBefore(' ')
        val rest = command.substringAfter(' ', "").trim()
        when (name) {
            "mithrilpf" -> actions.open()
            "mithrilpfpbs" -> actions.records(null)
            "mithrilpfstatus" -> actions.reply(actions.status())
            "mpfinvite",
            "mithrilpfinvite",
            "mithrilpfreinvite" -> actions.invite()
            "mithrilpfchat" ->
                if (rest.isEmpty()) actions.reply(Component.translatable("chat.mithrilpf.help"))
                else actions.chat(rest)
            "mpfgames" -> actions.games()
            else -> return false
        }
        return true
    }
}
