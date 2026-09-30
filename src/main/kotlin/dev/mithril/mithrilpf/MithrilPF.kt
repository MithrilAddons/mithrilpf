package dev.mithril.mithrilpf

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import dev.mithril.mithrilpf.account.BrowserLink
import dev.mithril.mithrilpf.account.NativeAccount
import dev.mithril.mithrilpf.discord.DiscordActivityModel
import dev.mithril.mithrilpf.discord.DiscordPresence
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.dungeontimer.TimerStamp
import dev.mithril.mithrilpf.finder.FinderClient
import dev.mithril.mithrilpf.party.PartyClient
import dev.mithril.mithrilpf.sync.RecordSync
import dev.mithril.mithrilpf.ui.FinderNavigation
import dev.mithril.mithrilpf.ui.PartyFinderScreen
import dev.mithril.mithrilpf.update.ModUpdates
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

object MithrilPF : ClientModInitializer {
    lateinit var nativeAccount: NativeAccount
        private set

    lateinit var finder: FinderClient
        private set

    private lateinit var browserLink: BrowserLink
    private lateinit var recordSync: RecordSync
    private lateinit var parties: PartyClient
    private lateinit var discord: DiscordPresence
    private val discordModel = DiscordActivityModel()
    private var nextDiscordUpdate = 0L
    lateinit var updates: ModUpdates
        private set

    val partyStatus: String
        get() = if (::parties.isInitialized) parties.status else "waiting"

    val syncStatus: String
        get() = if (::recordSync.isInitialized) recordSync.status else "waiting"

    private var openRequested = false
    private val finderNavigation = FinderNavigation()

    val partyHandoff
        get() = if (::parties.isInitialized) parties.party else null

    fun retryPartyInvites() {
        if (::parties.isInitialized) parties.reinvite()
    }

    override fun onInitializeClient() {
        val client = Minecraft.getInstance()
        browserLink = BrowserLink(client)
        nativeAccount = NativeAccount(client)
        finder = FinderClient(client, nativeAccount)
        recordSync = RecordSync(client)
        parties = PartyClient(client)
        discord = DiscordPresence()
        updates = ModUpdates(client)
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            // ALLOW_GAME still visits every listener when another mod hides the message.
            if (!overlay) parties.chat(message.string)
            true
        }
        DungeonTimers.register()
        dev.mithril.mithrilpf.ui.FinderHud.register()
        val key =
            KeyMappingHelper.registerKeyMapping(
                KeyMapping(
                    "key.mithrilpf.open",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath("mithrilpf", "main")
                    ),
                )
            )
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            for (name in listOf("mpc", "mithrilpfchat")) dispatcher.register(
                literal(name)
                    .executes {
                        client.player?.sendSystemMessage(
                            Component.translatable("chat.mithrilpf.help")
                        )
                        1
                    }
                    .then(
                        argument("message", StringArgumentType.greedyString()).executes {
                            parties.sendChat(StringArgumentType.getString(it, "message"))
                            1
                        }
                    )
            )
            dispatcher.register(
                literal("mithrilpfreinvite").executes {
                    parties.reinvite()
                    1
                }
            )
            dispatcher.register(
                literal("mithrilpf").executes {
                    openRequested = true
                    1
                }
            )
            dispatcher.register(
                literal("mpf").executes {
                    openRequested = true
                    1
                }
            )
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            nativeAccount.tick()
            finder.tick(client.screen is PartyFinderScreen)
            recordSync.tick()
            parties.tick(nativeAccount.session?.token, nativeAccount.status == "signing_out")
            updates.tick()
            val now = System.nanoTime()
            if (now >= nextDiscordUpdate) {
                nextDiscordUpdate = now + 1_000_000_000L
                discord.update(
                    discordModel.activity(
                        DungeonTimers.ready && DungeonTimers.settings.discordPresence,
                        DungeonTimers.settings.enabled && DungeonTimers.inDungeon,
                        DungeonTimers.floor,
                        DungeonTimers.state,
                        TimerStamp(DungeonTimers.ticks, now),
                        System.currentTimeMillis(),
                        parties.activity,
                    )
                )
            }
            while (key.consumeClick()) openRequested = true
            if (openRequested) {
                openRequested = false
                // Command chat must finish closing before the new screen opens.
                client.setScreen(screen(client.screen))
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            nativeAccount.close()
            finder.close()
            browserLink.close()
            recordSync.close()
            parties.close()
            updates.close()
            discord.close()
        }
    }

    fun screen(parent: Screen?): Screen = PartyFinderScreen(parent, browserLink, finderNavigation)
}
