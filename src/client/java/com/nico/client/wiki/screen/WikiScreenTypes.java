package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiTitleResolver;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

enum PageKind { HELP, NEW_TAB, ARTICLE }
enum OpenDisposition { CURRENT_TAB, NEW_TAB, EXTERNAL }
enum SpecialAction { TOGGLE_STYLE, OPEN_BOOKMARK, FOCUS_SEARCH }

enum ContextAction {
    OPEN("Open Link"),
    OPEN_NEW_TAB("Open in New Tab"),
    OPEN_EXTERNALLY("Open Externally"),
    COPY_LINK("Copy Link"),
    ADD_BOOKMARK("Bookmark Link"),
    REMOVE_BOOKMARK("Remove Bookmark");

    final String label;

    ContextAction(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

enum LoadState { LOADING, LOADED, ERROR }

enum Kind {
    PAGE_TITLE, TEXT, H2, H3, HR, TOC_HEADER, TOC_ROW, TABLE, TABLE_ROW,
    INFOBOX_TITLE, INFOBOX_IMAGE, INFOBOX_SLOTS, INFOBOX_TABS, INFOBOX_HEADER, INFOBOX_ROW,
    SLOT_STRIP, IMAGE, TABS, TAB_BORDER, CRAFTING
}

record TableCellLayout(
        int row,
        int column,
        int rowSpan,
        int columnSpan,
        boolean header,
        List<FormattedCharSequence> lines,
        WikiContent content,
        int contentHeight
) {
    TableCellLayout {
        lines = lines == null ? List.of() : List.copyOf(lines);
        content = content == null ? WikiContent.empty() : content;
    }
}

record RenderedTableCell(
        int xOffset,
        int yOffset,
        int width,
        int height,
        boolean header,
        List<FormattedCharSequence> lines,
        WikiContent content
) {
    RenderedTableCell {
        lines = lines == null ? List.of() : List.copyOf(lines);
        content = content == null ? WikiContent.empty() : content;
    }
}

record TableLayout(
        int[] rowHeights,
        boolean[] headerRows,
        List<RenderedTableCell> cells
) {
    TableLayout {
        rowHeights = rowHeights == null ? new int[0] : rowHeights.clone();
        headerRows = headerRows == null ? new boolean[0] : headerRows.clone();
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}

record Cell(
        int xOffset,
        int yOffset,
        int width,
        List<FormattedCharSequence> lines,
        WikiContent richContent
) {
    Cell(int xOffset, int yOffset, int width, List<FormattedCharSequence> lines) {
        this(xOffset, yOffset, width, lines, null);
    }

    Cell {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}

record RenderEntry(Kind kind, int x, int y, int width, int height, List<Cell> cells, int aux, Object payload) {
    RenderEntry {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}


final class TocItem {
    final int level;
    final String number;
    final String title;
    final String anchor;
    int targetY;

    TocItem(int level, String number, String title, String anchor) {
        this.level = level;
        this.number = number == null ? "" : number;
        this.title = title == null ? "" : title;
        this.anchor = anchor == null ? "" : anchor;
    }
}

record TocHitbox(int x, int y, int width, int height, TocItem item) {
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

record SlotHitbox(int x, int y, int width, int height, WikiItemSlot.Frame frame) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record TabButton(int x, int y, int width, int height, int index, String title) { }
record TabPayload(List<TabButton> buttons, int selected) { }

record TabHitbox(int x, int y, int width, int height, int groupId, int tabIndex) {
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

record LinkHitbox(int x, int y, int width, int height, URI uri) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record PageTabHitbox(
        int x,
        int y,
        int width,
        int height,
        int tabIndex,
        int closeStartX
) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }

    boolean isClose(double mouseX) {
        return mouseX >= closeStartX;
    }
}

record NewTabHitbox(int x, int y, int width, int height) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record LinkTarget(URI uri, String title) { }
record ContextMenu(int x, int y, LinkTarget target) { }

record ContextMenuHitbox(
        int x, int y, int width, int height, ContextAction action, LinkTarget target
) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record SearchSuggestionHitbox(
        int x, int y, int width, int height, WikiTitleResolver.SearchResult result
) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record SpecialPageHitbox(
        int x, int y, int width, int height, SpecialAction action, URI uri
) {
    boolean contains(double mouseX, double mouseY) {
        return ScreenGeometry.contains(mouseX, mouseY, x, y, width, height);
    }
}

record FindTarget(String text, int x, int y, int width, int height) { }

record ScrollbarGeometry(
        int x,
        int top,
        int bottom,
        int thumbY,
        int thumbHeight
) {
    int trackHeight() {
        return bottom - top;
    }

    boolean containsTrack(double mouseX, double mouseY) {
        return mouseX >= x - 4 && mouseX < x + 9 && mouseY >= top && mouseY < bottom;
    }

    boolean containsThumb(double mouseX, double mouseY) {
        return containsTrack(mouseX, mouseY)
                && mouseY >= thumbY
                && mouseY < thumbY + thumbHeight;
    }
}

record PageSnapshot(
        WikiPage page,
        LoadState state,
        String visibleTitle,
        String errorMessage,
        PageKind kind,
        URI requestUri,
        String requestQuery,
        int scrollPixels,
        Map<Integer, Integer> selectedTabs
) {
    PageSnapshot {
        visibleTitle = visibleTitle == null ? "Wiki" : visibleTitle;
        errorMessage = errorMessage == null ? "" : errorMessage;
        kind = kind == null ? PageKind.ARTICLE : kind;
        requestQuery = requestQuery == null ? "" : requestQuery;
        selectedTabs = selectedTabs == null ? Map.of() : Map.copyOf(selectedTabs);
    }
}

final class PageTab {
    WikiPage page;
    LoadState state;
    String visibleTitle;
    String errorMessage;
    PageKind kind;
    URI requestUri;
    String requestQuery = "";
    String pendingFragment = "";
    int scrollPixels;
    final boolean closable;
    final Map<Integer, Integer> selectedTabs = new HashMap<>();
    final Deque<PageSnapshot> backHistory = new ArrayDeque<>();
    final Deque<PageSnapshot> forwardHistory = new ArrayDeque<>();

    PageTab(String visibleTitle, PageKind kind, URI requestUri, String requestQuery, boolean closable) {
        this.visibleTitle = visibleTitle == null || visibleTitle.isBlank() ? "Wiki" : visibleTitle;
        this.kind = kind;
        this.requestUri = requestUri;
        this.requestQuery = requestQuery == null ? "" : requestQuery;
        this.closable = closable;
        this.state = kind == PageKind.ARTICLE ? LoadState.LOADING : LoadState.LOADED;
        this.errorMessage = "";
    }

    static PageTab help() {
        return new PageTab("Help", PageKind.HELP, null, "", false);
    }

    static PageTab newTab() {
        return new PageTab("New Tab", PageKind.NEW_TAB, null, "", true);
    }

    static PageTab initial(String title) {
        return new PageTab(title, PageKind.ARTICLE, null, "", true);
    }

    static PageTab link(String title, URI uri) {
        return new PageTab(title, PageKind.ARTICLE, uri, "", true);
    }

    static PageTab query(String query) {
        return new PageTab(query, PageKind.ARTICLE, null, query, true);
    }
}

final class ScreenGeometry {
    private ScreenGeometry() { }

    static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

