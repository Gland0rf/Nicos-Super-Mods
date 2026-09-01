package com.nico.client.memleak;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.ListenerNotFoundException;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GcMonitor implements AutoCloseable {
    public record Observation(Instant time, long heapUsedBytes, String collector, String cause) {
    }

    private record Registration(NotificationEmitter emitter, NotificationListener listener) {
    }

    private final Consumer<Observation> sink;
    private final List<Registration> registrations = new ArrayList<>();
    private final Set<String> heapPoolNames = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .map(pool -> pool.getName())
            .collect(Collectors.toUnmodifiableSet());

    public GcMonitor(Consumer<Observation> sink) {
        this.sink = sink;
    }

    public synchronized void start() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(bean instanceof NotificationEmitter emitter)) {
                continue;
            }
            NotificationListener listener = (notification, handback) -> {
                if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                if (!(notification.getUserData() instanceof CompositeData data)) {
                    return;
                }
                GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(data);
                String action = info.getGcAction().toLowerCase(Locale.ROOT);
                String collectorName = info.getGcName().toLowerCase(Locale.ROOT);
                if (action.contains("minor")
                        || action.contains("young")
                        || action.contains("pause")
                        || collectorName.contains("minor")
                        || collectorName.contains("young")) {
                    return;
                }
                long afterGc = info.getGcInfo().getMemoryUsageAfterGc().entrySet().stream()
                        .filter(entry -> heapPoolNames.contains(entry.getKey()))
                        .mapToLong(entry -> entry.getValue().getUsed())
                        .sum();
                if (afterGc <= 0) {
                    return;
                }
                sink.accept(new Observation(Instant.now(), afterGc, info.getGcName(), info.getGcCause()));
            };
            try {
                emitter.addNotificationListener(listener, null, null);
                registrations.add(new Registration(emitter, listener));
            } catch (RuntimeException ignored) {
                // Some JVMs expose a collector bean without notification support.
                // Insert napoleon GIF here
            }
        }
    }

    @Override
    public synchronized void close() {
        for (Registration registration : registrations) {
            try {
                registration.emitter.removeNotificationListener(registration.listener);
            } catch (ListenerNotFoundException ignored) {
                // Already removed by the JVM during shutdown.
            }
        }
        registrations.clear();
    }
}
