package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.service.HypixelWikiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
            if (visible) {
                addressBox.setFocused(false);
            }
        }
        if (findBox != null) {
            findBox.setVisible(visible);
            findBox.setFocused(visible);
            if (visible) {
                setFocused(findBox);
            } else {
                setFocused(null);
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
        if (target.isImage() || HypixelWikiService.isWikiArticleUri(target.uri())) {
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
            case OPEN -> openLinkTarget(target, OpenDisposition.CURRENT_TAB);
            case OPEN_NEW_TAB -> openLinkTarget(target, OpenDisposition.NEW_TAB);
            case OPEN_EXTERNALLY -> openResolvedUri(target.uri(), OpenDisposition.EXTERNAL);
            case COPY_LINK -> Minecraft.getInstance().keyboardHandler.setClipboard(target.uri().toString());
            case ADD_BOOKMARK, REMOVE_BOOKMARK -> toggleBookmark(target);
        }
    }

    protected Optional<LinkTarget> linkTargetAt(double mouseX, double mouseY) {
        // A slot owns the pixels of its item image. Do not let the nested image
        // hitbox turn UI controls into links to File:/thumbnail URLs.
        for (int index = slotHitboxes.size() - 1; index >= 0; index--) {
            SlotHitbox hitbox = slotHitboxes.get(index);
            if (!hitbox.contains(mouseX, mouseY)) continue;

            WikiItemSlot.Frame frame = hitbox.frame();
            if (!frame.uiTarget().isBlank()) {
                return Optional.empty();
            }
            if (!frame.link().isBlank()) {
                URI uri = resolveHref(frame.link());
                if (uri != null) {
                    return Optional.of(new LinkTarget(uri, frame.displayName()));
                }
            }

            // It is still a slot even if it has no action. Never fall through
            // to the image hitbox underneath it.
            return Optional.empty();
        }

        for (int index = imageHitboxes.size() - 1; index >= 0; index--) {
            ImageHitbox hitbox = imageHitboxes.get(index);
            if (!hitbox.contains(mouseX, mouseY) || hitbox.image().isEmpty()) {
                continue;
            }

            try {
                URI uri = URI.create(hitbox.image().url());
                String title = hitbox.image().displayName();
                if (title.isBlank()) title = "Wiki Image";
                return Optional.of(new LinkTarget(uri, title, hitbox.image()));
            } catch (IllegalArgumentException ignored) {
                // Malformed image URL: keep looking for another target.
            }
        }

        for (LinkHitbox hitbox : linkHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return Optional.of(new LinkTarget(hitbox.uri(), articleLabel(hitbox.uri())));
            }
        }
        return Optional.empty();
    }

    protected boolean activateUiTarget(String groupKey, String targetId) {
        if (page == null || targetId == null || targetId.isBlank()) return false;

        UiTargetMatch match = findUiTargetMatch(page.blocks(), groupKey, targetId);

        // Some UI templates/transclusions lose the synthetic group marker while
        // their goto-* class survives. Fall back to the unique panel target on
        // the page instead of silently swallowing the click.
        if (match == null && groupKey != null && !groupKey.isBlank()) {
            match = findUiTargetMatch(page.blocks(), "", targetId);
        }
        if (match == null) return false;

        int oldScroll = scrollPixels;
        selectedTabs.put(uiSelectionKeyForActions(match.groupKey()), match.panelIndex());
        rebuildLayout();
        scrollPixels = Math.max(0, Math.min(oldScroll, maxScrollPixels));
        saveScreenToActiveTab();
        updateFindMatches(false);
        return true;
    }

    protected static int uiSelectionKeyForActions(String groupKey) {
        return -1 - (groupKey == null ? 0 : (groupKey.hashCode() & 0x7fffffff));
    }

    protected static String normalizeUiId(String value) {
        if (value == null) return "";
        String result = value.trim().toLowerCase(Locale.ROOT);
        while (result.startsWith("#")) result = result.substring(1);
        if (result.startsWith("ui-")) result = result.substring(3);

        // The Wiki uses both dashes and underscores in UI ids/goto targets.
        // Treat them as equivalent for local panel navigation.
        return result.replace("-", "_");
    }

    protected static UiTargetMatch findUiTargetMatch(List<WikiBlock> blocks, String requestedGroupKey, String targetId) {
        if (blocks == null) return null;

        String wantedTarget = normalizeUiId(targetId);
        boolean restrictGroup = requestedGroupKey != null && !requestedGroupKey.isBlank();

        for (WikiBlock block : blocks) {
            if (block instanceof WikiBlock.UiGroup group) {
                boolean groupMatches = !restrictGroup || group.key().equals(requestedGroupKey);
                if (groupMatches) {
                    for (int index = 0; index < group.panels().size(); index++) {
                        if (normalizeUiId(group.panels().get(index).id()).equals(wantedTarget)) {
                            return new UiTargetMatch(group.key(), index);
                        }
                    }
                }
                for (WikiBlock.UiGroup.Panel panel : group.panels()) {
                     UiTargetMatch nested = findUiTargetMatch(panel.blocks(), requestedGroupKey, targetId);
                    if (nested != null) return nested;
                }
            } else if (block instanceof WikiBlock.TabGroup tabs) {
                for (WikiBlock.TabGroup.Tab tab : tabs.tabs()) {
                    UiTargetMatch nested = findUiTargetMatch(tab.blocks(), requestedGroupKey, targetId);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
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

    protected void openLinkTarget(LinkTarget target, OpenDisposition disposition) {
        if (target == null || target.uri() == null) {
            return;
        }

        URI uri = target.uri();

        if (target.isImage()) {
            URI filePage = wikiFilePageUri(target.image());
            if (filePage != null) {
                uri = filePage;
            }
        }

        openResolvedUri(uri, disposition);
    }

    protected URI wikiFilePageUri(WikiImage image) {
        if (image == null || image.isEmpty()) {
            return null;
        }

        String fileName = image.displayName().trim();

        // Usually alt/title already contains the real filename, e.g.
        // "Derpy (Old).png".
        if (fileName.isBlank() || !fileName.contains(".")) {
            String rawPath;

            try {
                rawPath = URI.create(image.url()).getRawPath();
            } catch (IllegalArgumentException exception) {
                return null;
            }

            if (rawPath == null || rawPath.isBlank()) {
                return null;
            }

            // MediaWiki thumbnail URL:
            // /images/thumb/Derpy_%28Old%29.png/56px-Derpy_%28Old%29.png
            int thumb = rawPath.indexOf("/thumb/");
            if (thumb >= 0) {
                String afterThumb = rawPath.substring(thumb + "/thumb/".length());
                int slash = afterThumb.indexOf('/');

                if (slash > 0) {
                    fileName = afterThumb.substring(0, slash);
                }
            }

            if (fileName.isBlank() || !fileName.contains(".")) {
                int slash = rawPath.lastIndexOf('/');
                fileName = slash >= 0
                        ? rawPath.substring(slash + 1)
                        : rawPath;

                // Strip MediaWiki thumbnail prefixes such as "56px-".
                fileName = fileName.replaceFirst("^\\d+px-", "");
            }

            try {
                fileName = java.net.URLDecoder.decode(
                        fileName,
                        StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (fileName.isBlank()) {
            return null;
        }

        String encoded = URLEncoder.encode(
                        fileName.replace(' ', '_'),
                        StandardCharsets.UTF_8
                )
                .replace("+", "%20");

        return URI.create(
                "https://hypixelskyblock.minecraft.wiki/w/File:" + encoded
        );
    }

    protected record UiTargetMatch(String groupKey, int panelIndex) { }

    /** Implemented by the layout layer. */
}
