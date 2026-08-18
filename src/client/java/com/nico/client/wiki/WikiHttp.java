package com.nico.client.wiki;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Builds HTTP requests with a stable, identifiable user agent for Hypixel Skyblock Wiki traffic. **/
public final class WikiHttp {
    private static final String DEFAULT_MOD_PAGE_URL = "https://modrinth.com/mod/nicos-super-mods";
    private static final String DEFAULT_CONTACT = "ndkogler@icloud.com";

    private static final ModIdentity MOD_IDENTITY = findOwningMod();
    private static final String MOD_NAME = MOD_IDENTITY.name();
    private static final String MOD_VERSION = MOD_IDENTITY.version();

    private static final String MOD_PAGE_URL = property("nsm.wiki.modPage", DEFAULT_MOD_PAGE_URL);
    private static final String CONTACT = property("nsm.wiki.contact", DEFAULT_CONTACT);

    private WikiHttp() { }

    public static String userAgent() {
        StringBuilder result = new StringBuilder()
                .append(MOD_NAME)
                .append('/')
                .append(MOD_VERSION)
                .append(" Hypixel-SkyBlock-Wiki-Reader");

        List<String> details = new ArrayList<>();
        if (!MOD_PAGE_URL.isBlank()) {
            details.add("+" + MOD_PAGE_URL);
        }
        if (!CONTACT.isBlank()) {
            details.add("contact: " + CONTACT);
        }
        if (!details.isEmpty()) {
            result.append(" (").append(String.join("; ", details)).append(')');
        }
        return result.toString();
    }

    public static HttpRequest.Builder request(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout == null ? Duration.ofSeconds(25) : timeout)
                .header("User-Agent", userAgent())
                .header("Accept-Language", "en-US,en;q=0.9");
    }

    public static HttpRequest.Builder request(URI uri) {
        return request(uri, Duration.ofSeconds(25));
    }

    public static String modPageUrl() {
        return MOD_PAGE_URL;
    }

    public static String contact() {
        return CONTACT;
    }

    public static boolean hasModPageUrl() {
        return !MOD_PAGE_URL.isBlank();
    }

    private static ModIdentity findOwningMod() {
        String classFile = WikiHttp.class.getName().replace('.', '/') + ".class";

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            if (container.findPath(classFile).isEmpty()) {
                continue;
            }

            String name = container.getMetadata().getName();
            if (name == null || name.isBlank()) {
                name = container.getMetadata().getId();
            }

            String version = container.getMetadata().getVersion().getFriendlyString();
            if (version == null || version.isBlank()) {
                version = "unknown";
            }

            return new ModIdentity(name, version);
        }

        // This should only happen in unusual test environments where Fabric Loader is unavailable.
        return new ModIdentity("Nico's Super Mods [NSM]", "unknown");
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name, fallback);
        return value == null ? "" : value.trim();
    }

    private record ModIdentity(String name, String version) { }
}