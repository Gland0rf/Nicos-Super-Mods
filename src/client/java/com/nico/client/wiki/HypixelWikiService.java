package com.nico.client.wiki;

import com.nico.client.utils.BazaarService;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compatibility facade. New code may import
 * com.nico.client.wiki.service.HypixelWikiService directly.
 */
public final class HypixelWikiService {
    private HypixelWikiService() { }

    public static void setBazaarService(BazaarService service) {
        com.nico.client.wiki.service.HypixelWikiService.setBazaarService(service);
    }

    public static boolean isBazaarServiceConfigured() {
        return com.nico.client.wiki.service.HypixelWikiService.isBazaarServiceConfigured();
    }

    public static CompletableFuture<WikiPage> findPage(ItemStack stack) {
        return com.nico.client.wiki.service.HypixelWikiService.findPage(stack);
    }

    public static CompletableFuture<WikiPage> reloadPage(ItemStack stack) {
        return com.nico.client.wiki.service.HypixelWikiService.reloadPage(stack);
    }

    public static CompletableFuture<WikiPage> findPage(String name) {
        return com.nico.client.wiki.service.HypixelWikiService.findPage(name);
    }

    public static CompletableFuture<WikiPage> reloadPage(String name) {
        return com.nico.client.wiki.service.HypixelWikiService.reloadPage(name);
    }

    public static CompletableFuture<WikiPage> findPageQuery(String query) {
        return com.nico.client.wiki.service.HypixelWikiService.findPageQuery(query);
    }

    public static CompletableFuture<WikiPage> reloadPageQuery(String query) {
        return com.nico.client.wiki.service.HypixelWikiService.reloadPageQuery(query);
    }

    public static CompletableFuture<List<WikiTitleResolver.SearchResult>> search(String query, int limit) {
        return com.nico.client.wiki.service.HypixelWikiService.search(query, limit);
    }

    public static CompletableFuture<WikiPage> findPage(URI uri) {
        return com.nico.client.wiki.service.HypixelWikiService.findPage(uri);
    }

    public static CompletableFuture<WikiPage> reloadPage(URI uri) {
        return com.nico.client.wiki.service.HypixelWikiService.reloadPage(uri);
    }

    public static boolean isWikiArticleUri(URI uri) {
        return com.nico.client.wiki.service.HypixelWikiService.isWikiArticleUri(uri);
    }
}