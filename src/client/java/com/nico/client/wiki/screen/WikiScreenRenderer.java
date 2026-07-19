package com.nico.client.wiki.screen;

import static com.nico.client.wiki.screen.WikiScreenMetrics.*;

import com.nico.client.wiki.WikiBrowserStore;
import com.nico.client.wiki.WikiTitleResolver;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

abstract class WikiScreenRenderer extends WikiScreenLayout {
    protected WikiScreenRenderer(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderMouseX = mouseX;
        renderMouseY = mouseY;

        long animationStep = animationStep();
        if (activeBrowserTab().kind == PageKind.ARTICLE
                && state == LoadState.LOADED && page != null && animationStep != lastAnimationStep) {
            int previousScroll = scrollPixels;
            rebuildLayout();
            scrollPixels = Math.max(0, Math.min(previousScroll, maxScrollPixels));
            lastAnimationStep = animationStep;
            saveScreenToActiveTab();
            updateFindMatches(false);
        }

        clearRenderHitboxes();
        renderHeader(graphics);
        renderBody(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);

        renderSearchSuggestions(graphics);
        if (contextMenu == null) {
            renderHoveredSlotTooltip(graphics, mouseX, mouseY);
        }
        renderContextMenu(graphics);
        renderFindStatus(graphics);

        boolean pointing = containsToc(mouseX, mouseY)
                || containsTab(mouseX, mouseY)
                || containsLinkedSlot(mouseX, mouseY)
                || containsLink(mouseX, mouseY)
                || containsPageTab(mouseX, mouseY)
                || containsContextMenu(mouseX, mouseY)
                || containsSearchSuggestion(mouseX, mouseY)
                || containsSpecialPageAction(mouseX, mouseY)
                || (newTabHitbox != null && newTabHitbox.contains(mouseX, mouseY));
        if (draggingScrollbar || isScrollbarHovered(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.RESIZE_NS);
        } else if (pointing) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    protected void renderHeader(GuiGraphics graphics) {
        int tabsY = HEADER_HEIGHT;
        int toolbarY = HEADER_HEIGHT + BROWSER_TAB_HEIGHT;
        int totalHeaderHeight = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + TOOLBAR_HEIGHT;
        graphics.fill(0, 0, width, height, backgroundColor());
        graphics.fill(0, 0, width, totalHeaderHeight, headerColor());
        graphics.renderItem(itemStack, OUTER_MARGIN, 18);
        graphics.drawString(font, "Hypixel SkyBlock Wiki", OUTER_MARGIN + 27, 12, 0xFFFFFFFF, true);
        graphics.drawString(font, font.plainSubstrByWidth(visibleTitle, Math.max(80, width - 90)),
                OUTER_MARGIN + 27, 29, MUTED, true);

        renderBrowserTabs(graphics, tabsY);
        graphics.fill(0, toolbarY, width, toolbarY + TOOLBAR_HEIGHT, toolbarColor());
        graphics.fill(0, totalHeaderHeight - 2, width, totalHeaderHeight, LINK);
    }

    protected void renderBrowserTabs(GuiGraphics graphics, int y) {
        if (browserTabs.isEmpty()) {
            return;
        }

        int available = Math.max(100, width - OUTER_MARGIN * 2 - 26);
        int tabWidth = Math.max(82, Math.min(180, available / Math.max(1, browserTabs.size())));
        int x = OUTER_MARGIN;

        for (int index = 0; index < browserTabs.size(); index++) {
            if (x >= width - OUTER_MARGIN - 26) {
                break;
            }
            int right = Math.min(width - OUTER_MARGIN - 26, x + tabWidth);
            int actualWidth = right - x;
            PageTab tab = browserTabs.get(index);
            boolean active = index == activeBrowserTabIndex;
            boolean hovered = contains(renderMouseX, renderMouseY, x, y, actualWidth, BROWSER_TAB_HEIGHT - 2);
            int background = active ? TAB_ACTIVE : hovered ? tocHoverColor() : TAB;

            graphics.fill(x, y, right, y + BROWSER_TAB_HEIGHT - 2, background);
            graphics.fill(x, y, right, y + 1, borderColor());
            graphics.fill(x, y, x + 1, y + BROWSER_TAB_HEIGHT - 2, borderColor());
            graphics.fill(right - 1, y, right, y + BROWSER_TAB_HEIGHT - 2, borderColor());

            String label = tab.visibleTitle == null || tab.visibleTitle.isBlank() ? "Wiki" : tab.visibleTitle;
            int closeWidth = tab.closable ? 14 : 0;
            graphics.drawString(font, font.plainSubstrByWidth(label, Math.max(18, actualWidth - 12 - closeWidth)),
                    x + 6, y + 6, TEXT, true);
            if (closeWidth > 0) {
                graphics.drawCenteredString(font, "×", right - 7, y + 6, MUTED);
            }

            pageTabHitboxes.add(new PageTabHitbox(
                    x,
                    y,
                    actualWidth,
                    BROWSER_TAB_HEIGHT - 2,
                    index,
                    closeWidth > 0 ? right - closeWidth : right
            ));
            x = right + 2;
        }

        int plusSize = BROWSER_TAB_HEIGHT - 4;
        int plusX = Math.min(width - OUTER_MARGIN - plusSize, x + 1);
        int plusY = y + 1;
        boolean hovered = contains(renderMouseX, renderMouseY, plusX, plusY, plusSize, plusSize);
        graphics.fill(plusX, plusY, plusX + plusSize, plusY + plusSize, hovered ? tocHoverColor() : TAB);
        graphics.drawCenteredString(font, "+", plusX + plusSize / 2, plusY + 5, TEXT);
        newTabHitbox = new NewTabHitbox(plusX, plusY, plusSize, plusSize);
    }

    protected void renderBody(GuiGraphics graphics) {
        int top = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + TOOLBAR_HEIGHT + 7;
        int bottom = height - FOOTER_HEIGHT - 4;
        PageTab tab = activeBrowserTab();

        if (tab.kind == PageKind.HELP) {
            renderSpecialPageFrame(graphics, top, bottom);
            renderHelpPage(graphics, top, bottom);
            return;
        }
        if (tab.kind == PageKind.NEW_TAB) {
            renderSpecialPageFrame(graphics, top, bottom);
            renderNewTabPage(graphics, top, bottom);
            return;
        }

        int left = state == LoadState.LOADED ? pageLeft : OUTER_MARGIN;
        int boxWidth = state == LoadState.LOADED ? pageWidth : width - OUTER_MARGIN * 2;
        graphics.fill(left - 1, top - 1, left + boxWidth + 1, bottom + 1, borderColor());
        graphics.fill(left, top, left + boxWidth, bottom, pageColor());
        graphics.enableScissor(left, top, left + boxWidth, bottom);
        if (state == LoadState.LOADING) {
            graphics.drawCenteredString(font, Component.literal("Loading Wiki page...").withStyle(ChatFormatting.YELLOW),
                    width / 2, top + 20, 0xFFFFFFFF);
        } else if (state == LoadState.ERROR) {
            renderError(graphics, top);
        } else {
            renderDocument(graphics, top, bottom);
        }
        graphics.disableScissor();
        if (state == LoadState.LOADED) {
            renderScrollbar(graphics, top, bottom);
        }
    }

    protected void renderSpecialPageFrame(GuiGraphics graphics, int top, int bottom) {
        int left = OUTER_MARGIN;
        int right = width - OUTER_MARGIN;
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, borderColor());
        graphics.fill(left, top, right, bottom, pageColor());
    }

    protected void renderHelpPage(GuiGraphics graphics, int top, int bottom) {
        int x = OUTER_MARGIN + 22;
        int y = top + 18;
        int contentWidth = Math.max(180, width - OUTER_MARGIN * 2 - 44);

        graphics.drawString(font, "Wiki Browser Help", x, y, TEXT, true);
        y += 22;
        graphics.drawString(font, "Keyboard shortcuts", x, y, LINK, true);
        y += 16;

        String[][] shortcuts = {
                {"Ctrl+L / Ctrl+K", "Focus Wiki search"},
                {"Ctrl+T", "Create a new tab"},
                {"Ctrl+W", "Close the current tab"},
                {"Ctrl+Tab", "Switch tabs"},
                {"Ctrl+F", "Find text on this page"},
                {"Ctrl+D", "Bookmark the current page"},
                {"Ctrl+R", "Reload"},
                {"Alt+Left / Alt+Right", "Back / Forward"},
                {"Middle click / Ctrl+click", "Open a link in a new tab"},
                {"Right click", "Open the link menu"}
        };
        for (String[] shortcut : shortcuts) {
            graphics.drawString(font, shortcut[0], x + 4, y, TEXT, false);
            graphics.drawString(font, shortcut[1], x + 130, y, MUTED, false);
            y += 14;
        }

        y += 8;
        boolean siteStyle = browserStore.websiteStyle();
        int toggleWidth = Math.min(330, contentWidth);
        boolean toggleHovered = contains(renderMouseX, renderMouseY, x, y, toggleWidth, 22);
        graphics.fill(x, y, x + toggleWidth, y + 22, toggleHovered ? tocHoverColor() : tableHeadColor());
        graphics.drawString(font, "Website styling: " + (siteStyle ? "ON" : "OFF"), x + 8, y + 7,
                siteStyle ? LINK : MUTED, true);
        specialPageHitboxes.add(new SpecialPageHitbox(x, y, toggleWidth, 22, SpecialAction.TOGGLE_STYLE, null));
        y += 34;

        graphics.drawString(font, "Bookmarks", x, y, LINK, true);
        y += 18;
        List<WikiBrowserStore.Bookmark> bookmarks = browserStore.bookmarks();
        if (bookmarks.isEmpty()) {
            graphics.drawString(font, "Right-click a Wiki link or press Ctrl+D to add a bookmark.", x + 4, y, MUTED, false);
        } else {
            for (WikiBrowserStore.Bookmark bookmark : bookmarks) {
                if (y + 18 > bottom - 8) break;
                int rowWidth = Math.min(contentWidth, 420);
                boolean hovered = contains(renderMouseX, renderMouseY, x, y, rowWidth, 18);
                graphics.fill(x, y, x + rowWidth, y + 18, hovered ? tocHoverColor() : tocBackgroundColor());
                graphics.drawString(font, "★ " + font.plainSubstrByWidth(bookmark.title(), rowWidth - 18),
                        x + 6, y + 5, LINK, false);
                specialPageHitboxes.add(new SpecialPageHitbox(
                        x, y, rowWidth, 18, SpecialAction.OPEN_BOOKMARK, bookmark.parsedUri()));
                y += 20;
            }
        }
    }

    protected void renderNewTabPage(GuiGraphics graphics, int top, int bottom) {
        int x = OUTER_MARGIN + 24;
        int y = top + 24;
        graphics.drawString(font, "New Wiki Tab", x, y, TEXT, true);
        y += 20;
        graphics.drawString(font, "Use the search bar above. It accepts page names and internal IDs such as ASPECT_OF_THE_END.",
                x, y, MUTED, false);
        y += 30;
        int buttonWidth = 190;
        boolean hovered = contains(renderMouseX, renderMouseY, x, y, buttonWidth, 22);
        graphics.fill(x, y, x + buttonWidth, y + 22, hovered ? tocHoverColor() : tableHeadColor());
        graphics.drawString(font, "Focus search", x + 8, y + 7, LINK, true);
        specialPageHitboxes.add(new SpecialPageHitbox(x, y, buttonWidth, 22, SpecialAction.FOCUS_SEARCH, null));

        y += 38;
        graphics.drawString(font, "Bookmarks", x, y, LINK, true);
        y += 18;
        for (WikiBrowserStore.Bookmark bookmark : browserStore.bookmarks()) {
            if (y + 18 > bottom - 8) break;
            int rowWidth = Math.min(width - x - OUTER_MARGIN - 20, 420);
            boolean rowHovered = contains(renderMouseX, renderMouseY, x, y, rowWidth, 18);
            graphics.fill(x, y, x + rowWidth, y + 18, rowHovered ? tocHoverColor() : tocBackgroundColor());
            graphics.drawString(font, "★ " + font.plainSubstrByWidth(bookmark.title(), rowWidth - 18),
                    x + 6, y + 5, LINK, false);
            specialPageHitboxes.add(new SpecialPageHitbox(
                    x, y, rowWidth, 18, SpecialAction.OPEN_BOOKMARK, bookmark.parsedUri()));
            y += 20;
        }
    }

    protected void renderSearchSuggestions(GuiGraphics graphics) {
        if (addressBox == null || !addressBox.isFocused() || searchSuggestions.isEmpty()) {
            return;
        }
        int x = toolbarAddressX;
        int y = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + TOOLBAR_HEIGHT + 1;
        int width = Math.max(160, toolbarAddressWidth);
        int visible = Math.min(6, searchSuggestions.size());
        int height = visible * SEARCH_SUGGESTION_HEIGHT + 2;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, borderColor());
        graphics.fill(x, y, x + width, y + height, pageColor());

        for (int index = 0; index < visible; index++) {
            WikiTitleResolver.SearchResult result = searchSuggestions.get(index);
            int rowY = y + 1 + index * SEARCH_SUGGESTION_HEIGHT;
            boolean hovered = contains(renderMouseX, renderMouseY, x, rowY, width, SEARCH_SUGGESTION_HEIGHT);
            graphics.fill(x, rowY, x + width, rowY + SEARCH_SUGGESTION_HEIGHT,
                    hovered ? tocHoverColor() : pageColor());
            graphics.drawString(font, font.plainSubstrByWidth(result.title(), width - 12),
                    x + 6, rowY + 5, hovered ? TEXT : LINK, false);
            searchSuggestionHitboxes.add(new SearchSuggestionHitbox(
                    x, rowY, width, SEARCH_SUGGESTION_HEIGHT, result));
        }
    }

    protected void renderContextMenu(GuiGraphics graphics) {
        if (contextMenu == null) {
            return;
        }
        List<ContextAction> actions = contextActions(contextMenu.target());
        int menuWidth = 154;
        int menuHeight = actions.size() * CONTEXT_ROW_HEIGHT + 4;
        int x = Math.max(4, Math.min(contextMenu.x(), width - menuWidth - 4));
        int y = Math.max(4, Math.min(contextMenu.y(), height - menuHeight - 4));

        graphics.fill(x - 1, y - 1, x + menuWidth + 1, y + menuHeight + 1, TOOLTIP_BORDER_OUTER);
        graphics.fill(x, y, x + menuWidth, y + menuHeight, TOOLTIP_BACKGROUND);

        int rowY = y + 2;
        for (ContextAction action : actions) {
            boolean hovered = contains(renderMouseX, renderMouseY, x + 2, rowY, menuWidth - 4, CONTEXT_ROW_HEIGHT);
            if (hovered) {
                graphics.fill(x + 2, rowY, x + menuWidth - 2, rowY + CONTEXT_ROW_HEIGHT, tocHoverColor());
            }
            graphics.drawString(font, action.label(), x + 7, rowY + 5, hovered ? TEXT : MUTED, false);
            contextMenuHitboxes.add(new ContextMenuHitbox(
                    x + 2, rowY, menuWidth - 4, CONTEXT_ROW_HEIGHT, action, contextMenu.target()));
            rowY += CONTEXT_ROW_HEIGHT;
        }
    }

    protected void renderFindStatus(GuiGraphics graphics) {
        if (!findBarVisible || findBox == null) {
            return;
        }
        String status = findMatches.isEmpty()
                ? "0/0"
                : (activeFindIndex + 1) + "/" + findMatches.size();
        int x = toolbarFindStatusX;
        int y = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + 9;
        graphics.drawString(font, status, x, y, findMatches.isEmpty() ? MUTED : LINK, false);
        graphics.drawString(font, "↑↓", x + 31, y, MUTED, false);
    }

    protected void renderError(GuiGraphics graphics, int top) {
        graphics.drawCenteredString(font, Component.literal("Could not load the Wiki page").withStyle(ChatFormatting.RED),
                width / 2, top + 12, 0xFFFFFFFF);
        List<FormattedCharSequence> lines = font.split(Component.literal(errorMessage == null ? "Unknown error" : errorMessage)
                .withStyle(ChatFormatting.GRAY), Math.max(100, width - 120));
        int y = top + 34;
        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    protected void renderDocument(GuiGraphics graphics, int top, int bottom) {
        for (int index = 0; index < findMatches.size(); index++) {
            FindTarget target = findMatches.get(index);
            int y = top + target.y() - scrollPixels;
            if (y + target.height() < top || y > bottom) {
                continue;
            }
            int color = index == activeFindIndex ? 0x55FFD83D : 0x337A6A1E;
            graphics.fill(target.x(), y, target.x() + target.width(), y + target.height(), color);
        }

        for (RenderEntry entry : entries) {
            int y = top + entry.y() - scrollPixels;
            if (y + entry.height() < top || y > bottom) {
                continue;
            }
            renderEntry(graphics, entry, y);
        }
    }

    protected ScrollbarGeometry scrollbarGeometry() {
        if (maxScrollPixels <= 0) {
            return null;
        }
        int top = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + TOOLBAR_HEIGHT + 7;
        int bottom = height - FOOTER_HEIGHT - 4;
        int visible = Math.max(1, bottom - top);
        int thumbHeight = Math.max(16, visible * visible / Math.max(1, visible + maxScrollPixels));
        int thumbY = top + (visible - thumbHeight) * scrollPixels / Math.max(1, maxScrollPixels);
        int x = pageLeft + pageWidth - 7;
        return new ScrollbarGeometry(x, top, bottom, thumbY, thumbHeight);
    }

    protected void renderScrollbar(GuiGraphics graphics, int top, int bottom) {
        ScrollbarGeometry geometry = scrollbarGeometry();
        if (geometry == null) {
            return;
        }
        graphics.fill(geometry.x(), geometry.top(), geometry.x() + 5, geometry.bottom(), 0x66333B44);
        graphics.fill(geometry.x(), geometry.thumbY(), geometry.x() + 5,
                geometry.thumbY() + geometry.thumbHeight(), LINK);
    }

    protected boolean isScrollbarHovered(double mouseX, double mouseY) {
        ScrollbarGeometry geometry = scrollbarGeometry();
        return geometry != null && geometry.containsTrack(mouseX, mouseY);
    }

    protected void updateScrollFromDrag(double mouseY, ScrollbarGeometry geometry) {
        int travel = Math.max(1, geometry.trackHeight() - geometry.thumbHeight());
        double desiredTop = mouseY - scrollbarDragOffset;
        double clamped = Math.max(geometry.top(), Math.min(desiredTop, geometry.top() + travel));
        scrollPixels = (int) Math.round((clamped - geometry.top()) * maxScrollPixels / travel);
        scrollPixels = Math.max(0, Math.min(scrollPixels, maxScrollPixels));
        saveScreenToActiveTab();
    }


    protected int backgroundColor() {
        return browserStore.websiteStyle() ? BACKGROUND : 0xFF101318;
    }

    protected int headerColor() {
        return browserStore.websiteStyle() ? HEADER : 0xFF20262E;
    }

    protected int toolbarColor() {
        return browserStore.websiteStyle() ? 0xFF242432 : 0xFF181D23;
    }

    protected int pageColor() {
        return browserStore.websiteStyle() ? PAGE : 0xFF181D23;
    }

    protected int borderColor() {
        return browserStore.websiteStyle() ? BORDER : 0xFF39414B;
    }

    protected int tocHoverColor() {
        return browserStore.websiteStyle() ? TOC_HOVER : 0xFF303844;
    }

    protected int tocBackgroundColor() {
        return browserStore.websiteStyle() ? TOC_BACKGROUND : 0xFF20262D;
    }

    protected int tableHeadColor() {
        return browserStore.websiteStyle() ? TABLE_HEAD : 0xFF2A3541;
    }

    protected int tableRowColor() {
        return browserStore.websiteStyle() ? TABLE_ROW : 0xFF20262D;
    }

    protected int tableAltColor() {
        return browserStore.websiteStyle() ? TABLE_ALT : 0xFF252C34;
    }

    protected abstract void renderEntry(GuiGraphics graphics, RenderEntry entry, int y);
    protected abstract void renderHoveredSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY);
    protected abstract boolean containsToc(double mouseX, double mouseY);
    protected abstract boolean containsTab(double mouseX, double mouseY);
    protected abstract boolean containsLinkedSlot(double mouseX, double mouseY);
    protected abstract boolean containsLink(double mouseX, double mouseY);
    protected abstract boolean containsPageTab(double mouseX, double mouseY);
    protected abstract boolean containsContextMenu(double mouseX, double mouseY);
    protected abstract boolean containsSearchSuggestion(double mouseX, double mouseY);
    protected abstract boolean containsSpecialPageAction(double mouseX, double mouseY);
}