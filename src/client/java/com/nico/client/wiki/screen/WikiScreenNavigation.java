package com.nico.client.wiki.screen;

import com.nico.client.wiki.service.HypixelWikiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Tab history, Wiki-link navigation, address-bar submission, and URL resolution. */
abstract class WikiScreenNavigation extends WikiScreenBase {
    protected WikiScreenNavigation(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    protected PageSnapshot currentSnapshot() {
        PageTab tab = activeBrowserTab();
        return new PageSnapshot(
                page,
                state,
                visibleTitle,
                errorMessage,
                tab.kind,
                tab.requestUri,
                tab.requestQuery,
                scrollPixels,
                new HashMap<>(selectedTabs)
        );
    }

    protected void restoreSnapshot(PageTab tab, PageSnapshot snapshot) {
        tab.page = snapshot.page();
        tab.state = snapshot.state();
        tab.visibleTitle = snapshot.visibleTitle();
        tab.errorMessage = snapshot.errorMessage();
        tab.kind = snapshot.kind();
        tab.requestUri = snapshot.requestUri();
        tab.requestQuery = snapshot.requestQuery();
        tab.scrollPixels = snapshot.scrollPixels();
        tab.selectedTabs.clear();
        tab.selectedTabs.putAll(snapshot.selectedTabs());
        tab.pendingFragment = "";
        applyActiveTabToScreen();
    }

    protected void navigateBack() {
        PageTab tab = activeBrowserTab();
        if (tab.backHistory.isEmpty()) {
            return;
        }
        saveScreenToActiveTab();
        tab.forwardHistory.addLast(currentSnapshot());
        restoreSnapshot(tab, tab.backHistory.removeLast());
        updateNavigationButtons();
    }

    protected void navigateForward() {
        PageTab tab = activeBrowserTab();
        if (tab.forwardHistory.isEmpty()) {
            return;
        }
        saveScreenToActiveTab();
        tab.backHistory.addLast(currentSnapshot());
        restoreSnapshot(tab, tab.forwardHistory.removeLast());
        updateNavigationButtons();
    }

    protected void updateNavigationButtons() {
        if (browserTabs.isEmpty()) {
            return;
        }
        PageTab tab = activeBrowserTab();
        if (backButton != null) {
            backButton.active = !tab.backHistory.isEmpty();
        }
        if (forwardButton != null) {
            forwardButton.active = !tab.forwardHistory.isEmpty();
        }
        if (reloadButton != null) {
            reloadButton.active = tab.kind == PageKind.ARTICLE;
        }
    }

    protected void activateBrowserTab(int index) {
        if (index < 0 || index >= browserTabs.size() || index == activeBrowserTabIndex) {
            return;
        }
        saveScreenToActiveTab();
        activeBrowserTabIndex = index;
        closeTransientUi();
        applyActiveTabToScreen();
    }

    protected void closeBrowserTab(int index) {
        if (index < 0 || index >= browserTabs.size() || !browserTabs.get(index).closable) {
            return;
        }

        saveScreenToActiveTab();
        browserTabs.remove(index);
        if (activeBrowserTabIndex > index) {
            activeBrowserTabIndex--;
        } else if (activeBrowserTabIndex >= browserTabs.size()) {
            activeBrowserTabIndex = browserTabs.size() - 1;
        }
        closeTransientUi();
        applyActiveTabToScreen();
    }

    protected void createNewTab() {
        saveScreenToActiveTab();
        browserTabs.add(PageTab.newTab());
        activeBrowserTabIndex = browserTabs.size() - 1;
        closeTransientUi();
        applyActiveTabToScreen();
        focusAddressBar(true);
    }

    protected void openLink(String href, OpenDisposition disposition) {
        URI uri = resolveHref(href);
        if (uri != null) {
            openResolvedUri(uri, disposition);
        }
    }

    protected void openResolvedUri(URI uri) {
        openResolvedUri(uri, OpenDisposition.CURRENT_TAB);
    }

    protected void openResolvedUri(URI uri, OpenDisposition disposition) {
        if (uri == null) {
            return;
        }

        if (disposition == OpenDisposition.EXTERNAL || !HypixelWikiService.isWikiArticleUri(uri)) {
            ConfirmLinkScreen.confirmLinkNow(this, uri, false);
            return;
        }

        if (page != null && sameArticle(page.pageUri(), uri) && uri.getFragment() != null
                && disposition == OpenDisposition.CURRENT_TAB) {
            scrollToAnchor(uri.getFragment());
            return;
        }

        if (disposition == OpenDisposition.NEW_TAB) {
            openWikiLinkInNewTab(uri);
        } else {
            openWikiLinkInCurrentTab(uri);
        }
    }

    protected void openWikiLinkInCurrentTab(URI uri) {
        // The permanent Help/Wiki tab is a launcher and documentation page.
        // Navigating from it must never replace or destroy it.
        if (activeBrowserTab().kind == PageKind.HELP) {
            openWikiLinkInNewTab(uri);
            return;
        }

        saveScreenToActiveTab();
        PageTab tab = activeBrowserTab();
        tab.backHistory.addLast(currentSnapshot());
        tab.forwardHistory.clear();
        tab.kind = PageKind.ARTICLE;
        tab.page = null;
        tab.state = LoadState.LOADING;
        tab.visibleTitle = articleLabel(uri);
        tab.requestUri = uri;
        tab.requestQuery = "";
        tab.pendingFragment = Objects.requireNonNullElse(uri.getFragment(), "");
        tab.scrollPixels = 0;
        tab.selectedTabs.clear();
        closeTransientUi();
        applyActiveTabToScreen();
        loadPage(false);
    }

    protected void openWikiLinkInNewTab(URI uri) {
        saveScreenToActiveTab();
        String label = articleLabel(uri);
        PageTab tab = PageTab.link(label, uri);
        tab.pendingFragment = Objects.requireNonNullElse(uri.getFragment(), "");
        browserTabs.add(tab);
        activeBrowserTabIndex = browserTabs.size() - 1;
        closeTransientUi();
        applyActiveTabToScreen();
        loadPage(false);
    }

    protected void openQueryInCurrentTab(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return;
        }

        try {
            URI uri = URI.create(query);
            if (uri.getScheme() != null) {
                OpenDisposition disposition = activeBrowserTab().kind == PageKind.HELP
                        ? OpenDisposition.NEW_TAB
                        : OpenDisposition.CURRENT_TAB;
                openResolvedUri(uri, disposition);
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Treat it as a Wiki search query.
        }

        // Searching from the permanent Help/Wiki tab opens a normal article tab.
        if (activeBrowserTab().kind == PageKind.HELP) {
            openQueryInNewTab(query);
            return;
        }

        saveScreenToActiveTab();
        PageTab tab = activeBrowserTab();
        tab.backHistory.addLast(currentSnapshot());
        tab.forwardHistory.clear();
        tab.kind = PageKind.ARTICLE;
        tab.page = null;
        tab.state = LoadState.LOADING;
        tab.visibleTitle = query;
        tab.requestUri = null;
        tab.requestQuery = query;
        tab.pendingFragment = "";
        tab.scrollPixels = 0;
        tab.selectedTabs.clear();
        closeTransientUi();
        applyActiveTabToScreen();
        loadPage(false);
    }

    protected void openQueryInNewTab(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return;
        }
        PageTab tab = PageTab.query(query);
        browserTabs.add(tab);
        activeBrowserTabIndex = browserTabs.size() - 1;
        closeTransientUi();
        applyActiveTabToScreen();
        loadPage(false);
    }

    protected URI resolveHref(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        try {
            URI base = page != null && page.pageUri() != null
                    ? page.pageUri()
                    : URI.create("https://hypixelskyblock.minecraft.wiki/w/");
            URI resolved = base.resolve(href.trim());
            String scheme = resolved.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return resolved;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    protected static boolean sameArticle(URI left, URI right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getHost(), right.getHost())
                && Objects.equals(left.getPath(), right.getPath());
    }

    protected static String articleLabel(URI uri) {
        String path = uri.getPath() == null ? "Wiki" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String value = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            value = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Keep the encoded label.
        }
        return value.replace('_', ' ').isBlank() ? "Wiki" : value.replace('_', ' ');
    }


    protected void onAddressChanged(String value) {
        if (suppressAddressResponder) {
            return;
        }
        String query = value == null ? "" : value.trim();
        if (query.length() < 2 || query.startsWith("http://") || query.startsWith("https://")) {
            searchSuggestions = List.of();
            return;
        }

        int request = ++searchRequestSerial;
        HypixelWikiService.search(query, 6).whenComplete((results, throwable) ->
                Minecraft.getInstance().execute(() -> {
                    if (request != searchRequestSerial) {
                        return;
                    }
                    searchSuggestions = throwable == null && results != null ? List.copyOf(results) : List.of();
                }));
    }

    protected void submitAddress(boolean newTab) {
        if (addressBox == null) {
            return;
        }
        String query = addressBox.getValue().trim();
        if (query.isBlank()) {
            return;
        }
        searchSuggestions = List.of();
        addressBox.setFocused(false);
        if (newTab) {
            openQueryInNewTab(query);
        } else {
            openQueryInCurrentTab(query);
        }
    }

    protected void focusAddressBar(boolean clear) {
        if (addressBox == null) {
            return;
        }
        if (clear) {
            suppressAddressResponder = true;
            addressBox.setValue("");
            suppressAddressResponder = false;
        }
        addressBox.setVisible(true);
        addressBox.setFocused(true);
    }


    protected abstract void closeTransientUi();
    protected abstract void scrollToAnchor(String fragment);
}