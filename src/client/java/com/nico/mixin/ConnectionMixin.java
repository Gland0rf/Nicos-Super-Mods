package com.nico.mixin;

import com.nico.client.dungeon.SecretDispatcher;
import com.nico.client.lag.DungeonRunPacketDetector;
import com.nico.client.lag.LagMonitorService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Connection.class, priority = 100)
public abstract class ConnectionMixin {

    @Inject(method = "sendPacket", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$sendPacket(
            Packet<?> packet,
            ChannelFutureListener listener,
            boolean flush,
            CallbackInfo ci
    ) {
        if (packet instanceof ServerboundUseItemOnPacket useItemOnPacket) {
            runOnClientThread(() -> {
                SecretDispatcher.onSend(useItemOnPacket);
            });
        }
    }

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            require = 0,
            order = 900
    )
    private void nsm$receivePacket(
            ChannelHandlerContext context,
            Packet<?> packet,
            CallbackInfo ci
    ) {
        LagMonitorService.getInstance().onInboundPacket(packet);

        runOnClientThread(() -> {
            DungeonRunPacketDetector.handle(packet);

            if (packet instanceof ClientboundTakeItemEntityPacket
                    || packet instanceof ClientboundSoundPacket
                    || packet instanceof ClientboundSystemChatPacket) {

                SecretDispatcher.onReceive(packet);
            }
        });
    }

    private static void runOnClientThread(Runnable runnable) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.isSameThread()) {
            runnable.run();
        } else {
            mc.execute(runnable);
        }
    }
}