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

/** Mouse and keyboard interaction layer. */
abstract class WikiScreenInput extends WikiScreenInteractionRenderer {
    protected WikiScreenInput(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        boolean control = event.hasControlDownWithQuirk();

        if (contextMenu != null) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                for (ContextMenuHitbox hitbox : contextMenuHitboxes) {
                    if (hitbox.contains(event.x(), event.y())) {
                        executeContextAction(hitbox.action(), hitbox.target());
                        return true;
                    }
                }
            }
            contextMenu = null;
            contextMenuHitboxes.clear();
            if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            Optional<LinkTarget> target = linkTargetAt(event.x(), event.y());
            if (target.isPresent()) {
                showContextMenu(target.get(), event.x(), event.y());
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            Optional<LinkTarget> target = linkTargetAt(event.x(), event.y());
            if (target.isPresent()) {
                openResolvedUri(target.get().uri(), OpenDisposition.NEW_TAB);
                return true;
            }
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (SearchSuggestionHitbox hitbox : searchSuggestionHitboxes) {
                if (hitbox.contains(event.x(), event.y())) {
                    if (control) {
                        openResolvedUri(hitbox.result().pageUri(), OpenDisposition.NEW_TAB);
                    } else {
                        openResolvedUri(hitbox.result().pageUri(), OpenDisposition.CURRENT_TAB);
                    }
                    searchSuggestions = List.of();
                    return true;
                }
            }

            for (SpecialPageHitbox hitbox : specialPageHitboxes) {
                if (!hitbox.contains(event.x(), event.y())) {
                    continue;
                }
                switch (hitbox.action()) {
                    case TOGGLE_STYLE -> toggleWebsiteStyle();
                    case OPEN_BOOKMARK -> {
                        if (hitbox.uri() != null) {
                            openResolvedUri(hitbox.uri(), control ? OpenDisposition.NEW_TAB : OpenDisposition.CURRENT_TAB);
                        }
                    }
                    case FOCUS_SEARCH -> focusAddressBar(true);
                }
                return true;
            }

            if (newTabHitbox != null && newTabHitbox.contains(event.x(), event.y())) {
                createNewTab();
                return true;
            }

            for (PageTabHitbox hitbox : pageTabHitboxes) {
                if (!hitbox.contains(event.x(), event.y())) {
                    continue;
                }
                PageTab tab = browserTabs.get(hitbox.tabIndex());
                if (hitbox.isClose(event.x()) && tab.closable) {
                    closeBrowserTab(hitbox.tabIndex());
                } else {
                    activateBrowserTab(hitbox.tabIndex());
                }
                return true;
            }

            if (activeBrowserTab().kind == PageKind.ARTICLE && state == LoadState.LOADED) {
                ScrollbarGeometry geometry = scrollbarGeometry();
                if (geometry != null && geometry.containsTrack(event.x(), event.y())) {
                    draggingScrollbar = true;
                    if (geometry.containsThumb(event.x(), event.y())) {
                        scrollbarDragOffset = (int) Math.round(event.y() - geometry.thumbY());
                    } else {
                        scrollbarDragOffset = geometry.thumbHeight() / 2;
                        updateScrollFromDrag(event.y(), geometry);
                    }
                    return true;
                }

                for (LinkHitbox hitbox : linkHitboxes) {
                    if (hitbox.contains(event.x(), event.y())) {
                        openResolvedUri(hitbox.uri(), control ? OpenDisposition.NEW_TAB : OpenDisposition.CURRENT_TAB);
                        return true;
                    }
                }

                for (int index = slotHitboxes.size() - 1; index >= 0; index--) {
                    SlotHitbox hitbox = slotHitboxes.get(index);
                    if (hitbox.contains(event.x(), event.y()) && !hitbox.frame().link().isBlank()) {
                        openLink(hitbox.frame().link(), control ? OpenDisposition.NEW_TAB : OpenDisposition.CURRENT_TAB);
                        return true;
                    }
                }

                for (TocHitbox hitbox : tocHitboxes) {
                    if (hitbox.contains(event.x(), event.y())) {
                        scrollPixels = Math.max(0, Math.min(hitbox.item().targetY - 4, maxScrollPixels));
                        saveScreenToActiveTab();
                        return true;
                    }
                }

                for (TabHitbox hitbox : tabHitboxes) {
                    if (hitbox.contains(event.x(), event.y())) {
                        int current = selectedTabs.getOrDefault(hitbox.groupId(), 0);
                        if (current != hitbox.tabIndex()) {
                            selectedTabs.put(hitbox.groupId(), hitbox.tabIndex());
                            int oldScroll = scrollPixels;
                            rebuildLayout();
                            scrollPixels = Math.min(oldScroll, maxScrollPixels);
                            saveScreenToActiveTab();
                            updateFindMatches(false);
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean control = event.hasControlDownWithQuirk();
        boolean shift = event.hasShiftDown();
        boolean alt = event.hasAltDown();
        boolean enter = key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER;

        if (contextMenu != null && key == InputConstants.KEY_ESCAPE) {
            contextMenu = null;
            return true;
        }

        if (findBarVisible && findBox != null && findBox.isFocused()) {
            if (enter) {
                jumpFind(shift ? -1 : 1);
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                setFindBarVisible(false);
                return true;
            }
        }

        if (addressBox != null && addressBox.isFocused()) {
            if (enter) {
                if (!searchSuggestions.isEmpty()) {
                    WikiTitleResolver.SearchResult first = searchSuggestions.get(0);
                    openResolvedUri(
                            first.pageUri(),
                            control ? OpenDisposition.NEW_TAB : OpenDisposition.CURRENT_TAB
                    );
                    searchSuggestions = List.of();
                    addressBox.setFocused(false);
                } else {
                    submitAddress(control);
                }
                return true;
            }
            if (key == InputConstants.KEY_ESCAPE) {
                addressBox.setFocused(false);
                searchSuggestions = List.of();
                return true;
            }
        }

        if (control && (key == InputConstants.KEY_L || key == InputConstants.KEY_K)) {
            focusAddressBar(false);
            return true;
        }
        if (control && key == InputConstants.KEY_T) {
            createNewTab();
            return true;
        }
        if (control && key == InputConstants.KEY_W) {
            if (activeBrowserTab().closable) {
                closeBrowserTab(activeBrowserTabIndex);
            }
            return true;
        }
        if (control && key == InputConstants.KEY_TAB) {
            int direction = shift ? -1 : 1;
            activateBrowserTab(Math.floorMod(activeBrowserTabIndex + direction, browserTabs.size()));
            return true;
        }
        if (control && key == InputConstants.KEY_F) {
            setFindBarVisible(true);
            return true;
        }
        if (control && key == InputConstants.KEY_D) {
            toggleCurrentBookmark();
            return true;
        }
        if (control && key == InputConstants.KEY_R) {
            loadPage(true);
            return true;
        }
        if (alt && key == InputConstants.KEY_LEFT) {
            navigateBack();
            return true;
        }
        if (alt && key == InputConstants.KEY_RIGHT) {
            navigateForward();
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE && findBarVisible) {
            setFindBarVisible(false);
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double offsetX, double offsetY) {
        if (draggingScrollbar && event.button() == 0) {
            ScrollbarGeometry geometry = scrollbarGeometry();
            if (geometry != null) {
                updateScrollFromDrag(event.y(), geometry);
            }
            return true;
        }
        return super.mouseDragged(event, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar && event.button() == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (state != LoadState.LOADED) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        scrollPixels -= (int) Math.round(verticalAmount * SCROLL_STEP);
        scrollPixels = Math.max(0, Math.min(scrollPixels, maxScrollPixels));
        saveScreenToActiveTab();
        return true;
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