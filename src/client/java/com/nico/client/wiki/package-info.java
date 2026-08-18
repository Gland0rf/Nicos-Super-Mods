/**
 * Parsed models, browser persistence, image handling, attribution, and compatibility entry points
 * for the in-game Hypixel Skyblock Wiki browser.
 *
 * <p>HTML parsing and network orchestration live in {@code com.nico.client.wiki.service};
 * Minecraft screen behavior lives in {@code com.nico.client.wiki.screen}. Model types in
 * this package deliberately contain no Jsoup nodes, so the renderer stays independent
 * of source markup.</p>
 */

package com.nico.client.wiki;