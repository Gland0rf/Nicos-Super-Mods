package com.nico.client.history;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Lists completed months Wrapped reports. The current month is never shown here. */
public final class WrappedArchiveScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private static final int MAX_ROWS_PER_PAGE = 7;
    private static final DateTimeFormatter MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final Screen parent;
    private final List<YearMonth> months;
    private int page;

    public WrappedArchiveScreen(Screen parent) {
        super(Component.literal("SkyBlock Wrapped"));
        this.parent = parent;
        this.months = SkyblockHistoryFeature.completedWrappedMonths();
    }

    @Override
    protected void init() {
        int rowsPerPage = rowsPerPage();
        int pageCount = pageCount(rowsPerPage);
        page = Math.max(0, Math.min(page, pageCount - 1));

        int panelWidth = Math.clamp(width - 40, 280, 420);
        int left = (width - panelWidth) / 2;
        int top = 62;
        int first = page * rowsPerPage;
        int last = Math.min(months.size(), first + rowsPerPage);

        for (int index = first; index < last; index++) {
            YearMonth month = months.get(index);
            int y = top + (index - first) * ROW_HEIGHT;
            addRenderableWidget(
                    Button.builder(Component.literal("View"), button ->
                                    Minecraft.getInstance().setScreen(new WrappedScreen(this, month)))
                            .bounds(left + panelWidth - 62, y, 54, 20)
                            .build()
            );
        }

        addRenderableWidget(
                Button.builder(Component.literal("<"), button -> {
                            page = Math.max(0, page - 1);
                            rebuildWidgets();
                        })
                        .bounds(width / 2 - 84, height - 30, 28, 20)
                        .build()
        );
        addRenderableWidget(
                Button.builder(Component.literal("Close"), button -> onClose())
                        .bounds(width / 2 - 44, height - 30, 88, 20)
                        .build()
        );
        addRenderableWidget(
                Button.builder(Component.literal(">"), button -> {
                            page = Math.min(pageCount - 1, page + 1);
                            rebuildWidgets();
                        })
                        .bounds(width / 2 + 56, height - 30, 28, 20)
                        .build()
        );

    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xF0101218);

        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);

        YearMonth current = YearMonth.now();
        String availability = current.format(MONTH_TITLE) + " is still in progress - available "
                + current.plusMonths(1).atDay(1).format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
        graphics.centeredText(font, availability, width / 2, 34, 0xFFAAAAAA);

        if (months.isEmpty()) {
            graphics.centeredText(font, "No completed monthly Wrapped reports yet.", width / 2, height / 2 - 6, 0xFFE0E0E0);
            graphics.centeredText(font, "Your first one appears after a tracked month has ended.", width / 2, height / 2 + 10, 0xFF999999);
        } else {
            int rowsPerPage = rowsPerPage();
            int panelWidth = Math.clamp(width - 40, 280, 420);
            int left = (width - panelWidth) / 2;
            int top = 62;
            int first = page * rowsPerPage;
            int last = Math.min(months.size(), first + rowsPerPage);

            graphics.fill(left, top - 8, left + panelWidth, top + rowsPerPage * ROW_HEIGHT + 2, 0xB91A1D28);
            graphics.fill(left, top - 8, left + 3, top + rowsPerPage * ROW_HEIGHT + 2, 0xFF9A6CFF);

            for (int index = first; index < last; index++) {
                YearMonth month = months.get(index);
                int y = top + (index - first) * ROW_HEIGHT;
                graphics.text(font, month.format(MONTH_TITLE), left + 14, y + 6, 0xFFF2F2F2, true);
            }

            int pages = pageCount(rowsPerPage);
            graphics.centeredText(font, (page + 1) + " / " + pages, width / 2, height - 44, 0xFF888888);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private int rowsPerPage() {
        return Math.max(1, Math.min(MAX_ROWS_PER_PAGE, (height - 116) / ROW_HEIGHT));
    }

    private int pageCount(int rowsPerPage) {
        return Math.max(1, (months.size() + rowsPerPage - 1) / rowsPerPage);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
