package dev.mithril.mithrilpf

import com.mojang.blaze3d.platform.InputConstants
import dev.mithril.mithrilpf.account.BrowserLink
import dev.mithril.mithrilpf.dungeontimer.DungeonTimers
import dev.mithril.mithrilpf.sync.RecordSync
import dev.mithril.mithrilpf.ui.PartyFinderScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

object MithrilPF : ClientModInitializer {
    private lateinit var browserLink: BrowserLink
    private lateinit var recordSync: RecordSync
    val syncStatus: String
        get() = if (::recordSync.isInitialized) recordSync.status else "waiting"

    private var openRequested = false

    override fun onInitializeClient() {
        val client = Minecraft.getInstance()
        browserLink = BrowserLink(client)
        recordSync = RecordSync(client)
        DungeonTimers.register()
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
            dispatcher.register(
                literal("mithrilpf").executes {
                    openRequested = true
                    1
                }
            )
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            recordSync.tick()
            while (key.consumeClick()) openRequested = true
            if (openRequested) {
                openRequested = false
                // Command chat must finish closing before the new screen opens.
                client.setScreen(screen(client.screen))
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            browserLink.close()
            recordSync.close()
        }
    }

    fun screen(parent: Screen?): Screen = PartyFinderScreen(parent, browserLink)
}
