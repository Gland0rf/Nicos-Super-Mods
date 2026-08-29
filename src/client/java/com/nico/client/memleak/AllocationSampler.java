package com.nico.client.memleak;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class AllocationSampler implements AutoCloseable {
    private static final Set<String> NON_CANDIDATE_MODS = Set.of(
            "minecraft", "java", "fabricloader", "fabric-api"
    );

    private final ModClassIndex classIndex;
    private final int maximumStackFrames;
    private final Map<String, LongAdder> sampledBytes = new ConcurrentHashMap<>();
    private volatile RecordingStream stream;
    private volatile boolean running;
    private volatile String failureReason;

    public AllocationSampler(ModClassIndex classIndex, int maximumStackFrames) {
        this.classIndex = classIndex;
        this.maximumStackFrames = maximumStackFrames;
    }

    public void start() {
        try {
            RecordingStream newStream = new RecordingStream();
            newStream.enable("jdk.ObjectAllocationSample").withStackTrace();
            newStream.onEvent("jdk.ObjectAllocationSample", this::handleAllocation);
            newStream.onError(error -> {
                failureReason = error.getClass().getSimpleName() + ": " + error.getMessage();
                running = false;
            });
            newStream.onClose(() -> running = false);
            stream = newStream;
            running = true;
            newStream.startAsync();
        } catch (Throwable error) {
            failureReason = error.getClass().getSimpleName() + ": " + error.getMessage();
            running = false;
        }
    }

    private void handleAllocation(RecordedEvent event) {
        if (event.getStackTrace() == null) {
            return;
        }

        long weight = event.hasField("weight") ? event.getLong("weight") : 1L;
        long inspected = 0;
        for (RecordedFrame frame : event.getStackTrace().getFrames()) {
            if (inspected++ >= maximumStackFrames) break;
            String className = frame.getMethod().getType().getName();
            if (className.startsWith("com.nico.client.memleak.")) continue;
            var owner = classIndex.ownerOf(className);
            if (owner.isPresent() && !isInfrastructureMod(owner.get().id())) {
                sampledBytes.computeIfAbsent(owner.get().id(), ignored -> new LongAdder()).add(weight);
                return;
            }
        }
    }

    private static boolean isInfrastructureMod(String modId) {
        return modId.equals("minecraft")
                || modId.equals("java")
                || modId.equals("fabricloader")
                || modId.equals("fabric-api")
                || modId.startsWith("fabric-")
                || modId.startsWith("fabric_");
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new HashMap<>();
        sampledBytes.forEach((id, counter) -> snapshot.put(id, counter.sum()));
        return Map.copyOf(snapshot);
    }

    public void reset() {
        sampledBytes.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public String failureReason() {
        return failureReason;
    }

    @Override
    public void close() {
        RecordingStream current = stream;
        if (current != null) {
            current.close();
        }
        running = false;
    }
}
