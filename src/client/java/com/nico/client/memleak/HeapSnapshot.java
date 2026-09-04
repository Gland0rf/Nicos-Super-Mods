package com.nico.client.memleak;

import java.time.Instant;
import java.util.Map;

public record HeapSnapshot(
        Instant time,
        long postGcHeapBytes,
        Map<String, Long> cumulativeSampledAllocationBytes,
        String collector,
        String cause
) {
    public HeapSnapshot {
        cumulativeSampledAllocationBytes = Map.copyOf(cumulativeSampledAllocationBytes);
    }
}
