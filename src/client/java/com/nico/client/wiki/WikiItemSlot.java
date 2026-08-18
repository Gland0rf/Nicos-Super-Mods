package com.nico.client.wiki;

import java.util.List;
import java.util.Objects;

/**
 * One inventory-slot widget from the wiki.
 *
 * <p>Slots may contain multiple synchronized frames when the source widget cycles through recipe alternatives.</p>
 * @param frames
 * @param activeFrameIndex
 * @param large
 */
public record WikiItemSlot(List<Frame> frames, int activeFrameIndex, boolean large) {
    public WikiItemSlot {
        frames = frames == null ? List.of() : List.copyOf(frames);
        activeFrameIndex = frames.isEmpty() ? 0 : Math.max(0, Math.min(activeFrameIndex, frames.size() - 1));
    }

    public static WikiItemSlot empty() {
        return new WikiItemSlot(List.of(), 0, false);
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public Frame activeFrame() {
        return frames.isEmpty() ? Frame.empty() : frames.get(activeFrameIndex);
    }

    public record Frame(
            WikiImage image,
            String itemName,
            String link,
            String tooltipTitle,
            String tooltipText,
            String stackSize,
            String imageId,
            String uiGroupKey,
            String uiTarget
    ) {
        public Frame {
            image = image == null ? WikiImage.empty() : image;
            itemName = Objects.requireNonNullElse(itemName, "").trim();
            link = Objects.requireNonNullElse(link, "").trim();
            tooltipTitle = Objects.requireNonNullElse(tooltipTitle, "").trim();
            tooltipText = Objects.requireNonNullElse(tooltipText, "").trim();
            stackSize = Objects.requireNonNullElse(stackSize, "").trim();
            imageId = Objects.requireNonNullElse(imageId, "").trim();
            uiGroupKey = Objects.requireNonNullElse(uiGroupKey, "").trim();
            uiTarget = Objects.requireNonNullElse(uiTarget, "").trim();
        }

        public static Frame empty() {
            return new Frame(WikiImage.empty(), "", "", "", "", "", "", "", "");
        }

        public String displayName() {
            if (!itemName.isBlank()) {
                return itemName;
            }
            if (!tooltipTitle.isBlank()) {
                return tooltipTitle;
            }
            return image.displayName();
        }
    }
}