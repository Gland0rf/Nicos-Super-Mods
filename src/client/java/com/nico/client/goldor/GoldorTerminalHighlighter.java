package com.nico.client.goldor;

import com.nico.client.configuration.NsmConfig;
import com.odtheking.odin.events.RenderEvent;
import com.odtheking.odin.utils.Color;
import com.odtheking.odin.utils.render.RenderUtilsKt;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoldorTerminalHighlighter {

    public static double passedThresholdBlocks = 3.0D;

    private static final long ALERT_COOLDOWN_MS = 5500L;
    private static final int SOUND_REPEAT_COUNT = 5;
    private static final long SOUND_REPEAT_DELAY_MS = 170L;

    private static final boolean DEPTH_CHECKED_RENDERING = true;

    private static final Color ASSIGNED_COLOR = new Color(85, 255, 85, 0.85F);
    private static final Color ALERT_COLOR = new Color(255, 85, 85, 0.95F);

    private static final String TERMINALS_START =
            "[BOSS] Storm: I should have known that I stood no chance.";

    private static final String CORE_ENTRANCE =
            "The Core entrance is opening!";

    private static final Pattern TERMINAL_ACTIVATED =
            Pattern.compile("^(\\w+) (?:activated|completed) a terminal! \\(\\d+/\\d+\\)$");

    private static final Pattern DEVICE_COMPLETED =
            Pattern.compile("^(\\w+) completed a device! \\(\\d+/\\d+\\)$");

    private static final Pattern PHASE_COMPLETE =
            Pattern.compile("^\\w+ (?:activated a (?:terminal|lever)|completed a device)! \\((?:7/7|8/8)\\)$");

    private static final Map<Integer, SectionDefinition> SECTIONS = new HashMap<>();
    private static final Map<Integer, Map<DungeonClass, List<TargetSelector>>> CLASS_ASSIGNMENTS_BY_SECTION = new HashMap<>();

    private static boolean wasInGoldor = false;
    private static boolean active = false;

    private static int goldorSection = 0;

    private static TargetPoint currentLineTarget = null;

    private static long lastAlertAt = 0L;
    private static String lastAlertKey = "";

    private static int queuedSounds = 0;
    private static long nextSoundAt = 0L;

    static {
        SECTIONS.put(0, new SectionDefinition(
           0,
           Axis.Z,
           1,
           Arrays.asList(
                   TargetPoint.device(new BlockPos(110, 121, 91)),

                   TargetPoint.terminal(1, new BlockPos(111, 113, 73)),
                   TargetPoint.terminal(2, new BlockPos(111, 119, 79)),
                   TargetPoint.terminal(3, new BlockPos(89, 112, 92)),
                   TargetPoint.terminal(4, new BlockPos(89, 122, 101))
           )
        ));

        Map<DungeonClass, List<TargetSelector>> sectionOne = new EnumMap<>(DungeonClass.class);

        sectionOne.put(DungeonClass.Tank, Arrays.asList(
                TargetSelector.terminal(1),
                TargetSelector.terminal(2)
        ));

        sectionOne.put(DungeonClass.Mage, Arrays.asList(
                TargetSelector.terminal(3),
                TargetSelector.terminal(4)
        ));

        sectionOne.put(DungeonClass.Healer, Arrays.asList(
                TargetSelector.device()
        ));

        CLASS_ASSIGNMENTS_BY_SECTION.put(0, sectionOne);
    }

    private GoldorTerminalHighlighter() { }

    /*public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (!NsmConfig.INSTANCE.dungeons.goldorTerminal.enabled || mc.level == null || mc.player == null) {
            clearVolatileAlertState();
            return;
        }

        boolean isInGoldor = isInGoldorPhase();

        if (!isInGoldor) {
            if (wasInGoldor) {
                resetRunState();
            }

            wasInGoldor = false;
            return;
        }

        if (!wasInGoldor) {
            resetRunState();
            active = true;
            wasInGoldor = true;
        }

        if (!active) {
            clearVolatileAlertState();
            return;
        }

        SectionDefinition section = SECTIONS.get(goldorSection);

        if (section == null) {
            clearVolatileAlertState();
            return;
        }

        DungeonClass selfClass = getSelfDungeonClass(mc.player);
        List<TargetPoint> assignedTargets = getAssignedTargets(selfClass, section);

        Optional<TargetPoint> missedTarget = getMissedTarget(mc.player, section, assignedTargets);

        if (missedTarget.isPresent()) {
            TargetPoint target = missedTarget.get();
            currentLineTarget = target;
            alertIfNeeded(mc, section, target, assignedTargets);
        } else {
            currentLineTarget = null;
            queuedSounds = 0;
        }

        serviceQueuedSounds(mc);
    }*/

    public static void render(RenderEvent.Extract event) {
        Minecraft mc = Minecraft.getInstance();

        /*if (!NsmConfig.INSTANCE.dungeons.goldorTerminal.enabled || mc.level == null || mc.player == null) {
            return;
        }*/
        return; // temporary

        /*if (!active || !isInGoldorPhase()) return;

        SectionDefinition section = SECTIONS.get(goldorSection);
        if (section == null) return;

        DungeonClass selfClass = getSelfDungeonClass(mc.player);
        List<TargetPoint> assignedTargets = getAssignedTargets(selfClass, section);

        for (TargetPoint target : assignedTargets) {
            if (target.done) continue;

            boolean alerting = target == currentLineTarget;
            Color color = alerting ? ALERT_COLOR : ASSIGNED_COLOR;

            AABB box = new AABB(target.pos).inflate(0.08D);

            RenderUtilsKt.drawWireFrameBox(
                    event,
                    box,
                    color,
                    alerting ? 5.0F : 3.0F,
                    DEPTH_CHECKED_RENDERING
            );

            RenderUtilsKt.drawText(
                    event,
                    target.displayName(),
                    target.center().add(0.0D, 1.15D, 0.0D),
                    1.8F,
                    DEPTH_CHECKED_RENDERING
            );
        }

        TargetPoint lineTarget = currentLineTarget;

        if (lineTarget != null && !lineTarget.done) {
            RenderUtilsKt.drawTracer(
                    event,
                    lineTarget.center(),
                    ALERT_COLOR,
                    DEPTH_CHECKED_RENDERING,
                    5.0F
            );
        }*/
    }

    public static void onChatMessage(String message) {
        if (message == null || message.isEmpty()) return;

        if (TERMINALS_START.equals(message)) {
            resetRunState();
            active = true;
            wasInGoldor = true;
            return;
        }

        if (!active) return;

        if (CORE_ENTRANCE.equals(message)) {
            active = false;
            clearVolatileAlertState();
            return;
        }

        if (PHASE_COMPLETE.matcher(message).matches()) {
            goldorSection++;
            clearVolatileAlertState();
            return;
        }

        Matcher terminalMatcher = TERMINAL_ACTIVATED.matcher(message);

        if (terminalMatcher.matches()) {
            markNearestCompleted(TargetKind.TERMINAL, terminalMatcher.group(1));
            return;
        }

        Matcher deviceMatcher = DEVICE_COMPLETED.matcher(message);

        if (deviceMatcher.matches()) {
            markNearestCompleted(TargetKind.DEVICE, deviceMatcher.group(1));
        }
    }

    private static boolean isInGoldorPhase() {
        try {
            return DungeonUtils.INSTANCE.getInDungeons()
                    && DungeonUtils.INSTANCE.getInBoss()
                    && DungeonUtils.INSTANCE.isFloor(7)
                    && DungeonUtils.INSTANCE.getF7Phase() == M7Phases.P3;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        }
    }

    public static void onRoomChanged() {
        clearVolatileAlertState();
    }

    private static Optional<TargetPoint> getMissedTarget(
            Player player,
            SectionDefinition section,
            List<TargetPoint> assignedTargets
    ) {
        if (assignedTargets.isEmpty()) {
            return Optional.empty();
        }

        List<TargetPoint> pending = new ArrayList<>();

        for (TargetPoint target : assignedTargets) {
            if (!target.done) {
                pending.add(target);
            }
        }

        if (pending.isEmpty()) {
            return Optional.empty();
        }

        double playerProgress = section.progress(player.position());

        double furthestPendingProgress = Double.NEGATIVE_INFINITY;

        for (TargetPoint target : pending) {
            furthestPendingProgress = Math.max(
                    furthestPendingProgress,
                    section.progress(target)
            );
        }

        if (playerProgress <= furthestPendingProgress + passedThresholdBlocks) {
            return Optional.empty();
        }

        Vec3 playerPos = player.position();

        pending.sort(Comparator.comparingDouble(target ->
                target.center().distanceToSqr(playerPos)
        ));

        return Optional.of(pending.get(0));
    }

    private static List<TargetPoint> getAssignedTargets(DungeonClass clazz, SectionDefinition section) {
        Map<DungeonClass, List<TargetSelector>> sectionAssignments =
                CLASS_ASSIGNMENTS_BY_SECTION.get(section.index);

        if (sectionAssignments == null) {
            return Collections.emptyList();
        }

        List<TargetSelector> selectors = sectionAssignments.get(clazz);

        if (selectors == null || selectors.isEmpty()) {
            return Collections.emptyList();
        }

        List<TargetPoint> targets = new ArrayList<>();

        for (TargetSelector selector : selectors) {
            for (TargetPoint target : section.targets) {
                if (selector.matches(target)) {
                    targets.add(target);
                    break;
                }
            }
        }

        return targets;
    }

    private static void alertIfNeeded(
            Minecraft mc,
            SectionDefinition section,
            TargetPoint target,
            List<TargetPoint> assignedTargets
    ) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        String key = target.key(section.index);

        if (key.equals(lastAlertKey) && now - lastAlertAt < ALERT_COOLDOWN_MS) {
            return;
        }

        lastAlertKey = key;
        lastAlertAt = now;

        mc.gui.setTimes(5, 30, 10);

        mc.gui.setTitle(
                Component.literal("Missed " + target.displayName())
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
        );

        mc.gui.setSubtitle(
                Component.literal("Your assignment " + describeTargets(assignedTargets))
                        .withStyle(ChatFormatting.YELLOW)
        );

        queuedSounds = SOUND_REPEAT_COUNT;
        nextSoundAt = 0L;
    }

    private static void serviceQueuedSounds(Minecraft mc) {
        if (queuedSounds <= 0 || mc.player == null) return;

        long now = System.currentTimeMillis();

        if (now < nextSoundAt) return;

        mc.player.playSound(
                SoundEvents.BELL_BLOCK,
                1.0F,
                1.0F
        );

        queuedSounds--;
        nextSoundAt = now + SOUND_REPEAT_DELAY_MS;
    }

    private static void markNearestCompleted(TargetKind kind, String playerName) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || playerName == null) return;

        SectionDefinition section = SECTIONS.get(goldorSection);
        if (section == null) return;

        Player completingPlayer = null;

        for (Player player : mc.level.players()) {
            if (player.getName().getString().equals(playerName)) {
                completingPlayer = player;
                break;
            }
        }

        if (completingPlayer == null) return;

        Vec3 playerPos = completingPlayer.position();

        TargetPoint nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (TargetPoint target : section.targets) {
            if (target.done || target.kind != kind) continue;

            double dist = target.center().distanceToSqr(playerPos);

            if (dist < nearestDist) {
                nearest = target;
                nearestDist = dist;
            }
        }

        if (nearest != null) {
            nearest.done = true;
        }
    }

    private static DungeonClass getSelfDungeonClass(Player self) {
        String selfName = self.getName().getString();

        try {
            for (DungeonPlayer dungeonPlayer : DungeonUtils.INSTANCE.getDungeonTeammates()) {
                if (!dungeonPlayer.getName().equals(selfName)) continue;

                DungeonClass clazz = dungeonPlayer.getClazz();

                if (clazz == null) {
                    return DungeonClass.Unknown;
                }

                return clazz;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return DungeonClass.Unknown;
    }

    private static String describeTargets(List<TargetPoint> targets) {
        if (targets == null || targets.isEmpty()) return "none";

        List<String> names = new ArrayList<>();

        for (TargetPoint target : targets) {
            names.add(target.displayName());
        }

        return String.join(" + ", names);
    }

    private static void resetRunState() {
        active = false;
        goldorSection = 0;
        currentLineTarget = null;

        lastAlertAt = 0L;
        lastAlertKey = "";

        queuedSounds = 0;
        nextSoundAt = 0L;

        for (SectionDefinition section : SECTIONS.values()) {
            for (TargetPoint target : section.targets) {
                target.done = false;
            }
        }
    }

    private static void clearVolatileAlertState() {
        currentLineTarget = null;
        queuedSounds = 0;
        nextSoundAt = 0L;
    }


    private enum Axis {
        X,
        Z
    }

    private enum TargetKind {
        TERMINAL,
        DEVICE
    }

    private static final class SectionDefinition {
        private final int index;
        private final Axis axis;
        private final int direction;
        private final List<TargetPoint> targets;

        private SectionDefinition(int index, Axis axis, int direction, List<TargetPoint> targets) {
            this.index = index;
            this.axis = axis;
            this.direction = direction;
            this.targets = targets;
        }

        private double progress(Vec3 pos) {
            return direction * (axis == Axis.X ? pos.x : pos.z);
        }

        private double progress(TargetPoint target) {
            Vec3 center = target.center();
            return progress(center);
        }
    }

    private static final class TargetPoint {
        private final TargetKind kind;
        private final int terminalNumber;
        private final BlockPos pos;
        private boolean done = false;

        private static TargetPoint terminal(int number, BlockPos pos) {
            return new TargetPoint(TargetKind.TERMINAL, number, pos);
        }

        private static TargetPoint device(BlockPos pos) {
            return new TargetPoint(TargetKind.DEVICE, -1, pos);
        }

        private TargetPoint(TargetKind kind, int terminalNumber, BlockPos pos) {
            this.kind = kind;
            this.terminalNumber = terminalNumber;
            this.pos = pos;
        }

        private Vec3 center() {
            return Vec3.atCenterOf(pos);
        }

        private String displayName() {
            if (kind == TargetKind.DEVICE) {
                return "Device";
            }

            return "Terminal " + terminalNumber;
        }

        private String key(int sectionIndex) {
            return sectionIndex + ":" + kind + ":" + terminalNumber;
        }
    }

    private static final class TargetSelector {
        private final TargetKind kind;
        private final int terminalNumber;

        private static TargetSelector terminal(int number) {
            return new TargetSelector(TargetKind.TERMINAL, number);
        }

        private static TargetSelector device() {
            return new TargetSelector(TargetKind.DEVICE, -1);
        }

        private TargetSelector(TargetKind kind, int terminalNumber) {
            this.kind = kind;
            this.terminalNumber = terminalNumber;
        }

        private boolean matches(TargetPoint target) {
            if (target.kind != kind) return false;

            if (kind == TargetKind.DEVICE) {
                return true;
            }

            return terminalNumber == target.terminalNumber;
        }
    }
}
