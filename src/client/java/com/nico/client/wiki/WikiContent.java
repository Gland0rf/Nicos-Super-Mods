package com.nico.client.wiki;

import java.util.List;

public record WikiContent(
        WikiText text,
        List<WikiImage> images,
        List<WikiItemSlot> itemSlots,
        List<WikiCraftingGrid> craftingGrids
) {
    public WikiContent {
        text = text == null ? WikiText.empty() : text;
        images = images == null ? List.of() : List.copyOf(images);
        itemSlots = itemSlots == null ? List.of() : List.copyOf(itemSlots);
        craftingGrids = craftingGrids == null ? List.of() : List.copyOf(craftingGrids);
    }

    public static WikiContent empty() {
        return new WikiContent(WikiText.empty(), List.of(), List.of(), List.of());
    }

    public static WikiContent text(WikiText text) {
        return new WikiContent(text, List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return text.isBlank() && images.isEmpty() && itemSlots.isEmpty() && craftingGrids.isEmpty();
    }

    public String plainText() {
        return text.plainText();
    }
}