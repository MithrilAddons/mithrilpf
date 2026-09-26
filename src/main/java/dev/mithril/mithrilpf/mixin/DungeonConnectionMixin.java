package dev.mithril.mithrilpf.mixin;

import dev.mithril.mithrilpf.dungeontimer.DungeonTimers;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class DungeonConnectionMixin {
    // Read-only observation before other mods hide chat or replace the tab list.
    @Inject(
            method =
                    "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"))
    private void mithrilpf$observe(
            ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        DungeonTimers.onPacket((Connection) (Object) this, packet);
    }
}
