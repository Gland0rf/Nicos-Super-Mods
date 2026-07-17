package com.nico.mixin;

import com.nico.client.lag.LagMonitorService;
import com.nico.client.secretTimer.SecretPacketHooks;
import com.nico.client.utils.LocationUtils;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
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
            runOnClientThread(() ->
                    SecretPacketHooks.onUseItemOnPacket(useItemOnPacket)
            );
        }
    }

    @Inject(
            method = "channelRead0",
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
            handleLocationPacket(packet);
            handleSecretPacket(packet);
        });
    }

    private static void handleLocationPacket(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket playerInfoPacket) {
            LocationUtils.onPlayerInfoUpdate(playerInfoPacket);
        } else if (packet instanceof ClientboundSetObjectivePacket objectivePacket) {
            LocationUtils.onSetObjective(objectivePacket);
        } else if (packet instanceof ClientboundSetPlayerTeamPacket teamPacket) {
            LocationUtils.onSetPlayerTeam(teamPacket);
        }
    }

    private static void handleSecretPacket(Packet<?> packet) {
        if (packet instanceof ClientboundTakeItemEntityPacket takeItemPacket) {
            SecretPacketHooks.onTakeItemEntityPacket(takeItemPacket);
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removeEntitiesPacket) {
            SecretPacketHooks.onRemoveEntitiesPacket(removeEntitiesPacket);
        } else if (packet instanceof ClientboundSoundPacket soundPacket) {
            SecretPacketHooks.onSoundPacket(soundPacket);
        } else if (packet instanceof ClientboundSystemChatPacket systemChatPacket) {
            SecretPacketHooks.onSystemChatPacket(systemChatPacket);
        }
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