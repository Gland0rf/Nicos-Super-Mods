package com.nico.client.wiki;

import java.util.List;
import java.util.Objects;

/**
 * A collapsible nested resource tree used by the Wiki's forging sections.
 * @param id
 * @param roots
 */
public record WikiForgingTree(String id, List<Node> roots) {
    public WikiForgingTree {
        id = Objects.requireNonNullElse(id, "forging-tree").trim();
        roots = roots == null ? List.of() : List.copyOf(roots);
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    public record Node(
            WikiContent content,
            List<Node> children,
            boolean expandedByDefault
    ) {
        public Node {
            content = content == null ? WikiContent.empty() : content;
            children = children == null ? List.of() : List.copyOf(children);
        }

        public boolean expandable() {
            return !children.isEmpty();
        }
    }
}
