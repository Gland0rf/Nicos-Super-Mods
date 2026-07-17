package com.nico.client.wiki;

import java.util.List;
import java.util.Objects;

public sealed interface WikiBlock permits
        WikiBlock.Heading,
        WikiBlock.Paragraph,
        WikiBlock.ListItem,
        WikiBlock.Table,
        WikiBlock.TabGroup,
        WikiBlock.Crafting,
        WikiBlock.Image,
        WikiBlock.HorizontalRule {

    record Heading(int level, WikiText text, String anchor) implements WikiBlock {
        public Heading {
            level = Math.max(2, Math.min(6, level));
            text = text == null ? WikiText.empty() : text;
            anchor = Objects.requireNonNullElse(anchor, "").trim();
        }
    }

    record Paragraph(WikiContent content) implements WikiBlock {
        public Paragraph {
            content = content == null ? WikiContent.empty() : content;
        }
    }

    record ListItem(boolean ordered, int depth, WikiContent content) implements WikiBlock {
        public ListItem {
            depth = Math.max(0, depth);
            content = content == null ? WikiContent.empty() : content;
        }
    }

    record Table(List<Row> rows, boolean sortable, boolean pixelated) implements WikiBlock {
        public Table {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public int columnCount() {
            int maximum = 0;
            for (Row row : rows) {
                int count = 0;
                for (Cell cell : row.cells()) {
                    count += cell.columnSpan();
                }
                maximum = Math.max(maximum, count);
            }
            return maximum;
        }

        public record Row(List<Cell> cells) {
            public Row {
                cells = cells == null ? List.of() : List.copyOf(cells);
            }
        }

        public record Cell(WikiContent content, boolean header, int rowSpan, int columnSpan) {
            public Cell {
                content = content == null ? WikiContent.empty() : content;
                rowSpan = Math.max(1, rowSpan);
                columnSpan = Math.max(1, columnSpan);
            }
        }
    }

    record TabGroup(List<Tab> tabs, int initiallySelectedIndex) implements WikiBlock {
        public TabGroup {
            tabs = tabs == null ? List.of() : List.copyOf(tabs);
            initiallySelectedIndex = tabs.isEmpty()
                    ? 0
                    : Math.max(0, Math.min(initiallySelectedIndex, tabs.size() - 1));
        }

        public record Tab(String title, String panelId, List<WikiBlock> blocks) {
            public Tab {
                title = Objects.requireNonNullElse(title, "").trim();
                panelId = Objects.requireNonNullElse(panelId, "").trim();
                blocks = blocks == null ? List.of() : List.copyOf(blocks);
            }
        }
    }

    record Crafting(WikiCraftingGrid grid) implements WikiBlock {
        public Crafting {
            grid = grid == null ? WikiCraftingGrid.empty() : grid;
        }
    }

    record Image(WikiImage image, WikiText caption) implements WikiBlock {
        public Image {
            image = image == null ? WikiImage.empty() : image;
            caption = caption == null ? WikiText.empty() : caption;
        }
    }

    record HorizontalRule() implements WikiBlock { }
}