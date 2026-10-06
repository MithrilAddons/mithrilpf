package dev.mithril.mithrilpf.party

import dev.mithril.mithrilpf.ui.Palette
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component

internal class MinecraftPartyHost(private val client: Minecraft) : PartyHost {
    override val uuid
        get() = client.user.profileId.toString().replace("-", "")

    override val name
        get() = client.user.name

    override val connection
        get() = client.connection

    override val hasPlayer
        get() = client.player != null

    override val server
        get() = client.currentServer?.ip

    override fun proof(): (String) -> Unit {
        val user = client.user
        val service = client.services().sessionService()
        return { serverId -> service.joinServer(user.profileId, user.accessToken, serverId) }
    }

    override fun execute(action: () -> Unit) = client.execute(action)

    override fun command(command: String) {
        client.connection?.sendCommand(command)
    }

    override fun requestPartyInfo() =
        HypixelModAPI.getInstance().sendPacket(ServerboundPartyInfoPacket())

    override fun message(key: String) {
        client.player?.sendSystemMessage(Component.translatable("party.mithrilpf.$key"))
    }

    override fun chatMessage(message: PartyChatMessage) {
        client.player?.sendSystemMessage(
            Component.translatable("chat.mithrilpf.prefix")
                .withStyle { it.withColor(Palette.ACCENT and 0xFFFFFF) }
                .append(
                    Component.literal("${message.name}: ").withStyle {
                        it.withColor(Palette.HIGHLIGHT and 0xFFFFFF)
                    }
                )
                .append(
                    Component.literal(message.text).withStyle {
                        it.withColor(Palette.TEXT and 0xFFFFFF)
                    }
                )
        )
    }

    override fun chatStatus(key: String, retryText: String?) {
        val message =
            Component.translatable("chat.mithrilpf.$key").withStyle {
                it.withColor(Palette.ACCENT and 0xFFFFFF)
            }
        if (retryText != null)
            message.withStyle { it.withClickEvent(ClickEvent.SuggestCommand("/mpc $retryText")) }
        client.player?.sendSystemMessage(message)
    }
}
