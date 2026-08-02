package com.nico.client.wiki;

import java.net.URI;

public class WikiAttribution {
    public static final URI WIKI_HOME = URI.create("https://hypixelskyblock.minecraft.wiki");
    public static final URI WIKI_LICENSE_PAGE = URI.create(
            "https://hypixelskyblock.minecraft.wiki/w/Hypixel_Skyblock_Wiki:License"
    );
    public static final URI CC_BY_NC_SA_3 = URI.create(
            "https://creativecommons.org/licenses/by-nc-sa/3.0/"
    );

    public static final String CONTENT_CREDIT =
            "Wiki content: Hypixel Skyblock Wiki (CC BY-NC-SA 3.0)";
    public static final String IMAGE_NOTICE =
            "Images may have file-specific creators, owners, or license terms";
    public static final String TRANSFORMATION_NOTICE =
            "Content and images may be reformatted or resized for in-game display.";

    private WikiAttribution() { }
}
