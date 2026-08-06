package com.nico.client.modcheck.client;

import com.nico.client.modcheck.ModCheckRuntime;
import com.nico.client.modcheck.config.IgnoredUnknownMods;
import com.nico.client.modcheck.scan.FindingSeverity;
import com.nico.client.modcheck.scan.FindingStatus;
import com.nico.client.modcheck.scan.ScanFinding;
import com.nico.client.modcheck.scan.ScanReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

public final class ModCheckWarningScreen extends Screen {
    private static final int COLOR_CRITICAL = 0xFFFF5555;
    private static final int COLOR_WARNING = 0xFFFFAA00;
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFFAAAAAA;

    private final Screen parent;
    private final ScanReport report;
    private boolean copied;

    private static final int FINDING_ROW_HEIGHT = 29;
    private int scrollOffset;
    private int maximumScrollOffset;
    private int findingsTop;
    private int findingsBottom;
    private boolean hasIgnorableUnknowns;

    public ModCheckWarningScreen(Screen parent, ScanReport report) {
        super(Component.literal("NSM ModCheck security warning"));
        this.parent = parent;
        this.report = report;
    }

    @Override
    protected void init() {
        int buttonY = this.height - 36;
        int center = this.width / 2;

        this.addRenderableWidget(
                Button.builder(
                        Component.literal("Copy report"),
                        button -> copyReport()
                ).bounds(center - 156, buttonY, 100, 20).build()
        );

        this.addRenderableWidget(
                Button.builder(
                        Component.literal("Close Minecraft"),
                        button -> {
                            if (this.minecraft != null) {
                                this.minecraft.stop();
                            }
                        }
                ).bounds(center - 50, buttonY, 100, 20).build()
        );

        String continueLabel = report.criticalCount() > 0 ? "Continue anyway" : "Acknowledge";
        this.addRenderableWidget(
                Button.builder(
                        Component.literal(continueLabel),
                        button -> {
                            ModCheckRuntime.acknowledge();

                            if (this.minecraft != null) {
                                this.minecraft.setScreen(parent);
                            }
                        }
                ).bounds(center + 56, buttonY, 100, 20).build()
        );

        List<ScanFinding> unknownFindings = report.findings()
                .stream()
                .filter(ScanFinding::isIgnorableUnknown)
                .toList();

        this.hasIgnorableUnknowns = !unknownFindings.isEmpty();

        if (!unknownFindings.isEmpty()) {
            System.out.println("[NSM ModCheck] BRRRR");
            int unknownCount = unknownFindings.size();

            this.addRenderableWidget(
                    Button.builder(
                            Component.literal("Don't warn again for these " + unknownCount + " mods"),
                            button -> {
                                List<String> hashes = unknownFindings.stream()
                                        .map(ScanFinding::sha512)
                                        .toList();

                                try {
                                    int added = IgnoredUnknownMods.addAll(hashes);

                                    button.setMessage(Component.literal("Ignored " + added + " mods."));

                                    button.active = false;

                                    ModCheckRuntime.acknowledge();
                                } catch (IOException e) {
                                    button.setMessage(Component.literal("Could not save ignore list"));
                                }
                            }
                    ).bounds(center - 150, this.height - 60, 300, 20).build()
            );
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // Deliberately do nothing. The user must choose an explicit action.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float deltaTicks) {
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        int titleColor = report.criticalCount() > 0 ? COLOR_CRITICAL : COLOR_WARNING;
        graphics.drawCenteredString(
                this.font,
                Component.literal(report.headline()),
                this.width / 2,
                18,
                titleColor
        );

        boolean hasUnknownMods = report.findings().stream()
                .anyMatch(finding ->
                        finding.severity() == FindingSeverity.WARNING
                                && (
                                finding.status() == FindingStatus.UNKNOWN_MOD
                                        || finding.status() == FindingStatus.UNKNOWN_JAR
                        )
                );

        int contentY = 35;

        if (report.criticalCount() > 0) {
            contentY = drawCenteredWrapped(
                    graphics,
                    """
                    Another mod may already have executed.
                    If this was not intentional, re-log into your minecraft account, change your password, activate 2FA
                    and run a virus-scan on your computer.
                    """.strip(),
                    35,
                    Math.min(this.width - 48, 620),
                    COLOR_NORMAL
            );

            contentY += 6;
        }

        if (hasUnknownMods) {
            contentY = drawCenteredWrapped(
                    graphics,
                    """
                    Some installed mods are not currently listed in the trust registry.
                    This does not mean they are malicious; their exact files simpy could not be verified.
                    This means it's probably not a frequently-used mod. If you know this mod is secure, then
                    there's no action required. Otherwise, consider removing this mod.
                    """.strip(),
                    35,
                    Math.min(this.width - 48, 620),
                    COLOR_NORMAL
            );

            contentY += 6;
        }

        graphics.drawCenteredString(
                this.font,
                Component.literal(
                        "Critical: " + report.criticalCount()
                                + "   Warnings: " + report.warningCount()
                                + "   Verified: " + report.verifiedCount()
                ),
                this.width / 2,
                contentY,
                COLOR_MUTED
        );

        List<ScanFinding> actionableFindings = report.findings()
                .stream()
                .filter(finding -> finding.severity() != FindingSeverity.INFO)
                .sorted(
                        Comparator.comparingInt((ScanFinding finding) ->
                            finding.severity() == FindingSeverity.CRITICAL ? 0 : 1
                        ).thenComparing(ScanFinding::fileName, String.CASE_INSENSITIVE_ORDER)
                )
                .toList();

        int footerY = hasIgnorableUnknowns ? this.height - 86 : this.height - 55;
        this.findingsTop = contentY + 24;
        this.findingsBottom = footerY - 20;

        int availableHeight = Math.max(
                0,
                findingsBottom - findingsTop
        );

        int visibleCount = Math.max(
                1,
                availableHeight / FINDING_ROW_HEIGHT
        );

        this.maximumScrollOffset = Math.max(
                0,
                actionableFindings.size() - visibleCount
        );

        this.scrollOffset = Math.max(
                0,
                Math.min(scrollOffset, maximumScrollOffset)
        );

        int endIndex = Math.min(
                actionableFindings.size(),
                scrollOffset + visibleCount
        );

        List<ScanFinding> visible = actionableFindings.subList(scrollOffset, endIndex);

        int y = findingsTop;

        for (ScanFinding finding : visible) {
            int color = finding.severity() == FindingSeverity.CRITICAL ? COLOR_CRITICAL : COLOR_WARNING;

            graphics.drawString(
                    this.font,
                    Component.literal(shorten(finding.fileName(), 46) + " - " + finding.status()),
                    24,
                    y,
                    color
            );
            y += 12;

            graphics.drawString(
                    this.font,
                    Component.literal(shorten(finding.detail(), 105)),
                    34,
                    y,
                    COLOR_NORMAL
            );
            y += 17;
        }

        if (maximumScrollOffset > 0) {
            String position = "Showing "
                    + (scrollOffset + 1)
                    + "-"
                    + endIndex
                    + " of "
                    + actionableFindings.size()
                    + " - scroll for more";

            graphics.drawString(
                    this.font,
                    Component.literal(position),
                    this.width - this.font.width(position) - 16,
                    findingsBottom,
                    COLOR_MUTED
            );
        }

        long hidden = report.findings().stream()
                .filter(finding -> finding.severity() != FindingSeverity.INFO)
                .count() - visible.size();
        if (hidden > 0) {
            graphics.drawString(
                    this.font,
                    Component.literal("...and " + hidden + " more issue(s). Copy the report for full details."),
                    24,
                    y,
                    COLOR_MUTED
            );
        }

        String reportLocation = report.reportPath() == null
                ? "Report file could not be written."
                : "Report: " + report.reportPath();
        graphics.drawCenteredString(
                this.font,
                Component.literal(shorten(reportLocation, Math.max(40, this.width / 6))),
                this.width / 2,
                footerY,
                copied ? 0xFF55FF55 : COLOR_MUTED
        );

        super.render(graphics, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maximumScrollOffset <= 0 || verticalAmount == 0) {
            return super.mouseScrolled(
                    mouseX,
                    mouseY,
                    horizontalAmount,
                    verticalAmount
            );
        }

        int scrollAmount = Math.max(1, (int) Math.ceil(Math.abs(verticalAmount)));

        if (verticalAmount > 0) scrollOffset -= scrollAmount;
        else scrollOffset += scrollAmount;

        scrollOffset = Math.max(
                0,
                Math.min(scrollOffset, maximumScrollOffset)
        );

        return true;
    }

    private int drawCenteredWrapped(
            GuiGraphics graphics,
            String text,
            int startY,
            int maximumWidth,
            int color
    ) {
        int y = startY;

        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                y += this.font.lineHeight + 2;
                continue;
            }

            List<FormattedCharSequence> lines = this.font.split(
                    Component.literal(paragraph),
                    maximumWidth
            );

            for (FormattedCharSequence line : lines) {
                graphics.drawCenteredString(
                        this.font,
                        line,
                        this.width / 2,
                        y,
                        color
                );

                y += this.font.lineHeight + 2;
            }
        }

        return y;
    }

    private void copyReport() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(report.toHumanReadable());
            copied = true;
        }
    }

    private static String shorten(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maximumLength - 3)) + "...";
    }
}
