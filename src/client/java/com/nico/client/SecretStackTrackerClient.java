package com.nico.client;

import com.nico.OdinRoomBridge;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.goldor.GoldorTerminalHighlighter;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.hud.HudMoveCommand;
import com.nico.client.minions.MinionRoiClient;
import com.nico.client.minions.base.MinionDataRegistry;
import com.nico.client.secretTimer.SecretPacketHooks;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.nico.client.stacking.RoomStackingDetector;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
import com.nico.client.utils.SecretEventBridge;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.util.*;

public class SecretStackTrackerClient /*implements ClientModInitializer*/ {
	/**
	public static HudLayoutManager HUD_LAYOUT;

	private static final long SELF_SECRET_CONFIRM_WINDOW_MS = 2500;
	private static final long STACKING_WINDOW_MS = 15_000;
	private static final long ALERT_COOLDOWN_MS = 5000;

	private static String currentRoomName = null;
	private static int lastRoomSecretCount = -1;

	private static long lastSelfSecretAt = 0;
	private static String lastSelfSecretRoom = null;

	private static long pendingSelfSecretAt = 0;
	private static String pendingSelfSecretRoom = null;

	private static long lastAlertAt = 0;

	private static int tickCounter = 0;

	@Override
	public void onInitializeClient() {
		System.out.println("[NSM] Client initializer loaded");

		NsmConfigManager.init();

		SecretPacketHooks.init();
		SecretEventBridge.INSTANCE.init();

		SecretRoomTimerClient.init();

		try {
			HUD_LAYOUT = new HudLayoutManager();
			HudMoveCommand.register(HUD_LAYOUT);


		} catch (Exception e) {
			throw new RuntimeException(e);
		}

        try {
            MinionDataRegistry registry =
                    MinionDataRegistry.loadFromResources("/assets/nsm/minions.json");

			HypixelApiClient apiClient = new HypixelApiClient(null);
			BazaarService bazaarService = new BazaarService(apiClient);

			MinionRoiClient.init(registry, bazaarService, HUD_LAYOUT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        registerCommands();
		registerTickHandler();
	}

	private static void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(
					ClientCommands.literal("nsmrooms")
							.executes(context -> {
								try {
									printPlayerRooms();
									return 1;
								} catch (Throwable throwable) {
									throwable.printStackTrace();

									Minecraft mc = Minecraft.getInstance();
									if (mc.player != null) {
										mc.player.sendSystemMessage(
												Component.literal("§cNSM room command crashed. Check latest.log.")
										);
									}

									return 0;
								}
							})
			);

			dispatcher.register(
					ClientCommands.literal("nsmconfig")
							.executes(context -> {
								Minecraft mc = Minecraft.getInstance();
								mc.execute(() -> mc.setScreen(NsmConfigManager.createScreen(mc.screen)));
								return 1;
							})
			);

			dispatcher.register(
					ClientCommands.literal("nsm")
							.executes(context -> {
								Minecraft mc = Minecraft.getInstance();
								mc.execute(() -> mc.setScreen(NsmConfigManager.createScreen(mc.screen)));
								return 1;
							})
			);
		});
	}

	private static void registerTickHandler() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tickCounter++;

			if (NsmConfig.INSTANCE.dungeons.goldorTerminal.enabled) {
				GoldorTerminalHighlighter.tick();
			}

			// Once per second
			if (tickCounter % 20 != 0) return;

			if (NsmConfig.INSTANCE.dungeons.roomStacking.enabled) {
				RoomStackingDetector.tick();
			}
		});
	}

	public static void onRoomSecretsPacket(int foundSecrets, int totalSecrets) {
		SecretRoomTimerClient.onRoomSecretsPacket(foundSecrets, totalSecrets);

		if (!NsmConfig.INSTANCE.dungeons.secretStackingDetectorEnabled) return;

		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || mc.player == null) return;
		if (!DungeonUtils.INSTANCE.getInDungeons()) return;
		if (DungeonUtils.INSTANCE.getInBoss()) return;

		String roomName = OdinRoomBridge.getRoomNameForPlayer(mc.player);

		if (roomName == null || roomName.equals("Unknown")) return;

		onRoomSecretCounterUpdate(roomName, foundSecrets);
	}

	public static void onRoomChanged() {
		currentRoomName = null;
		lastRoomSecretCount = -1;

		pendingSelfSecretAt = 0;
		pendingSelfSecretRoom = null;
	}

/**
	private static void printPlayerRooms() {
		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || mc.player == null) return;

		if (!FabricLoader.getInstance().isModLoaded("odin")) {
			mc.player.sendSystemMessage(Component.literal("§cOdin is not loaded."));
			return;
		}

		Set<String> teammateNames = getOdinDungeonTeammateNames();

		mc.player.sendSystemMessage(Component.literal("§a--- Secret Stack Tracker Room Debug ---"));
		mc.player.sendSystemMessage(Component.literal("§7Odin teammates found: §e" + teammateNames.size()));

		for (Player player : mc.level.players()) {
			String playerName = player.getName().getString();

			if (!teammateNames.contains(playerName)) continue;

			String roomName = OdinRoomBridge.getRoomNameForPlayer(player);

			int x = player.blockPosition().getX();
			int y = player.blockPosition().getY();
			int z = player.blockPosition().getZ();

			mc.player.sendSystemMessage(Component.literal(
					"§e" + playerName +
							" §7-> §b" + roomName +
							" §8(" + x + ", " + y + ", " + z + ")"
			));
		}
	}

	public static Set<String> getOdinDungeonTeammateNames() {
		Set<String> names = new HashSet<>();

		try {
			for (DungeonPlayer teammate : DungeonUtils.INSTANCE.getDungeonTeammates()) {
				String name = teammate.getName();

				if (name != null && !name.isBlank()) {
					names.add(name);
				}
			}
		} catch (Throwable throwable) {
			throwable.printStackTrace();
		}

		return names;
	}

	public static void onOdinSecretPickup(BlockPos secretPos) {
		SecretRoomTimerClient.onOdinSecretPickup(secretPos);

		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || mc.player == null) return;
		if (!DungeonUtils.INSTANCE.getInDungeons()) return;
		if (DungeonUtils.INSTANCE.getInBoss()) return;

		String roomName = OdinRoomBridge.getRoomNameForPlayer(mc.player);

		if (roomName == null || roomName.equals("Unknown")) return;

		long now = System.currentTimeMillis();

		lastSelfSecretAt = now;
		lastSelfSecretRoom = roomName;

		pendingSelfSecretAt = now;
		pendingSelfSecretRoom = roomName;
	}

	public static void onRoomSecretCounterUpdate(String roomName, int newSecretCount) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.level == null || mc.player == null) return;
		if (!DungeonUtils.INSTANCE.getInDungeons()) return;
		if (DungeonUtils.INSTANCE.getInBoss()) return;
		if (roomName == null || roomName.equals("Unknown")) return;

		long now = System.currentTimeMillis();

		if (!roomName.equals(currentRoomName)) {
			currentRoomName = roomName;
			lastRoomSecretCount = newSecretCount;

			pendingSelfSecretAt = 0;
			pendingSelfSecretRoom = null;

			return;
		}

		if (lastRoomSecretCount < 0) {
			lastRoomSecretCount = newSecretCount;
			return;
		}

		int delta = newSecretCount - lastRoomSecretCount;
		lastRoomSecretCount = newSecretCount;

		if (delta <= 0) return;

		for (int i = 0; i < delta; i++) {
			handleRoomSecretIncrement(roomName, now);
		}
	}

	private static void handleRoomSecretIncrement(String roomName, long now) {
		if (isProbablyOurOwnSecret(roomName, now)) {
			pendingSelfSecretAt = 0;
			pendingSelfSecretRoom = null;

			debug("Room counter increment was probably ours.");
			return;
		}

		boolean weRecentlyDidSecretInThisRoom =
				lastSelfSecretRoom != null
						&& lastSelfSecretRoom.equals(roomName)
						&& now - lastSelfSecretAt <= STACKING_WINDOW_MS;

		if (weRecentlyDidSecretInThisRoom) {
			onStackingDetected(roomName);
		} else {
			debug("Someone else got a secret in this room, but not stacking.");
		}
	}

	private static boolean isProbablyOurOwnSecret(String roomName, long now) {
		return pendingSelfSecretRoom != null
				&& pendingSelfSecretRoom.equals(roomName)
				&& now - pendingSelfSecretAt <= SELF_SECRET_CONFIRM_WINDOW_MS;
	}

	private static void onStackingDetected(String roomName) {
		Minecraft mc = Minecraft.getInstance();

		if (mc.player == null) return;

		long now = System.currentTimeMillis();

		if (now - lastAlertAt < ALERT_COOLDOWN_MS) return;
		lastAlertAt = now;

		mc.player.sendSystemMessage(Component.literal(
				"§c§l[NSM] Stacking detected in §b" + roomName + "§c!"
		));

		System.out.println("[NSM] Stacking detected in " + roomName);
	}

	private static void debug(String message) {
		System.out.println("[NSM] " + message);
	}
 **/
}
