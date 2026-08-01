package com.nico.client.watchConnection;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class HeartRateState {
    private final AtomicInteger bpm = new AtomicInteger(-1);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong lastUpdate = new AtomicLong(0L);
    private final AtomicReference<String> status =
            new AtomicReference<>("Starting...");

    public int getBpm() {
        return bpm.get();
    }

    public void setBpm(int value) {
        bpm.set(value);
        lastUpdate.set(System.currentTimeMillis());
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setConnected(boolean value) {
        connected.set(value);

        if (!value) {
            bpm.set(-1);
            lastUpdate.set(0L);
        }
    }

    public long getLastUpdate() {
        return lastUpdate.get();
    }

    public String getStatus() {
        return status.get();
    }

    public void setStatus(String value) {
        status.set(value == null || value.isBlank() ? "Connecting..." : value);
    }
}
