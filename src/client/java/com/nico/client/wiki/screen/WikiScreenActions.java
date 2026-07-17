package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiBrowserStore;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiImageTextureCache;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;
import com.nico.client.wiki.WikiTitleResolver;
import com.nico.client.wiki.service.HypixelWikiService;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Find-in-page, bookmarks, context menus, style toggle, and anchor scrolling. */
abstract class WikiScreenActions extends WikiScreenNavigation {
    protected WikiScreenActions(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    protected void onFindChanged(String value) {
        findQuery = value == null ? "" : value.trim();
        updateFindMatches(true);
    }

    protected void setFindBarVisible(boolean visible) {
        findBarVisible = visible;
        if (addressBox != null) {
            addressBox.setVisible(!visible);
        }
        if (findBox != null) {
            findBox.setVisible(visible);
            findBox.setFocused(visible);
            if (!visible) {
                findBox.setValue("");
            }
        }
        if (!visible) {
            findQuery = "";
            findMatches.clear();
            activeFindIndex = -1;
        }
    }

    protected void updateFindMatches(boolean jumpToFirst) {
        findMatches.clear();
        if (findQuery.isBlank()) {
            activeFindIndex = -1;
            return;
        }
        String needle = findQuery.toLowerCase(Locale.ROOT);
        for (FindTarget target : findTargets) {
            if (target.text().toLowerCase(Locale.ROOT).contains(needle)) {
                findMatches.add(target);
            }
        }
        if (findMatches.isEmpty()) {
            activeFindIndex = -1;
            return;
        }
        boolean selectionReset = activeFindIndex < 0 || activeFindIndex >= findMatches.size();
        if (jumpToFirst || selectionReset) {
            activeFindIndex = 0;
        }
        if (jumpToFirst) {
            jumpToFindIndex(activeFindIndex);
        }
    }

    protected void jumpFind(int direction) {
        if (findMatches.isEmpty()) {
            return;
        }
        activeFindIndex = Math.floorMod(activeFindIndex + direction, findMatches.size());
        jumpToFindIndex(activeFindIndex);
    }

    protected void jumpToFindIndex(int index) {
        if (index < 0 || index >= findMatches.size()) {
            return;
        }
        FindTarget target = findMatches.get(index);
        scrollPixels = Math.max(0, Math.min(target.y() - 8, maxScrollPixels));
        saveScreenToActiveTab();
    }

    protected void toggleCurrentBookmark() {
        PageTab tab = activeBrowserTab();
        URI uri = page != null ? page.pageUri() : tab.requestUri;
        if (uri == null || !HypixelWikiService.isWikiArticleUri(uri)) {
            return;
        }
        browserStore.toggleBookmark(visibleTitle, uri);
    }

    protected void toggleBookmark(LinkTarget target) {
        if (target == null || target.uri() == null || !HypixelWikiService.isWikiArticleUri(target.uri())) {
            return;
        }
        browserStore.toggleBookmark(target.title(), target.uri());
    }

    protected void closeTransientUi() {
        contextMenu = null;
        contextMenuHitboxes.clear();
        searchSuggestions = List.of();
        searchSuggestionHitboxes.clear();
    }

    protected void showContextMenu(LinkTarget target, double mouseX, double mouseY) {
        if (target == null || target.uri() == null) {
            return;
        }
        contextMenu = new ContextMenu((int) Math.round(mouseX), (int) Math.round(mouseY), target);
        contextMenuHitboxes.clear();
        searchSuggestions = List.of();
    }

    protected List<ContextAction> contextActions(LinkTarget target) {
        List<ContextAction> actions = new ArrayList<>();
        actions.add(ContextAction.OPEN);
        if (HypixelWikiService.isWikiArticleUri(target.uri())) {
            actions.add(ContextAction.OPEN_NEW_TAB);
        }
        actions.add(ContextAction.OPEN_EXTERNALLY);
        actions.add(ContextAction.COPY_LINK);
        if (HypixelWikiService.isWikiArticleUri(target.uri())) {
            actions.add(browserStore.isBookmarked(target.uri())
                    ? ContextAction.REMOVE_BOOKMARK
                    : ContextAction.ADD_BOOKMARK);
        }
        return List.copyOf(actions);
    }

    protected void executeContextAction(ContextAction action, LinkTarget target) {
        contextMenu = null;
        switch (action) {
            case OPEN -> openResolvedUri(target.uri(), OpenDisposition.CURRENT_TAB);
            case OPEN_NEW_TAB -> openResolvedUri(target.uri(), OpenDisposition.NEW_TAB);
            case OPEN_EXTERNALLY -> openResolvedUri(target.uri(), OpenDisposition.EXTERNAL);
            case COPY_LINK -> Minecraft.getInstance().keyboardHandler.setClipboard(target.uri().toString());
            case ADD_BOOKMARK, REMOVE_BOOKMARK -> toggleBookmark(target);
        }
    }

    protected Optional<LinkTarget> linkTargetAt(double mouseX, double mouseY) {
        for (LinkHitbox hitbox : linkHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return Optional.of(new LinkTarget(hitbox.uri(), articleLabel(hitbox.uri())));
            }
        }
        for (int index = slotHitboxes.size() - 1; index >= 0; index--) {
            SlotHitbox hitbox = slotHitboxes.get(index);
            if (!hitbox.contains(mouseX, mouseY) || hitbox.frame().link().isBlank()) {
                continue;
            }
            URI uri = resolveHref(hitbox.frame().link());
            if (uri != null) {
                return Optional.of(new LinkTarget(uri, hitbox.frame().displayName()));
            }
        }
        return Optional.empty();
    }

    protected void toggleWebsiteStyle() {
        browserStore.setWebsiteStyle(!browserStore.websiteStyle());
        if (page != null) {
            rebuildLayout();
        }
    }

    protected void scrollToPendingFragment(PageTab tab) {
        if (tab.pendingFragment == null || tab.pendingFragment.isBlank()) {
            return;
        }
        scrollToAnchor(tab.pendingFragment);
        tab.pendingFragment = "";
    }

    protected void scrollToAnchor(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return;
        }
        String normalized = fragment.replace('_', ' ');
        for (TocItem item : buildToc(page == null ? List.of() : page.blocks())) {
            if (item.anchor.equalsIgnoreCase(fragment)
                    || item.title.equalsIgnoreCase(normalized)) {
                rebuildLayout();
                // targetY values are assigned during rebuild to the hitbox payloads.
                for (RenderEntry entry : entries) {
                    if (entry.payload() instanceof TocItem target
                            && (target.anchor.equalsIgnoreCase(fragment)
                            || target.title.equalsIgnoreCase(normalized))) {
                        scrollPixels = Math.max(0, Math.min(target.targetY - 4, maxScrollPixels));
                        saveScreenToActiveTab();
                        return;
                    }
                }
                break;
            }
        }
    }


    /** Implemented by the layout layer. */
}
