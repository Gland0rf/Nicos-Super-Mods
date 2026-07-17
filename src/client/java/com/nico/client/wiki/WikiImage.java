package com.nico.client.wiki;

import java.util.Objects;

public record WikiImage(String url, String altText, String title, int declaredWidth, int declaredHeight) {
    public WikiImage {
        url = Objects.requireNonNullElse(url, "").trim();
        altText = Objects.requireNonNullElse(altText, "").trim();
        title = Objects.requireNonNullElse(title, "").trim();
        declaredWidth = Math.max(0, declaredWidth);
        declaredHeight = Math.max(0, declaredHeight);
    }

    public static WikiImage empty() {
        return new WikiImage("", "", "", 0, 0);
    }

    public boolean isEmpty() {
        return url.isBlank();
    }

    public String displayName() {
        if (!altText.isBlank()) {
            return altText;
        }
        return title;
    }
}