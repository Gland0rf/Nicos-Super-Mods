package com.nico.client.wiki;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Metadata needed to credit a wiki image and expose its source and license information in the UI.
 * @param fileTitle
 * @param filePageUrl
 * @param originalFileUrl
 * @param licenseShortName
 * @param licenseUrl
 * @param artist
 * @param credit
 * @param usageTerms
 * @param attribution
 * @param source
 * @param metadataAvailable
 */
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
        return metadataAvailable ? "Not specified in metadata" : "See the Wiki file page";
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

    /**
     * Returns whether the resolved metadata gives us an explicitly reusable license for in-game display.
     *
     * <p>This intentionally uses an allowlist rather than trying to enumerate every restricted license.
     * Unknown licenses, permission-only notices, and proprietary game-asset licenses remain hidden until
     * their terms have been verified for this use. The Creative Commons non-commercial variants are allowed
     */
    public boolean permitsInGameDisplay() {
        String evidence = (licenseShortName + " " + usageTerms).toLowerCase(Locale.ROOT);
        if (containsBlockingMarker(evidence)) {
            return false;
        }

        String license = licenseShortName.isBlank() ? usageTerms : licenseShortName;
        if (license.isBlank()) {
            return false;
        }

        boolean foundReusableLicense = false;
        for (String part : license.split("\\s+\\+\\s+")) {
            String normalized = normalizeLicense(part);
            if (normalized.isBlank() || isNeutralLicenseNote(normalized)) {
                continue;
            }
            if (!isExplicitlyReusableLicense(normalized)) {
                return false;
            }
            foundReusableLicense = true;
        }
        return foundReusableLicense;
    }

    private static boolean containsBlockingMarker(String value) {
        return value.contains("fair use")
            || value.contains("fair-use")
            || value.contains("fairuse")
            || value.contains("non-free")
            || value.contains("non free")
            || value.contains("all rights reserved")
            || value.contains("mojang")
            || value.contains("hypixel copyright")
            || value.contains("skyblockresourcepack")
            || value.contains("skyblock resource pack");
    }

    private static boolean isNeutralLicenseNote(String value) {
        return value.equals("from wikimedia (see file page)")
            || value.equals("used with permission");
    }

    private static boolean isExplicitlyReusableLicense(String value) {
        if (value.equals("site license")) {
            // Current wiki revisions are CC BY-NC-SA 3.0; older revisions are CC BY-SA 3.0.
            return true;
        }
        if (value.equals("public domain") || value.startsWith("public domain ")) {
            return true;
        }
        if (value.startsWith("cc0") || value.contains("creative commons zero")) {
            return true;
        }
        if (value.startsWith("cc by") || value.startsWith("cc-by")) {
            return !hasNoDerivativesRestriction(value);
        }
        if (value.startsWith("creative commons attribution")) {
            return !hasNoDerivativesRestriction(value);
        }
        return false;
    }

    private static boolean hasNoDerivativesRestriction(String value) {
        return value.contains("-nd")
            || value.contains(" nd")
            || value.contains("no derivative")
            || value.contains("no-derivative")
            || value.contains("noderivative");
    }

    private static String normalizeLicense(String value) {
        return clean(value)
            .toLowerCase(Locale.ROOT)
            .replace('\u2013', '-')
            .replace('\u2014', '-');
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
