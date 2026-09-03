package com.nico.mixin;

import com.nico.client.dungeon.DungeonState;
import com.nico.client.dungeon.DungeonStatsTracker;
import com.nico.client.utils.LocationUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 100)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handlePlayerInfoUpdate(
            ClientboundPlayerInfoUpdatePacket packet,
            CallbackInfo ci
    ) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            LocationUtils.onTabDisplayName(entry.displayName());
            DungeonStatsTracker.onTabDisplayName(entry.displayName());
        }
    }

    @Inject(method = "handleAddObjective", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleSetObjective(
            ClientboundSetObjectivePacket packet,
            CallbackInfo ci
    ) {
        LocationUtils.onScoreboardObjective(packet.getObjectiveName());
    }

    @Inject(method = "handleSetPlayerTeamPacket", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleSetPlayerTeamPacket(
            ClientboundSetPlayerTeamPacket packet,
            CallbackInfo ci
    ) {
        packet.getParameters().ifPresent(parameters -> {
            DungeonState.onScoreboardText(
                    parameters.getPlayerPrefix(),
                    parameters.getPlayerSuffix()
            );

            LocationUtils.onTeamText(
                    parameters.getPlayerPrefix(),
                    parameters.getPlayerSuffix()
            );
        });
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleRespawn(
            ClientboundRespawnPacket packet,
            CallbackInfo ci
    ) {
        DungeonStatsTracker.reset();
    }
}