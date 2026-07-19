package com.nico.client.secretTimer;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretPacketHooks {
    private SecretPacketHooks() {}

    private static final Pattern SECRET_COUNTER_REGEX =
            Pattern.compile("(\\d+)/(\\d+) Secrets");

    private static final Pattern CONTROL_CODES =
            Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");

    private static final UUID WITHER_ESSENCE_ID =
            UUID.fromString("e0f3e929-869e-3dca-9504-54c666ee6f23");

    private static final UUID REDSTONE_KEY_ID =
            UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");

    private static final double ITEM_SECRET_DISTANCE_SQ = 36.0D;
    private static final double SKULL_SECRET_CLICK_DISTANCE_SQ = 20.25D;

    private static final List<String> DUNGEON_ITEM_DROPS = List.of(
            "Health Potion VIII Splash Potion",
            "Healing Potion 8 Splash Potion",
            "Healing Potion VIII Splash Potion",
            "Healing VIII Splash Potion",
            "Healing 8 Splash Potion",
            "Decoy",
            "Inflatable Jerry",
            "Spirit Leap",
            "Trap",
            "Training Weights",
            "Defuse Kit",
            "Dungeon Chest Key",
            "Treasure Talisman",
            "Revive Stone",
            "Architect's First Draft",
            "Secret Dye",
            "Candycomb"
    );

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;

        initialized = true;
        System.out.println("[NSM] Secret packet hooks initialized.");
    }

    public static void onTakeItemEntityPacket(ClientboundTakeItemEntityPacket packet) {
        runOnClientThread(() -> {
            try {
                handleTakeItemEntityPacket(packet);
            } catch (Throwable throwable) {
                System.out.println("[NSM] Failed while handling ClientboundTakeItemEntityPacket.");
                throwable.printStackTrace();
            }
        });
    }

    public static void onRemoveEntitiesPacket(ClientboundRemoveEntitiesPacket packet) {
        runOnClientThread(() -> {
            try {
                handleRemoveEntitiesPacket(packet);
            } catch (Throwable throwable) {
                System.out.println("[NSM] Failed while handling ClientboundRemoveEntitiesPacket.");
                throwable.printStackTrace();
            }
        });
    }

    public static void onSoundPacket(ClientboundSoundPacket packet) {
        runOnClientThread(() -> {
            try {
                handleSoundPacket(packet);
            } catch (Throwable throwable) {
                System.out.println("[NSM] Failed while handling ClientboundSoundPacket.");
                throwable.printStackTrace();
            }
        });
    }

    public static void onUseItemOnPacket(ServerboundUseItemOnPacket packet) {
        runOnClientThread(() -> {
            try {
                handleUseItemOnPacket(packet);
            } catch (Throwable throwable) {
                System.out.println("[NSM] Failed while handling ServerboundUseItemOnPacket.");
                throwable.printStackTrace();
            }
        });
    }

    public static void onSystemChatPacket(ClientboundSystemChatPacket packet) {
        runOnClientThread(() -> {
            try {
                handleSystemChatPacket(packet);
            } catch (Throwable throwable) {
                System.out.println("[NSM] Failed while handling ClientboundSystemChatPacket.");
                throwable.printStackTrace();
            }
        });
    }

    private static void handleTakeItemEntityPacket(ClientboundTakeItemEntityPacket packet) {
        Minecraft mc = Minecraft.getInstance();

        if (!hasWorldAndPlayer(mc)) return;

        System.out.print("[NSM] Sub-stage 1");

        if (packet.getPlayerId() != mc.player.getId()) {
            return;
        }

        Entity entity = mc.level.getEntity(packet.getItemId());

        System.out.print("[NSM] Sub-stage 2");

        if (!(entity instanceof ItemEntity itemEntity)) {
            return;
        }

        System.out.print("[NSM] Sub-stage 3");

        if (!isDungeonSecretDrop(itemEntity)) {
            return;
        }

        System.out.print("[NSM] Sub-stage 4");

        if (itemEntity.distanceToSqr(mc.player) > ITEM_SECRET_DISTANCE_SQ) {
            return;
        }

        System.out.print("[NSM] Sub-stage 5");

        BlockPos pos = itemEntity.blockPosition();

        System.out.println("[NSM] Own item secret packet at " + pos);
        SecretRoomTimerClient.onOdinItemSecretPickup(pos);
    }

    private static void handleRemoveEntitiesPacket(ClientboundRemoveEntitiesPacket packet) {
        Minecraft mc = Minecraft.getInstance();

        if (!hasWorldAndPlayer(mc)) return;

        for (int entityId : packet.getEntityIds()) {
            Entity entity = mc.level.getEntity(entityId);

            if (!(entity instanceof ItemEntity itemEntity)) {
                continue;
            }

            if (!isDungeonSecretDrop(itemEntity)) {
                continue;
            }

            if (itemEntity.distanceToSqr(mc.player) > ITEM_SECRET_DISTANCE_SQ) {
                continue;
            }

            BlockPos pos = itemEntity.blockPosition();

            System.out.println("[NSM] Own removed item secret packet at " + pos);
            SecretRoomTimerClient.onOdinItemSecretPickup(pos);
        }
    }

    private static void handleSoundPacket(ClientboundSoundPacket packet) {
        SoundEvent sound = packet.getSound().value();

        boolean isBatSound =
                sound == SoundEvents.BAT_HURT ||
                        sound == SoundEvents.BAT_DEATH;

        if (!isBatSound) {
            return;
        }

        if (Math.abs(packet.getVolume() - 0.1F) > 0.0001F) {
            return;
        }

        BlockPos pos = BlockPos.containing(
                packet.getX(),
                packet.getY(),
                packet.getZ()
        );

        System.out.println("[NSM] Bat secret packet at " + pos);
        SecretRoomTimerClient.onOdinSecretPickup(pos);
    }

    private static void handleUseItemOnPacket(ServerboundUseItemOnPacket packet) {
        Minecraft mc = Minecraft.getInstance();

        if (!hasWorldAndPlayer(mc)) return;

        if (packet.getHand() == InteractionHand.OFF_HAND) {
            return;
        }

        BlockHitResult hit = packet.getHitResult();
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);

        if (state.getBlock() instanceof SkullBlock) {
            double distanceSq = mc.player
                    .getEyePosition()
                    .distanceToSqr(Vec3.atLowerCornerOf(pos));

            if (distanceSq > SKULL_SECRET_CLICK_DISTANCE_SQ) {
                return;
            }
        }

        if (!isSecretBlock(mc, state, pos)) {
            return;
        }

        System.out.println("[NSM] Interact secret packet at " + pos);

        Block block = state.getBlock();

        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            SecretRoomTimerClient.onOdinChestSecretPickup(pos);
        } else {
            SecretRoomTimerClient.onOdinSecretPickup(pos);
        }
    }

    private static void handleSystemChatPacket(ClientboundSystemChatPacket packet) {
        if (packet.overlay()) {
            return;
        }

        String clean = stripControlCodes(packet.content().getString());

        if (clean.contains("That chest is locked!")) {
            SecretRoomTimerClient.onLockedChestMessage();
        }

        SecretRoomTimerClient.onChatMessage(clean);

        Matcher matcher = SECRET_COUNTER_REGEX.matcher(clean);

        if (!matcher.find()) {
            return;
        }

        int foundSecrets = Integer.parseInt(matcher.group(1));
        int totalSecrets = Integer.parseInt(matcher.group(2));

        System.out.println("[NSM] Secret counter packet: " + foundSecrets + "/" + totalSecrets);
        SecretRoomTimerClient.onRoomSecretsPacket(foundSecrets, totalSecrets);
    }

    private static boolean isSecretBlock(Minecraft mc, BlockState state, BlockPos pos) {
        Block block = state.getBlock();

        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.LEVER) {
            return true;
        }

        if (!(block instanceof SkullBlock)) {
            return false;
        }

        BlockEntity blockEntity = mc.level.getBlockEntity(pos);

        if (!(blockEntity instanceof SkullBlockEntity skullBlockEntity)) {
            return false;
        }

        UUID ownerId = getSkullOwnerId(skullBlockEntity.getOwnerProfile());

        return WITHER_ESSENCE_ID.equals(ownerId) || REDSTONE_KEY_ID.equals(ownerId);
    }

    private static UUID getSkullOwnerId(ResolvableProfile ownerProfile) {
        if (ownerProfile == null) {
            return null;
        }

        return ownerProfile.partialProfile().id();
    }

    private static UUID tryReadUuidFromIdMethod(Object ownerProfile) {
        try {
            Method method = ownerProfile.getClass().getMethod("id");
            Object result = method.invoke(ownerProfile);

            if (result instanceof Optional<?> optional) {
                Object value = optional.orElse(null);

                if (value instanceof UUID uuid) {
                    return uuid;
                }
            }

            if (result instanceof UUID uuid) {
                return uuid;
            }
        } catch (ReflectiveOperationException ignored) {

        }

        return null;
    }

    private static UUID tryReadUuidFromGameProfileMethod(Object ownerProfile) {
        try {
            Method method = ownerProfile.getClass().getMethod("gameProfile");
            Object result = method.invoke(ownerProfile);

            if (result instanceof GameProfile gameProfile) {
                return gameProfile.id();
            }
        } catch (ReflectiveOperationException ignored) {

        }

        return null;
    }

    private static boolean isDungeonSecretDrop(ItemEntity itemEntity) {
        String name = stripControlCodes(itemEntity.getItem().getHoverName().getString());
        String lowerName = name.toLowerCase(Locale.ROOT);

        for (String drop : DUNGEON_ITEM_DROPS) {
            if (lowerName.contains(drop.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static String stripControlCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return CONTROL_CODES.matcher(text).replaceAll("");
    }

    private static boolean hasWorldAndPlayer(Minecraft mc) {
        return mc.level != null && mc.player != null;
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