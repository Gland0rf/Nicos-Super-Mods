package com.nico.client.modcheck.registry;

import java.time.Instant;

public record RegistryFetchResult(
    TrustRegistry registry,
    String sourceUrl,
    Instant fetchedAt,
    boolean signatureVerified
) {

}