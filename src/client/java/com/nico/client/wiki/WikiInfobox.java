package com.nico.client.wiki;

import java.util.List;
import java.util.Objects;

public record WikiInfobox(String title, List<Entry> entries) {
    public WikiInfobox {
        title = Objects.requireNonNullElse(title, "").trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static WikiInfobox empty() {
        return new WikiInfobox("", List.of());
    }

    public boolean isEmpty() {
        return title.isBlank() && entries.isEmpty();
    }

    public String findTextValue(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        for (Entry entry : entries) {
            if (entry instanceof Row row && row.label().plainText().equalsIgnoreCase(label)) {
                return row.value().plainText();
            }
        }
        return "";
    }

    public sealed interface Entry permits Image, SlotStrip, PanelTabs, Header, Row { }

    public record Image(WikiImage image, WikiText caption) implements Entry {
        public Image {
            image = image == null ? WikiImage.empty() : image;
            caption = caption == null ? WikiText.empty() : caption;
        }
    }

    public record SlotStrip(List<WikiItemSlot> slots) implements Entry {
        public SlotStrip {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }
    }

    public record PanelTabs(List<String> labels, int activeIndex) implements Entry {
        public PanelTabs {
            labels = labels == null ? List.of() : labels.stream()
                    .map(value -> Objects.requireNonNullElse(value, "").trim())
                    .toList();
            activeIndex = labels.isEmpty() ? 0 : Math.max(0, Math.min(activeIndex, labels.size() - 1));
        }
    }

    public record Header(WikiText text) implements Entry {
        public Header {
            text = text == null ? WikiText.empty() : text;
        }
    }

    public record Row(WikiText label, WikiContent value, int groupColumns) implements Entry {
        public Row {
            label = label == null ? WikiText.empty() : label;
            value = value == null ? WikiContent.empty() : value;
            groupColumns = Math.max(1, groupColumns);
        }
    }
}