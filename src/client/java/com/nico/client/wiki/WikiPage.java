package com.nico.client.wiki;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record WikiPage(
        String title,
        String sourceName,
        URI pageUri,
        String revisionId,
        WikiInfobox infobox,
        List<WikiBlock> blocks
) {
    public WikiPage {
        title = Objects.requireNonNullElse(title, "").trim();
        sourceName = Objects.requireNonNullElse(sourceName, "Hypixel SkyBlock Wiki").trim();
        revisionId = Objects.requireNonNullElse(revisionId, "").trim();
        infobox = infobox == null ? WikiInfobox.empty() : infobox;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}