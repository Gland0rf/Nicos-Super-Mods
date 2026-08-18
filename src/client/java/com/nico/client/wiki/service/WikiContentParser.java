package com.nico.client.wiki.service;

import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiHtmlContract;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiText;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses crafting grids, inventory slots, images, and rich cell/paragraph content. */
abstract class WikiContentParser extends WikiServiceSupport {
    protected static WikiCraftingGrid tryParseCraftingGrid(Element root) {
        List<WikiCraftingGrid> grids = tryParseCraftingGrids(root);
        return grids.isEmpty() ? null : grids.get(0);
    }

    /**
     * Parses every synchronized frame of one Wiki crafting widget.
     *
     * The Wiki's crafting database uses // to define alternative recipes.
     * Those alternatives are emitted as coordinated animated frames inside
     * each inventory slot. Empty frames are significant: dropping them turns
     * the alternatives into the union of every recipe, which is what caused
     * recipes to overlap.
     */
    protected static List<WikiCraftingGrid> tryParseCraftingGrids(Element root) {
        Element input = ownedDescendantWithClass(
                root,
                WikiHtmlContract.CRAFTING_INPUT,
                WikiHtmlContract.CRAFTING_TABLE
        );
        Element output = ownedDescendantWithClass(
                root,
                WikiHtmlContract.CRAFTING_OUTPUT,
                WikiHtmlContract.CRAFTING_TABLE
        );
        if (input == null || output == null) {
            return List.of();
        }

        List<WikiItemSlot> inputs = new ArrayList<>();
        List<Element> rows = directChildrenWithClass(input, WikiHtmlContract.CRAFTING_ROW);
        for (Element row : rows) {
            int inRow = 0;
            for (Element child : row.children()) {
                if (!child.hasClass(WikiHtmlContract.INVENTORY_SLOT)) {
                    continue;
                }
                inputs.add(parseItemSlot(child));
                inRow++;
                if (inRow >= 3 || inputs.size() >= 9) {
                    break;
                }
            }
        }

        if (inputs.isEmpty()) {
            for (Element slot : input.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT)) {
                if (nearestAncestorWithClass(slot, WikiHtmlContract.CRAFTING_TABLE) != root) {
                    continue;
                }
                inputs.add(parseItemSlot(slot));
                if (inputs.size() >= 9) {
                    break;
                }
            }
        }

        Element outputSlotElement = null;
        for (Element slot : output.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT)) {
            if (nearestAncestorWithClass(slot, WikiHtmlContract.CRAFTING_TABLE) == root) {
                outputSlotElement = slot;
                break;
            }
        }
        if (outputSlotElement == null) {
            return List.of();
        }

        WikiCraftingGrid rawGrid = new WikiCraftingGrid(
                inputs,
                parseItemSlot(outputSlotElement),
                root.hasClass(WikiHtmlContract.CRAFTING_SHAPELESS)
                        || !root.getElementsByClass(WikiHtmlContract.CRAFTING_SHAPELESS).isEmpty(),
                root.hasClass(WikiHtmlContract.CRAFTING_FIXED)
                        || !root.getElementsByClass(WikiHtmlContract.CRAFTING_FIXED).isEmpty()
        );
        if (rawGrid.isEmpty()) {
            return List.of();
        }

        int frameCount = craftingFrameCount(rawGrid);
        if (frameCount <= 1) {
            return List.of(rawGrid);
        }

        List<WikiCraftingGrid> variants = new ArrayList<>(frameCount);
        Set<String> seen = new LinkedHashSet<>();
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            List<WikiItemSlot> variantInputs = new ArrayList<>(WikiCraftingGrid.INPUT_SLOT_COUNT);
            for (WikiItemSlot slot : rawGrid.inputs()) {
                variantInputs.add(selectCraftingFrame(slot, frameIndex));
            }

            WikiCraftingGrid variant = new WikiCraftingGrid(
                    variantInputs,
                    selectCraftingFrame(rawGrid.output(), frameIndex),
                    rawGrid.shapeless(),
                    rawGrid.fixed()
            );
            if (variant.isEmpty()) {
                continue;
            }

            String key = craftingKey(variant);
            if (seen.add(key)) {
                variants.add(variant);
            }
        }

        return variants.isEmpty() ? List.of(rawGrid) : List.copyOf(variants);
    }

    private static int craftingFrameCount(WikiCraftingGrid grid) {
        int count = grid.output().frames().size();
        for (WikiItemSlot slot : grid.inputs()) {
            count = Math.max(count, slot.frames().size());
        }
        return Math.max(1, count);
    }

    private static WikiItemSlot selectCraftingFrame(WikiItemSlot slot, int frameIndex) {
        if (slot == null || slot.frames().isEmpty()) {
            return WikiItemSlot.empty();
        }

        List<WikiItemSlot.Frame> frames = slot.frames();
        WikiItemSlot.Frame frame;
        if (frames.size() == 1) {
            // A non-animated frame is shared by every recipe variant.
            frame = frames.get(0);
        } else {
            int index = Math.floorMod(slot.activeFrameIndex() + frameIndex, frames.size());
            frame = frames.get(index);
        }

        if (isEmptyFrame(frame)) {
            return WikiItemSlot.empty();
        }
        return new WikiItemSlot(List.of(frame), 0, slot.large());
    }

    private static boolean isEmptyFrame(WikiItemSlot.Frame frame) {
        return frame == null
                || (frame.image().isEmpty()
                && frame.itemName().isBlank()
                && frame.link().isBlank()
                && frame.tooltipTitle().isBlank()
                && frame.tooltipText().isBlank()
                && frame.stackSize().isBlank()
                && frame.imageId().isBlank())
                && frame.uiTarget().isBlank();
    }

    protected static WikiContent parseContent(Element element) {
        List<WikiCraftingGrid> crafting = new ArrayList<>();
        Set<String> craftingKeys = new LinkedHashSet<>();
        for (Element grid : element.getElementsByClass(WikiHtmlContract.CRAFTING_TABLE)) {
            if (!grid.hasClass(WikiHtmlContract.CRAFTING_ROOT)) {
                continue;
            }

            Element outerCrafting = nearestAncestorWithClass(grid, WikiHtmlContract.CRAFTING_TABLE);
            if (outerCrafting != null && outerCrafting != element) {
                continue;
            }

            for (WikiCraftingGrid parsed : tryParseCraftingGrids(grid)) {
                if (parsed == null || parsed.isEmpty()) {
                    continue;
                }

                String key = craftingKey(parsed);
                if (craftingKeys.add(key)) {
                    crafting.add(parsed);
                }
            }
        }

        List<WikiItemSlot> slots = new ArrayList<>();
        for (Element slotElement : element.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT)) {
            if (nearestAncestorWithClass(slotElement, WikiHtmlContract.INVENTORY_SLOT) != null
                    || nearestAncestorWithClass(slotElement, WikiHtmlContract.CRAFTING_TABLE) != null) {
                continue;
            }
            WikiItemSlot parsedSlot = parseItemSlot(slotElement);
            if (!parsedSlot.isEmpty() && !isInlineInventorySlot(slotElement)) {
                slots.add(parsedSlot);
            }
        }

        List<WikiImage> images = new ArrayList<>();
        Set<String> imageUrls = new LinkedHashSet<>();
        for (Element image : element.select("img")) {
            if (isInsideWidget(image)) {
                continue;
            }

            WikiImage parsed = parseImage(image);
            if (parsed.isEmpty()) {
                continue;
            }

            /*
             * The Wiki uses ordinary linked <img> elements for many inline
             * item/mob icons. Keep those in the rich text itself so they stay
             * beside the words they belong to. Turning them into inventory
             * slots moved them onto a new line and added the gray slot frame.
             */
            if (isInlineWikiIcon(image, parsed)) {
                continue;
            }

            if (imageUrls.add(parsed.url())) {
                images.add(parsed);
            }
        }

        Element textClone = element.clone();
        textClone.getElementsByClass(WikiHtmlContract.CRAFTING_TABLE).remove();

        for (Element slotElement : new ArrayList<>(
            textClone.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT)
        )) {
            if (!isInlineInventorySlot(slotElement)) {
                slotElement.remove();
            }
        }
        textClone.select("figure").remove();
        for (Element image : new ArrayList<>(textClone.select("img"))) {
            WikiImage parsed = parseImage(image);
            if (parsed.isEmpty() || !isInlineWikiIcon(image, parsed)) {
                image.remove();
            }
        }

        WikiText text = parseStyledText(textClone);
        return new WikiContent(text, images, slots, crafting);
    }

    protected static Element nearestAncestorTag(Element element, String tagName) {
        Element current = element;
        while (current != null) {
            if (current.tagName().equalsIgnoreCase(tagName)) {
                return current;
            }
            current = current.parent();
        }
        return null;
    }

    protected static Element nearestAncestorWithAttribute(Element element, String attribute) {
        Element current = element;
        while (current != null) {
            if (current.hasAttr(attribute)) {
                return current;
            }
            current = current.parent();
        }
        return null;
    }

    protected static boolean isInlineWikiIcon(Element image, WikiImage parsed) {
        if (hasAncestorClass(image, WikiHtmlContract.MESSAGEBOX_IMAGE)
                || hasAncestorClass(image, WikiHtmlContract.MBOX_IMAGE)
                || hasAncestorClass(image, WikiHtmlContract.DARK_MESSAGEBOX_IMAGE)
                || hasAncestorClass(image, WikiHtmlContract.INFOBOX_IMAGE_CONTAINER)) {
            return false;
        }
        int width = parsed.declaredWidth();
        int height = parsed.declaredHeight();
        boolean smallDeclaredImage = width > 0 && height > 0 && width <= 96 && height <= 96;
        boolean linked = nearestAncestorTag(image, "a") != null;
        boolean inlineParent = image.parent() != null
                && (image.parent().tagName().equals("a")
                || image.parent().tagName().equals("span")
                || image.parent().tagName().equals("p"));
        return smallDeclaredImage && (linked || inlineParent);
    }

    /**
     * Item-link templates sometimes use a tiny .invslot directly inside a
     * paragraph/list item. Those are inline icons, not standalone inventory
     * widgets. Keep real table/crafting/infobox slot widgets unchanged.
     */
    protected static boolean isInlineInventorySlot(Element slot) {
        if (slot == null
                || nearestAncestorWithClass(slot, WikiHtmlContract.CRAFTING_TABLE) != null
                || nearestAncestorWithClass(slot, WikiHtmlContract.INFOBOX) != null) {
            return false;
        }
        Element current = slot.parent();
        while (current != null) {
            String tag = current.tagName();
            if (tag.equals("p") || tag.equals("li") || tag.equals("dd") || tag.equals("dt")) {
                return true;
            }
            if (tag.equals("td") || tag.equals("th") || tag.equals("table")) {
                return false;
            }
            current = current.parent();
        }
        return false;
    }

    protected static String craftingKey(WikiCraftingGrid grid) {
        StringBuilder key = new StringBuilder();
        for (WikiItemSlot slot : grid.inputs()) {
            WikiItemSlot.Frame frame = slot.activeFrame();
            key.append(frame.displayName()).append('|')
                    .append(frame.stackSize()).append(';');
        }
        WikiItemSlot.Frame output = grid.output().activeFrame();
        key.append("->").append(output.displayName()).append('|').append(output.stackSize());
        return key.toString();
    }

    protected static List<WikiItemSlot> parseSlotsWithin(Element container) {
        List<WikiItemSlot> result = new ArrayList<>();
        for (Element slot : container.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT)) {
            if (nearestAncestorWithClass(slot, WikiHtmlContract.INVENTORY_SLOT) != null
                    || nearestAncestorWithClass(slot, WikiHtmlContract.CRAFTING_TABLE) != null) {
                continue;
            }
            WikiItemSlot parsed = parseItemSlot(slot);
            if (!parsed.isEmpty()) {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    protected static WikiItemSlot parseItemSlot(Element slot) {
        List<WikiItemSlot.Frame> frames = new ArrayList<>();
        Element uiOwner = nearestAncestorWithAttribute(slot, WikiHtmlContract.UI_GROUP_ATTRIBUTE);
        String uiGroupKey = uiOwner == null
                ? ""
                : uiOwner.attr(WikiHtmlContract.UI_GROUP_ATTRIBUTE).trim();
        Elements frameElements = slot.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT_ITEM);
        if (frameElements.isEmpty()) {
            frameElements = new Elements(slot);
        }

        boolean animated = frameElements.size() > 1
                || slot.hasClass("animated")
                || nearestAncestorWithClass(slot, "animated") != null;

        int active = 0;
        for (Element frameElement : frameElements) {
            Element imageElement = frameElement.getElementsByClass(
                    WikiHtmlContract.INVENTORY_SLOT_ITEM_IMAGE
            ).first();

            if (imageElement != null && !imageElement.tagName().equals("img")) {
                imageElement = imageElement.selectFirst("img");
            }
            if (imageElement == null) {
                imageElement = frameElement.selectFirst("img");
            }

            WikiImage image = imageElement == null ? WikiImage.empty() : parseImage(imageElement);
            Element anchor = frameElement.tagName().equals("a")
                    ? frameElement
                    : frameElement.selectFirst("a");

            String mineTipTitle = cleanMineTipText(firstNonBlank(
                    frameElement.attr("data-minetip-title"),
                    slot.attr("data-minetip-title"),
                    frameElement.attr("title")
            ));
            String mineTipText = cleanMineTipText(firstNonBlank(
                    frameElement.attr("data-minetip-text"),
                    slot.attr("data-minetip-text")
            ));
            String imageId = firstNonBlank(
                    frameElement.attr("data-iid"),
                    slot.attr("data-iid"),
                    imageElement == null ? "" : imageElement.attr("data-iid")
            );
            String itemName = firstNonBlank(
                    frameElement.attr("data-item"),
                    frameElement.attr("data-name"),
                    mineTipTitle,
                    anchor == null ? "" : anchor.attr("title"),
                    image.altText()
            );
            if (image.isEmpty() && !itemName.isBlank()) {
                image = deriveDefaultItemImage(itemName);
            }
            Element stackSize = frameElement.getElementsByClass(
                    WikiHtmlContract.INVENTORY_SLOT_STACK_SIZE
            ).first();

            String uiTarget = firstUiTarget(slot, frameElement);
            String link = anchor == null ? "" : absoluteUrl(anchor, "href");

            // A goto-* class is the real action for SkyBlock UI controls. The
            // nested image often has its own MediaWiki File: link; that must
            // never override the UI action.
            if (!uiTarget.isBlank()) {
                link = "";
            } else if (isInventoryImageLink(link)) {
                link = "";
            }

            WikiItemSlot.Frame frame = new WikiItemSlot.Frame(
                    image,
                    itemName,
                    link,
                    mineTipTitle,
                    mineTipText,
                    stackSize == null ? "" : stackSize.text().trim(),
                    imageId,
                    uiGroupKey,
                    uiTarget
            );

            boolean meaningful = !isEmptyFrame(frame);
            if (meaningful || animated) {
                // Empty animated frames are intentional. They represent a slot
                // that is unused in one of the alternative recipes.
                frames.add(meaningful ? frame : WikiItemSlot.Frame.empty());
                if (frameElement.hasClass(WikiHtmlContract.INVENTORY_SLOT_ACTIVE_FRAME)) {
                    active = frames.size() - 1;
                }
            }
        }
        return new WikiItemSlot(frames, active, slot.hasClass(WikiHtmlContract.INVENTORY_SLOT_LARGE));
    }

    protected static String firstUiTarget(Element... elements) {
        if (elements == null) return "";
        for (Element element : elements) {
            if (element == null) continue;

            // Module:Inventory slot normally puts goto-* on the outer invslot,
            // but other UI helpers can put it on a nested wrapper. Search the
            // supplied element and its descendants so both forms work.
            for (Element candidate : element.getAllElements()) {
                for (String className : candidate.classNames()) {
                    if (className.startsWith(WikiHtmlContract.UI_GOTO_PREFIX)
                            && className.length() > WikiHtmlContract.UI_GOTO_PREFIX.length()) {
                        return className.substring(WikiHtmlContract.UI_GOTO_PREFIX.length()).trim();
                    }
                }
            }
        }
        return "";
    }

    protected static boolean isInventoryImageLink(String link) {
        if (link == null || link.isBlank()) return false;
        String lower = link.toLowerCase(Locale.ROOT);
        return lower.contains("/images/")
                || lower.contains("/w/file:")
                || lower.contains("/wiki/file:")
                || lower.contains("special:redirect/file");
    }

    protected static WikiImage deriveDefaultItemImage(String itemName) {
        String cleanName = itemName == null ? "" : itemName.trim();
        if (cleanName.isBlank()) {
            return WikiImage.empty();
        }
        String fileName = cleanName.endsWith(".png") ? cleanName : cleanName + ".png";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return new WikiImage(
                WIKI_BASE + "/w/Special:Redirect/file/" + encoded,
                cleanName,
                cleanName,
                32,
                32
        );
    }

    protected static String cleanMineTipText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        /*
         * Keep the Wiki's Minecraft formatting codes and line breaks. The
         * screen renderer understands both '&' and section-sign codes.
         */
        String normalized = value
                .replace("<br />", "\n")
                .replace("<br/>", "\n")
                .replace("<br>", "\n")
                .replace("\\n", "\n")
                .replace(' ', ' ');

        normalized = org.jsoup.parser.Parser.unescapeEntities(normalized, false);

        /*
         * Module:Inventory_slot uses '/' as a line separator and '//' as a
         * blank line in data-minetip-text. Preserve escaped literal slashes.
         */
        String escapedSlash = "\u0000NSM_WIKI_SLASH\u0000";
        normalized = normalized
                .replace("\\/", escapedSlash)
                .replace("//", "\n\n")
                .replace("/", "\n")
                .replace(escapedSlash, "/");

        return normalized
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }


    protected static WikiImage parseImage(Element image) {
        String url = firstNonBlank(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("src")
        );
        if (url.isBlank()) {
            url = firstSrcSetUrl(firstNonBlank(
                    image.attr("data-srcset"),
                    image.attr("srcset")
            ));
        }
        url = normalizeImageUrl(image, url);

        int width = parsePositiveInt(firstNonBlank(
                image.attr("width"),
                image.attr("data-file-width")
        ), 0);
        int height = parsePositiveInt(firstNonBlank(
                image.attr("height"),
                image.attr("data-file-height")
        ), 0);

        return new WikiImage(url, image.attr("alt"), image.attr("title"), width, height);
    }

    protected static String firstSrcSetUrl(String srcSet) {
        if (srcSet == null || srcSet.isBlank()) {
            return "";
        }
        String first = srcSet.split(",", 2)[0].trim();
        int whitespace = first.indexOf(' ');
        return whitespace < 0 ? first : first.substring(0, whitespace).trim();
    }

}