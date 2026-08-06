package com.nico.client.modcheck.config;

public final class ModCheckConfig {
    private ModCheckConfig() {
    }

    public static final String REGISTRY_URL = System.getProperty(
            "modguard.registryUrl",
            "https://raw.githubusercontent.com/Gland0rf/Nicos-Super-Mods-Trust-Registry/main/registry.json"
    );

    public static final String SIGNATURE_URL = System.getProperty(
            "modguard.signatureUrl",
            "https://raw.githubusercontent.com/Gland0rf/Nicos-Super-Mods-Trust-Registry/main/registry.sig"
    );

    /** X.509 SubjectPublicKeyInfo-encoded Ed25519 public key, Base64 encoded. */
    public static final String REGISTRY_PUBLIC_KEY_BASE64 = System.getProperty(
            "modguard.registryPublicKey",
            "MCowBQYDK2VwAyEAvydQoezK4L3vVZ0Zh1eLQNtOtT1V8W9gJryngPP+rVM="
    );

    public static final int CONNECT_TIMEOUT_MILLIS = Integer.getInteger(
            "modguard.connectTimeoutMillis",
            2_500
    );

    public static final int REQUEST_TIMEOUT_MILLIS = Integer.getInteger(
            "modguard.requestTimeoutMillis",
            4_000
    );

    public static final int MAX_REGISTRY_BYTES = Integer.getInteger(
            "modguard.maxRegistryBytes",
            4 * 1024 * 1024
    );

    /** Unknown mods show a warning, while known-project hash mismatches are critical. */
    public static final boolean WARN_ON_UNKNOWN_MODS = !Boolean.getBoolean(
            "modguard.ignoreUnknownMods"
    );
}
