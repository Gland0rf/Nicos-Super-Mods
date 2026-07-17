package com.nico.client.lag;

public record LagSnapshot (
        boolean active,
        boolean warmingUp,
        double estimatedTps,
        int pingMillis,
        double jitterMillis,
        long packetGapMillis,
        int fps,
        double estimatedServerDelaySeconds,
        double networkStallSeconds,
        LagDiagnosis diagnosis
) {
    public static LagSnapshot inactive() {
        return new LagSnapshot(
                false,
                false,
                Double.NaN,
                -1,
                Double.NaN,
                0L,
                0,
                0.0D,
                0.0D,
                LagDiagnosis.UNKNOWN
        );
    }

    public boolean hasTpsEstimate() {
        return Double.isFinite(estimatedTps);
    }

    public boolean hasPing() {
        return pingMillis >= 0;
    }
}
