package com.nico.client.memleak;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ActivityTimeline {
    public record Event(Instant time, String type, String description) { }

    private static final int MAXIMUM_EVENTS = 160;
    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(2);

    private final Deque<Event> events = new ArrayDeque<>();

    public synchronized void mark(String type, String description) {
        Instant now = Instant.now();
        Event latest = events.peekLast();
        if (latest != null
                && latest.type().equals(type)
                && latest.description().equals(description)
                && Duration.between(latest.time(), now).compareTo(DUPLICATE_WINDOW) < 0) {
            return;
        }

        events.addLast(new Event(now, type, description));
        while (events.size() > MAXIMUM_EVENTS) {
            events.removeFirst();
        }
    }

    public synchronized List<Event> snapshot() {
        return List.copyOf(events);
    }

    public synchronized List<Event> between(Instant start, Instant end) {
        List<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (!event.time().isBefore(start) && !event.time().isAfter(end)) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    public synchronized Event latestSignificantEvent() {
        return events.peekLast();
    }

    public synchronized void reset() {
        events.clear();
    }
}
