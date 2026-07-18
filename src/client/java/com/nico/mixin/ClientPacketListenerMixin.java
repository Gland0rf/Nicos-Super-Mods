package com.nico.mixin;

import com.nico.client.secretTimer.SecretPacketHooks;
import com.nico.client.utils.LocationUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, priority = 100)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleTakeItemEntity(
            ClientboundTakeItemEntityPacket packet,
            CallbackInfo ci
    ) {
        SecretPacketHooks.onTakeItemEntityPacket(packet);
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleRemoveEntities(
            ClientboundRemoveEntitiesPacket packet,
            CallbackInfo ci
    ) {
        SecretPacketHooks.onRemoveEntitiesPacket(packet);
    }

    @Inject(method = "handleSoundEvent", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleSoundEvent(
            ClientboundSoundPacket packet,
            CallbackInfo ci
    ) {
        SecretPacketHooks.onSoundPacket(packet);
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handleSystemChat(
            ClientboundSystemChatPacket packet,
            CallbackInfo ci
    ) {
        SecretPacketHooks.onSystemChatPacket(packet);
    }

    @Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"), require = 1, order = 900)
    private void nsm$handlePlayerInfoUpdate(
            ClientboundPlayerInfoUpdatePacket packet,
            CallbackInfo ci
    ) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME)) {
            return;
        }

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            LocationUtils.onTabDisplayName(entry.displayName());
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
            LocationUtils.onTeamText(
                    parameters.getPlayerPrefix(),
                    parameters.getPlayerSuffix()
            );
        });
    }
}