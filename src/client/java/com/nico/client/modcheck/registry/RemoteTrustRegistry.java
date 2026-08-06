package com.nico.client.modcheck.registry;

import com.nico.client.modcheck.config.ModCheckConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public final class RemoteTrustRegistry {
    private final HttpClient httpClient;

    public RemoteTrustRegistry() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ModCheckConfig.CONNECT_TIMEOUT_MILLIS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public RegistryFetchResult fetch() throws Exception {
        byte[] registryBytes = get(ModCheckConfig.REGISTRY_URL, ModCheckConfig.MAX_REGISTRY_BYTES);
        ensurePublicKeyConfigured();
        byte[] signatureResponse = get(ModCheckConfig.SIGNATURE_URL, 16 * 1024);
        String signatureText = new String(signatureResponse, StandardCharsets.US_ASCII).trim();
        byte[] signatureBytes = Base64.getDecoder().decode(signatureText);
        boolean signatureVerified = verifySignature(registryBytes, signatureBytes);
        if (!signatureVerified) {
            throw new SecurityException("Registry signature verification failed");
        }

        TrustRegistry registry = TrustRegistry.parse(registryBytes);
        return new RegistryFetchResult(
                registry,
                ModCheckConfig.REGISTRY_URL,
                Instant.now(),
                signatureVerified
        );
    }

    private byte[] get(String url, int maximumBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(ModCheckConfig.REQUEST_TIMEOUT_MILLIS))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", createUserAgent())
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("Registry request returned HTTP " + response.statusCode() + " for " + url);
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IllegalStateException("Registry response exceeded size limit");
            }
            return bytes;
        }
    }

    private static String createUserAgent() {
        ModContainer container = FabricLoader.getInstance()
                .getModContainer("nsm")
                .orElseThrow(() -> new IllegalStateException("Could not find NSM metadata"));

        ModMetadata metadata = container.getMetadata();

        String name = metadata.getName();
        String version = metadata.getVersion().getFriendlyString();

        return name + "/" + version + " (+https://github.com/Gland0rf/Nicos-Super-Mods)";
    }

    private static boolean verifySignature(byte[] registryBytes, byte[] signatureBytes) throws Exception {
        byte[] publicKeyBytes = Base64.getDecoder().decode(ModCheckConfig.REGISTRY_PUBLIC_KEY_BASE64);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(registryBytes);
        return verifier.verify(signatureBytes);
    }

    private static void ensurePublicKeyConfigured() {
        String key = ModCheckConfig.REGISTRY_PUBLIC_KEY_BASE64;
        if (key.isBlank() || key.startsWith("REPLACE_")) {
            throw new IllegalStateException(
                    "Registry public key is not configured. Generate a key with tools/RegistrySigner.java."
            );
        }
    }
}
