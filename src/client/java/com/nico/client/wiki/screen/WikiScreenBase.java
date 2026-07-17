package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiBrowserStore;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiTitleResolver;
import com.nico.client.wiki.service.HypixelWikiService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Shared widgets, browser state, asynchronous loading, and tab snapshots. */
abstract class WikiScreenBase extends Screen {
    protected static final int OUTER_MARGIN = 16;
    protected static final int HEADER_HEIGHT = 56;
    protected static final int FOOTER_HEIGHT = 31;
    protected static final int BROWSER_TAB_HEIGHT = 22;
    protected static final int TOOLBAR_HEIGHT = 25;
    protected static final long ANIMATION_PERIOD_MILLIS = 3_000L;
    protected static final int PAGE_PADDING = 16;
    protected static final int COLUMN_GAP = 14;
    protected static final int INFOBOX_WIDTH = 240;
    protected static final int MIN_ARTICLE_WIDTH = 500;
    protected static final int MAX_PAGE_WIDTH = 1240;
    protected static final int LINE_HEIGHT = 12;
    protected static final int SCROLL_STEP = 28;

    protected static final int BACKGROUND = 0xFF121426;
    protected static final int HEADER = 0xFF1D1D2B;
    protected static final int PAGE = 0xFF171A24;
    protected static final int BORDER = 0xFF667084;
    protected static final int TEXT = 0xFFFFFFFF;
    protected static final int MUTED = 0xFFD8DEE9;
    protected static final int LINK = 0xFF55C8F5;
    protected static final int BLUE = 0xFF3B98C2;
    protected static final int BLUE_DARK = 0xFF3489B0;
    protected static final int DIVIDER = 0xFF707B90;
    protected static final int TABLE_HEAD = 0xFF343B4D;
    protected static final int TABLE_ROW = 0xFF181C27;
    protected static final int TABLE_ALT = 0xFF202635;
    protected static final int SLOT = 0xFF8B8B8B;
    protected static final int SLOT_BORDER_DARK = 0xFF373737;
    protected static final int SLOT_BORDER_LIGHT = 0xFFFFFFFF;
    protected static final int SLOT_BORDER = 0xFF575757;
    protected static final int CRAFTING_BACKGROUND = 0xFFC6C6C6;
    protected static final int CRAFTING_BORDER_DARK = 0xFF555555;
    protected static final int CRAFTING_BORDER_LIGHT = 0xFFFFFFFF;
    protected static final int CRAFTING_ARROW = 0xFF8B8B8B;
    protected static final int TAB = 0xFF2E2F3D;
    protected static final int TAB_ACTIVE = 0xFF3B98C2;
    protected static final int TOC_BACKGROUND = 0xFF2E2F3D;
    protected static final int TOC_HOVER = 0xFF3A3B4D;
    protected static final int TOOLTIP_BACKGROUND = 0xF0081326;
    protected static final int TOOLTIP_BORDER_OUTER = 0xFF000000;
    protected static final int TOOLTIP_BORDER_INNER = 0xFF2C7FD3;
    protected static final int TOOLTIP_TEXT = 0xFFC6C6C6;
    protected static final int TOOLTIP_MAX_WIDTH = 280;
    protected static final int CONTEXT_ROW_HEIGHT = 18;
    protected static final int SEARCH_SUGGESTION_HEIGHT = 18;

    protected final Screen parent;
    protected final ItemStack itemStack;
    protected final String initialTitle;

    protected final List<RenderEntry> entries = new ArrayList<>();
    protected final List<TabHitbox> tabHitboxes = new ArrayList<>();
    protected final List<TocHitbox> tocHitboxes = new ArrayList<>();
    protected final List<SlotHitbox> slotHitboxes = new ArrayList<>();
    protected final List<LinkHitbox> linkHitboxes = new ArrayList<>();
    protected final List<PageTabHitbox> pageTabHitboxes = new ArrayList<>();
    protected final List<ContextMenuHitbox> contextMenuHitboxes = new ArrayList<>();
    protected final List<SearchSuggestionHitbox> searchSuggestionHitboxes = new ArrayList<>();
    protected final List<SpecialPageHitbox> specialPageHitboxes = new ArrayList<>();
    protected final List<FindTarget> findTargets = new ArrayList<>();
    protected final List<FindTarget> findMatches = new ArrayList<>();
    protected final Map<Integer, Integer> selectedTabs = new HashMap<>();
    protected final List<PageTab> browserTabs = new ArrayList<>();
    protected final WikiBrowserStore browserStore = WikiBrowserStore.get();

    protected LoadState state = LoadState.LOADING;
    protected WikiPage page;
    protected String visibleTitle;
    protected String errorMessage;
    protected int pageLeft;
    protected int pageWidth;
    protected int documentHeight;
    protected int scrollPixels;
    protected int maxScrollPixels;
    protected int nextTabGroup;
    protected int renderMouseX;
    protected int renderMouseY;
    protected int activeBrowserTabIndex;
    protected long lastAnimationStep = -1L;
    protected boolean draggingScrollbar;
    protected int scrollbarDragOffset;
    protected Button backButton;
    protected Button forwardButton;
    protected Button reloadButton;
    protected EditBox addressBox;
    protected EditBox findBox;
    protected List<WikiTitleResolver.SearchResult> searchSuggestions = List.of();
    protected ContextMenu contextMenu;
    protected boolean findBarVisible;
    protected String findQuery = "";
    protected int activeFindIndex = -1;
    protected NewTabHitbox newTabHitbox;
    protected boolean suppressAddressResponder;
    protected int searchRequestSerial;
    protected int toolbarAddressX;
    protected int toolbarAddressWidth;
    protected int toolbarFindWidth;
    protected int toolbarFindStatusX;

    protected WikiScreenBase(Screen parent, ItemStack itemStack) {
        super(Component.literal("Hypixel SkyBlock Wiki"));
        this.parent = parent;
        this.itemStack = itemStack;
        this.initialTitle = itemStack.getHoverName().getString();
        this.visibleTitle = initialTitle;
        this.browserTabs.add(PageTab.help());
        this.browserTabs.add(PageTab.initial(initialTitle));
        this.activeBrowserTabIndex = 1;
    }

    @Override
    protected void init() {
        super.init();

        int toolbarY = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + 3;
        int buttonY = height - 25;

        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(OUTER_MARGIN, buttonY, 60, 20).build());

        backButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> navigateBack())
                .bounds(OUTER_MARGIN, toolbarY, 22, 19).build());

        forwardButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> navigateForward())
                .bounds(OUTER_MARGIN + 24, toolbarY, 22, 19).build());

        reloadButton = addRenderableWidget(Button.builder(Component.literal("R"), button -> loadPage(true))
                .bounds(OUTER_MARGIN + 48, toolbarY, 22, 19).build());

        toolbarAddressX = OUTER_MARGIN + 74;
        toolbarAddressWidth = Math.max(120, width - toolbarAddressX - OUTER_MARGIN - 72);
        toolbarFindWidth = Math.max(80, toolbarAddressWidth - 72);
        toolbarFindStatusX = toolbarAddressX + toolbarFindWidth + 7;

        addressBox = addRenderableWidget(new EditBox(
                font,
                toolbarAddressX,
                toolbarY,
                toolbarAddressWidth,
                19,
                Component.literal("Wiki search")
        ));
        addressBox.setMaxLength(256);
        addressBox.setHint(Component.literal("Search Wiki or enter an item ID").withStyle(ChatFormatting.DARK_GRAY));
        addressBox.setResponder(this::onAddressChanged);

        addRenderableWidget(Button.builder(Component.literal("★"), button -> toggleCurrentBookmark())
                .bounds(width - OUTER_MARGIN - 66, toolbarY, 30, 19).build());

        addRenderableWidget(Button.builder(Component.literal("?"), button -> activateBrowserTab(0))
                .bounds(width - OUTER_MARGIN - 34, toolbarY, 30, 19).build());

        findBox = addRenderableWidget(new EditBox(
                font,
                toolbarAddressX,
                toolbarY,
                toolbarFindWidth,
                19,
                Component.literal("Find on page")
        ));
        findBox.setMaxLength(128);
        findBox.setHint(Component.literal("Find on page").withStyle(ChatFormatting.DARK_GRAY));
        findBox.setResponder(this::onFindChanged);
        findBox.setVisible(false);

        updateNavigationButtons();
        PageTab tab = activeBrowserTab();
        if (tab.kind == PageKind.ARTICLE && tab.page == null && tab.state != LoadState.ERROR) {
            loadPage(false);
        } else {
            applyActiveTabToScreen();
        }
    }

    protected void loadPage(boolean reload) {
        PageTab targetTab = activeBrowserTab();
        if (targetTab.kind != PageKind.ARTICLE) {
            applyActiveTabToScreen();
            return;
        }

        targetTab.state = LoadState.LOADING;
        targetTab.errorMessage = null;
        if (targetTab.requestUri == null && targetTab.requestQuery.isBlank()) {
            targetTab.visibleTitle = initialTitle;
        }
        targetTab.scrollPixels = 0;
        targetTab.selectedTabs.clear();

        applyActiveTabToScreen();
        entries.clear();
        clearRenderHitboxes();

        CompletableFuture<WikiPage> future;
        if (!targetTab.requestQuery.isBlank()) {
            future = reload
                    ? HypixelWikiService.reloadPageQuery(targetTab.requestQuery)
                    : HypixelWikiService.findPageQuery(targetTab.requestQuery);
        } else if (targetTab.requestUri == null) {
            future = reload
                    ? HypixelWikiService.reloadPage(itemStack)
                    : HypixelWikiService.findPage(itemStack);
        } else {
            future = reload
                    ? HypixelWikiService.reloadPage(targetTab.requestUri)
                    : HypixelWikiService.findPage(targetTab.requestUri);
        }

        future.whenComplete((loaded, throwable) -> Minecraft.getInstance().execute(() -> {
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                targetTab.errorMessage = cause.getMessage();
                if (targetTab.errorMessage == null || targetTab.errorMessage.isBlank()) {
                    targetTab.errorMessage = cause.getClass().getSimpleName();
                }
                targetTab.page = null;
                targetTab.state = LoadState.ERROR;
            } else {
                targetTab.page = loaded;
                targetTab.visibleTitle = loaded.title();
                targetTab.state = LoadState.LOADED;
                targetTab.requestUri = loaded.pageUri();
            }

            if (targetTab == activeBrowserTab()) {
                applyActiveTabToScreen();
                if (targetTab.state == LoadState.LOADED) {
                    scrollToPendingFragment(targetTab);
                }
            }
            updateNavigationButtons();
        }));
    }

    protected static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    protected PageTab activeBrowserTab() {
        activeBrowserTabIndex = Math.max(0, Math.min(activeBrowserTabIndex, browserTabs.size() - 1));
        return browserTabs.get(activeBrowserTabIndex);
    }

    protected void saveScreenToActiveTab() {
        if (browserTabs.isEmpty()) {
            return;
        }
        PageTab tab = activeBrowserTab();
        tab.page = page;
        tab.state = state;
        tab.visibleTitle = visibleTitle;
        tab.errorMessage = errorMessage;
        tab.scrollPixels = scrollPixels;
        tab.selectedTabs.clear();
        tab.selectedTabs.putAll(selectedTabs);
    }

    protected void applyActiveTabToScreen() {
        PageTab tab = activeBrowserTab();
        page = tab.page;
        state = tab.state;
        visibleTitle = tab.visibleTitle;
        errorMessage = tab.errorMessage;
        scrollPixels = tab.scrollPixels;
        selectedTabs.clear();
        selectedTabs.putAll(tab.selectedTabs);

        findTargets.clear();
        if (tab.kind == PageKind.ARTICLE && state == LoadState.LOADED && page != null) {
            rebuildLayout();
            scrollPixels = Math.max(0, Math.min(scrollPixels, maxScrollPixels));
        } else {
            entries.clear();
            maxScrollPixels = 0;
        }

        if (addressBox != null) {
            suppressAddressResponder = true;
            String address = tab.kind == PageKind.HELP
                    ? ""
                    : !tab.requestQuery.isBlank()
                    ? tab.requestQuery
                    : tab.requestUri != null
                    ? tab.requestUri.toString()
                    : tab.kind == PageKind.NEW_TAB ? "" : initialTitle;
            addressBox.setValue(address);
            suppressAddressResponder = false;
        }

        clearRenderHitboxes();
        updateNavigationButtons();
        updateFindMatches(false);
    }

    protected void clearRenderHitboxes() {
        tabHitboxes.clear();
        tocHitboxes.clear();
        slotHitboxes.clear();
        linkHitboxes.clear();
        pageTabHitboxes.clear();
        contextMenuHitboxes.clear();
        searchSuggestionHitboxes.clear();
        specialPageHitboxes.clear();
        newTabHitbox = null;
    }


    protected abstract void rebuildLayout();
    protected abstract List<TocItem> buildToc(List<WikiBlock> blocks);
    protected abstract void navigateBack();
    protected abstract void navigateForward();
    protected abstract void toggleCurrentBookmark();
    protected abstract void activateBrowserTab(int index);
    protected abstract void scrollToPendingFragment(PageTab tab);
    protected abstract void onAddressChanged(String value);
    protected abstract void onFindChanged(String value);
    protected abstract void updateNavigationButtons();
    protected abstract void updateFindMatches(boolean jumpToFirst);
}