/**
 * Minecraft UI for browsing parsed wiki pages.
 *
 * <p>The public {@link com.nico.client.wiki.screen.WikiScreen} sits on a package-private inheritance
 * stack. Each layer owns one concern-state, navigation, browser actions, rendering, and input - so the
 * screen can share state without exposing those implementation details as public API.</p>
 */

package com.nico.client.wiki.screen;