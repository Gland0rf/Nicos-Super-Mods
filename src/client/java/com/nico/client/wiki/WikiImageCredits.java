package com.nico.client.wiki;

import java.net.URI;
import java.util.Objects;

public record WikiImageCredits(
        String fileTitle,
        String filePageUrl,
        String originalFileUrl,
        String licenseShortName,
        String licenseUrl,
        String artist,
        String credit,
        String usageTerms,
        String attribution,
        String source,
        boolean metadataAvailable
) {
    public WikiImageCredits {
        fileTitle = clean(fileTitle);
        filePageUrl = clean(filePageUrl);
        originalFileUrl = clean(originalFileUrl);
        licenseShortName = clean(licenseShortName);
        licenseUrl = clean(licenseUrl);
        artist = clean(artist);
        credit = clean(credit);
        usageTerms = clean(usageTerms);
        attribution = clean(attribution);
        source = clean(source);
    }

    public static WikiImageCredits empty() {
        return new WikiImageCredits("", "", "", "", "", "", "", "", "", "", false);
    }

    public boolean hasFilePage() {
        return validHttpUri(filePageUrl);
    }

    public boolean hasOriginalFile() {
        return validHttpUri(originalFileUrl);
    }

    public String displayTitle(String fallback) {
        if (!fileTitle.isBlank()) {
            return fileTitle.replaceFirst("(?i)^File:", "").replace('_', ' ');
        }
        String cleanFallback = clean(fallback);
        return cleanFallback.isBlank() ? "Wiki image" : cleanFallback;
    }

    public String licenseLabel() {
        if (!licenseShortName.isBlank()) {
            return licenseShortName;
        }
        if (!usageTerms.isBlank()) {
            return usageTerms;
        }
        // MediaWiki extmetadata does not expose every custom file-license
        // template used by the SkyBlock Wiki. Avoid implying that a file has
        // no terms; the file-description page remains the authoritative place
        // to check the creator/source/license template.
        return "See the Wiki file page for terms";
    }

    public String creatorLabel() {
        if (!artist.isBlank() && !credit.isBlank() && !artist.equalsIgnoreCase(credit)) {
            return artist + " / " + credit;
        }
        if (!artist.isBlank()) {
            return artist;
        }
        if (!credit.isBlank()) {
            return credit;
        }
        return "";
    }

    public URI filePageUri() {
        return uri(filePageUrl);
    }

    public URI originalFileUri() {
        return uri(originalFileUrl);
    }

    public String attributionText(String fallbackTitle) {
        StringBuilder result = new StringBuilder(displayTitle(fallbackTitle));
        String creator = creatorLabel();
        if (!creator.isBlank()) {
            result.append(" - ").append(creator);
        }
        result.append(". License: ").append(licenseLabel()).append('.');
        if (hasFilePage()) {
            result.append(" Source and full terms: ").append(filePageUrl).append('.');
        } else if (hasOriginalFile()) {
            result.append(" Source: ").append(originalFileUrl).append('.');
        }
        result.append(" Rendered/resized for in-game display.");
        return result.toString();
    }

    private static String clean(String value) {
        return Objects.requireNonNullElse(value, "")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean validHttpUri(String value) {
        URI uri = uri(value);
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        return uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https");
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
