package com.nico.client.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record WikiText(List<Span> spans) {
    public WikiText {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    public static WikiText empty() {
        return new WikiText(List.of());
    }

    public static WikiText plain(String text) {
        if (text == null || text.isBlank()) {
            return empty();
        }
        return new WikiText(List.of(new Span(text, "", false, false, "")));
    }

    public boolean isBlank() {
        return spans.stream().allMatch(span -> span.text().isBlank());
    }

    public String plainText() {
        StringBuilder result = new StringBuilder();
        for (Span span : spans) {
            result.append(span.text());
        }
        return result.toString().trim();
    }

    public WikiText append(WikiText other) {
        if (other == null || other.isBlank()) {
            return this;
        }
        if (isBlank()) {
            return other;
        }
        List<Span> result = new ArrayList<>(spans.size() + other.spans().size());
        result.addAll(spans);
        result.addAll(other.spans());
        return new WikiText(mergeAdjacent(result));
    }

    public WikiText appendPlain(String text) {
        return append(plain(text));
    }

    private static List<Span> mergeAdjacent(List<Span> source) {
        List<Span> result = new ArrayList<>();
        for (Span span : source) {
            if (span == null || span.text().isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                Span previous = result.get(result.size() - 1);
                if (previous.sameFormatting(span)) {
                    result.set(result.size() - 1, new Span(
                            previous.text() + span.text(),
                            previous.href(),
                            previous.bold(),
                            previous.italic(),
                            previous.cssClasses()
                    ));
                    continue;
                }
            }
            result.add(span);
        }
        return List.copyOf(result);
    }

    public record Span(String text, String href, boolean bold, boolean italic, String cssClasses) {
        public Span {
            text = Objects.requireNonNullElse(text, "");
            href = Objects.requireNonNullElse(href, "").trim();
            cssClasses = Objects.requireNonNullElse(cssClasses, "").trim();
        }

        public boolean isLink() {
            return !href.isBlank();
        }

        private boolean sameFormatting(Span other) {
            return other != null
                    && href.equals(other.href)
                    && bold == other.bold
                    && italic == other.italic
                    && cssClasses.equals(other.cssClasses);
        }
    }
}