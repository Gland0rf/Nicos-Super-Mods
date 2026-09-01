package com.nico.client.memleak;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class LifecycleLeakDetector {
    public record Suspect (
            String kind,
            String description,
            Instant releasedAt,
            int completedMajorCollections,
            Duration retainedFor
    ) {

    }

    private static final int MAXIMUM_RETIRED_OBJECTS = 48;
    private static final int REQUIRED_MAJOR_COLLECTIONS = 3;
    private static final Duration REQUIRED_AGE = Duration.ofMinutes(2);

    private static final class RetiredObject {
        private final String kind;
        private final String description;
        private final Instant releasedAt;
        private final WeakReference<Object> reference;
        private int completedMajorCollections;

        private RetiredObject(String kind, String description, Object value, Instant releasedAt) {
            this.kind = kind;
            this.description = description;
            this.releasedAt = releasedAt;
            this.reference = new WeakReference<>(value);
        }
    }

    private final Map<String, WeakReference<Object>> currentObjects = new HashMap<>();
    private final Map<String, String> currentDescriptions = new HashMap<>();
    private final Deque<RetiredObject> retiredObjects = new ArrayDeque<>();

    public synchronized boolean observe(String kind, Object value, String description, Instant now) {
        WeakReference<Object> currentReference = currentObjects.get(kind);
        Object previous = currentReference ==  null ? null : currentReference.get();

        if (previous == value) return false;

        if (previous != null) {
            String previousDescription = currentDescriptions.getOrDefault(kind, kind);
            retiredObjects.addLast(new RetiredObject(kind, previousDescription, previous, now));
            while (retiredObjects.size() > MAXIMUM_RETIRED_OBJECTS) {
                retiredObjects.removeFirst();
            }
        }

        if (value == null) {
            currentObjects.remove(kind);
            currentDescriptions.remove(kind);
        } else {
            currentObjects.put(kind, new WeakReference<>(value));
            currentDescriptions.put(kind, description);
        }
        return true;
    }

    public synchronized void onMajorCollection() {
        retiredObjects.removeIf(retired -> retired.reference.get() == null);
        for (RetiredObject retired : retiredObjects) {
            retired.completedMajorCollections++;
        }
    }

    public synchronized List<Suspect> suspects(Instant now) {
        retiredObjects.removeIf(retired -> retired.reference.get() == null);
        List<Suspect> result = new ArrayList<>();

        for (RetiredObject retired : retiredObjects) {
            Duration age = Duration.between(retired.releasedAt, now);
            if (retired.completedMajorCollections >= REQUIRED_MAJOR_COLLECTIONS
                    && age.compareTo(REQUIRED_AGE) >= 0) {
                result.add(new Suspect(
                        retired.kind,
                        retired.description,
                        retired.releasedAt,
                        retired.completedMajorCollections,
                        age
                ));
            }
        }

        return List.copyOf(result);
    }

    public synchronized void reset() {
        retiredObjects.clear();
    }
}
