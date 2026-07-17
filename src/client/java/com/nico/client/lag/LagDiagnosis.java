package com.nico.client.lag;

public enum LagDiagnosis {
    WARMING_UP("Warming up", 0xFFAAAAAA),
    GOOD("Stable", 0xFF55FF55),
    SERVER_LAG("Server lag", 0xFFFFAA00),
    NETWORK_LAG("Network lag", 0xFFFFAA00),
    CLIENT_LAG("Client lag", 0xFFFFFF55),
    MIXED("Mixed lag", 0xFFFF5555),
    STALLED("Connection stalled", 0xFFFF5555),
    UNKNOWN("Unknown", 0xFFAAAAAA);

    private final String label;
    private final int color;

    LagDiagnosis(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }
}
