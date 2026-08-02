package com.nico.client.wiki.service;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiHtmlContract;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiForgingTree;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts a rendered MediaWiki article into the strict Wiki model. */
abstract class WikiArticleParser extends WikiWidgetParser {
    private static final Pattern FORGING_QUANTITY = Pattern.compile(
            "^\\s*([0-9][0-9,.]*)(?:\\s*\\([^)]*\\))?\\s*"
    );
    private static final Pattern FORGING_TOGGLE_LABEL = Pattern.compile(
            "(?i)\\s*\\[(?:expand|collapse)]\\s*"
    );
    protected static WikiPage parseRenderedArticle(String title, URI pageUri, String revisionId, String html) {
        Document document = Jsoup.parse(html, WIKI_ARTICLE_BASE);
        Element articleRoot = document.getElementsByClass(WikiHtmlContract.ARTICLE_ROOT).first();
        if (articleRoot == null) {
            throw new HypixelWikiService.WikiContractException("Missing required article root class ." + WikiHtmlContract.ARTICLE_ROOT);
        }
        if (DEBUG) {
            logContractElements(articleRoot);
        }

        Element workingRoot = articleRoot.clone();
        removeIgnoredElements(workingRoot);

        WikiInfobox infobox = parseInfobox(workingRoot);
        Element infoboxElement = workingRoot.getElementsByClass(WikiHtmlContract.INFOBOX).first();
        if (infoboxElement != null) {
            infoboxElement.remove();
        }

        List<WikiBlock> blocks = new ArrayList<>();
        appendChildrenAsBlocks(workingRoot, blocks, 0);
        blocks.removeIf(WikiArticleParser::isEmptyBlock);

        if (blocks.isEmpty() && infobox.isEmpty()) {
            throw new HypixelWikiService.WikiRequestException("The Wiki article contained no supported content");
        }
        return new WikiPage(title, "Hypixel SkyBlock Wiki", pageUri, revisionId, infobox, blocks);
    }

    protected static WikiInfobox parseInfobox(Element articleRoot) {
        Element root = articleRoot.getElementsByClass(WikiHtmlContract.INFOBOX).first();
        if (root == null) {
            return WikiInfobox.empty();
        }

        Element titleElement = root.getElementsByClass(WikiHtmlContract.INFOBOX_TITLE).first();
        String title = titleElement == null ? "" : titleElement.text().trim();
        List<WikiInfobox.Entry> entries = new ArrayList<>();
        walkInfobox(root, entries);

        WikiInfobox result = new WikiInfobox(title, entries);
        if (result.isEmpty() && DEBUG) {
            System.err.println("[NSM Wiki] Ignoring an unsupported .infobox structure on this page");
        }
        return result;
    }

    protected static void walkInfobox(Element parent, List<WikiInfobox.Entry> entries) {
        for (Element child : parent.children()) {
            if (child.hasClass(WikiHtmlContract.INFOBOX_TITLE)) {
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_IMAGE_CONTAINER)) {
                parseInfoboxImageContainer(child, entries);
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_SECTION_LABELS)) {
                WikiInfobox.PanelTabs tabs = parseInfoboxPanelTabs(child);
                if (!tabs.labels().isEmpty()) {
                    entries.add(tabs);
                }
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_HEADER)) {
                WikiText text = parseStyledText(child);
                if (!text.isBlank()) {
                    entries.add(new WikiInfobox.Header(text));
                }
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_ROW_CONTAINER)) {
                entries.addAll(parseInfoboxRows(child));
                continue;
            }
            walkInfobox(child, entries);
        }
    }

    protected static void parseInfoboxImageContainer(Element container, List<WikiInfobox.Entry> entries) {
        Element imageElement = container.getElementsByClass(WikiHtmlContract.INFOBOX_IMAGE).first();
        if (imageElement != null) {
            Element imageTag = imageElement.tagName().equals("img") ? imageElement : imageElement.selectFirst("img");
            WikiImage image = imageTag == null ? WikiImage.empty() : parseImage(imageTag);
            Element captionElement = container.getElementsByClass(WikiHtmlContract.INFOBOX_IMAGE_CAPTION).first();
            WikiText caption = captionElement == null ? WikiText.empty() : parseStyledText(captionElement);
            if (!image.isEmpty() || !caption.isBlank()) {
                entries.add(new WikiInfobox.Image(image, caption));
            }
        }
        List<WikiItemSlot> slots = parseSlotsWithin(container);
        if (!slots.isEmpty()) {
            entries.add(new WikiInfobox.SlotStrip(slots));
        }
    }

    protected static WikiInfobox.PanelTabs parseInfoboxPanelTabs(Element container) {
        List<String> labels = new ArrayList<>();
        int active = 0;
        int index = 0;
        for (Element child : container.children()) {
            String label = child.text().trim();
            if (label.isBlank()) {
                continue;
            }
            labels.add(label);
            if (child.hasClass(WikiHtmlContract.INFOBOX_ACTIVE_SECTION)
                    || "true".equalsIgnoreCase(child.attr("aria-selected"))) {
                active = index;
            }
            index++;
        }
        return new WikiInfobox.PanelTabs(labels, active);
    }

    protected static List<WikiInfobox.Row> parseInfoboxRows(Element rowContainer) {
        org.jsoup.select.Elements labels = rowContainer.getElementsByClass(
                WikiHtmlContract.INFOBOX_ROW_LABEL
        );
        org.jsoup.select.Elements values = rowContainer.getElementsByClass(
                WikiHtmlContract.INFOBOX_ROW_VALUE
        );
        int count = Math.min(labels.size(), values.size());
        if (count <= 0) {
            return List.of();
        }

        int columns = parsePositiveInt(rowContainer.attr("data-columns"), 1);
        List<WikiInfobox.Row> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            WikiText label = parseStyledText(labels.get(index));
            WikiContent value = parseContent(values.get(index));
            if (!label.isBlank() || !value.isEmpty()) {
                result.add(new WikiInfobox.Row(label, value, columns));
            }
        }
        return List.copyOf(result);
    }

    protected static List<WikiBlock> parsePanelBlocks(Element panel) {
        List<WikiBlock> blocks = new ArrayList<>();
        appendChildrenAsBlocks(panel, blocks, 0);
        blocks.removeIf(WikiArticleParser::isEmptyBlock);
        return List.copyOf(blocks);
    }

    /*
     * Do not merge adjacent WikiBlock.Crafting values. Adjacent blocks can be
     * completely different recipes or different table rows. Recipe animation
     * is represented only by multiple crafting grids inside the same
     * WikiContent, or by multiple frames inside the same WikiItemSlot.
     */

    protected static void appendChildrenAsBlocks(Element parent, List<WikiBlock> blocks, int listDepth) {
        for (Element child : parent.children()) {
            appendElementAsBlocks(child, blocks, listDepth);
        }
    }

    protected static void appendElementAsBlocks(Element element, List<WikiBlock> blocks, int listDepth) {
        if (isIgnoredElement(element)) {
            return;
        }

        if (element.hasClass(WikiHtmlContract.MESSAGEBOX_NARROW_WRAPPER)) {
            Element box = element.getElementsByClass(WikiHtmlContract.MESSAGEBOX).first();
            if (box != null) {
                appendMessageBox(box, blocks);
            }
            return;
        }
        if (element.hasClass(WikiHtmlContract.MESSAGEBOX)) {
            appendMessageBox(element, blocks);
            return;
        }

        // TabberNeue has an explicit header/tabs/section/panel contract. A few
        // transcluded pages still contain the legacy Extension:Tabber markup.
        // A generic class="tabber" without either exact contract is merely a
        // container and must not make the whole article fail.
        WikiBlock.TabGroup tabGroup = tryParseTabGroup(element, WikiArticleParser::parsePanelBlocks);
        if (tabGroup != null) {
            blocks.add(tabGroup);
            return;
        }

        if (element.hasClass(WikiHtmlContract.CRAFTING_ROOT)
                && element.hasClass(WikiHtmlContract.CRAFTING_TABLE)) {
            List<WikiCraftingGrid> grids = tryParseCraftingGrids(element);
            if (!grids.isEmpty()) {
                if (grids.size() == 1) {
                    blocks.add(new WikiBlock.Crafting(grids.get(0)));
                } else {
                    // Keep alternatives in one rich-content block. The screen
                    // selects one grid every three seconds, matching the Wiki.
                    blocks.add(new WikiBlock.Paragraph(new WikiContent(
                            WikiText.empty(),
                            List.of(),
                            List.of(),
                            grids
                    )));
                }
                return;
            }
            // Some templates add the class to a wrapper around the actual
            // widget. Recurse so an owned inner widget can still be parsed.
        }

        if (element.hasClass(WikiHtmlContract.INVENTORY_SLOT)) {
            WikiItemSlot slot = parseItemSlot(element);
            if (!slot.isEmpty()) {
                blocks.add(new WikiBlock.Paragraph(new WikiContent(
                        WikiText.empty(),
                        List.of(),
                        List.of(slot),
                        List.of()
                )));
            }
            return;
        }

        // Minecraft UI templates are collections of exact .invslot elements.
        // Treat the whole widget as rich content so its images never escape as
        // giant standalone article images.
        if (element.hasClass(WikiHtmlContract.CRAFTING_ROOT)
                && !element.hasClass(WikiHtmlContract.CRAFTING_TABLE)
                && !element.getElementsByClass(WikiHtmlContract.INVENTORY_SLOT).isEmpty()) {
            WikiContent content = parseContent(element);
            if (!content.isEmpty()) {
                blocks.add(new WikiBlock.Paragraph(content));
            }
            return;
        }

        if (element.hasClass(WikiHtmlContract.WIKITABLE) && element.tagName().equals("table")) {
            WikiBlock.Table table = parseWikiTable(element);
            if (table != null) {
                blocks.add(table);
            }
            return;
        }

        if ((element.tagName().equals("ul") || element.tagName().equals("ol"))
                && followsForgingTreeHeading(blocks)) {
            WikiForgingTree tree = parseForgingTree(element, nextForgingTreeId(blocks));
            if (!tree.isEmpty()) {
                blocks.add(new WikiBlock.ForgingTree(tree));
            }
            return;
        }

        switch (element.tagName()) {
            case "h2", "h3", "h4", "h5", "h6" -> {
                int level = Integer.parseInt(element.tagName().substring(1));
                Element copy = element.clone();
                copy.getElementsByClass(WikiHtmlContract.EDIT_SECTION).remove();
                WikiText text = parseStyledText(copy);
                if (!text.isBlank()) {
                    String anchor = element.id();
                    if (anchor.isBlank()) {
                        Element headline = element.getElementsByClass("mw-headline").first();
                        anchor = headline == null ? "" : headline.id();
                    }
                    blocks.add(new WikiBlock.Heading(level, text, anchor));
                }
            }
            case "p", "blockquote", "dd" -> {
                WikiContent content = parseContent(element);
                if (!content.isEmpty()) {
                    blocks.add(new WikiBlock.Paragraph(content));
                }
            }
            case "ul" -> appendList(element, blocks, false, listDepth);
            case "ol" -> appendList(element, blocks, true, listDepth);
            case "hr" -> blocks.add(new WikiBlock.HorizontalRule());
            case "figure" -> {
                Element image = element.selectFirst("img");
                if (image != null && !isInsideWidget(image)) {
                    Element caption = element.selectFirst("figcaption");
                    blocks.add(new WikiBlock.Image(
                            parseImage(image),
                            caption == null ? WikiText.empty() : parseStyledText(caption)
                    ));
                }
            }
            case "img" -> {
                if (!isInsideWidget(element)) {
                    WikiImage image = parseImage(element);
                    if (!image.isEmpty()) {
                        blocks.add(new WikiBlock.Image(image, WikiText.empty()));
                    }
                }
            }
            default -> appendChildrenAsBlocks(element, blocks, listDepth);
        }
    }

    protected static boolean followsForgingTreeHeading(List<WikiBlock> blocks) {
        if (blocks.isEmpty()) {
            return false;
        }
        WikiBlock previous = blocks.get(blocks.size() - 1);
        return previous instanceof WikiBlock.Heading heading
                && heading.text().plainText().equalsIgnoreCase("Forging Tree");
    }

    protected static String nextForgingTreeId(List<WikiBlock> blocks) {
        int index = 0;
        for (WikiBlock block : blocks) {
            if (block instanceof WikiBlock.ForgingTree) {
                index++;
            }
        }
        return "forging-tree-" + index;
    }

    protected static WikiForgingTree parseForgingTree(Element list, String id) {
        List<WikiForgingTree.Node> roots = new ArrayList<>();
        for (Element child : list.children()) {
            if (child.tagName().equals("li")) {
                WikiForgingTree.Node node = parseForgingTreeNode(child, 0);
                if (node != null) {
                    roots.add(node);
                }
            }
        }
        return new WikiForgingTree(id, roots);
    }

    protected static WikiForgingTree.Node parseForgingTreeNode(Element item, int depth) {
        Element rowCopy = item.clone();
        rowCopy.select("ul,ol").remove();
        rowCopy.select(
                ".drl-expand,.drl-collapse,.expand,.collapse,"
                        + "[data-action=expand],[data-action=collapse]"
        ).remove();

        WikiContent content = normalizeForgingTreeContent(parseContent(rowCopy));
        List<WikiForgingTree.Node> children = new ArrayList<>();
        for (Element nestedList : item.select("ul,ol")) {
            if (nearestAncestorTag(nestedList, "li") != item) {
                continue;
            }
            for (Element nestedItem : nestedList.children()) {
                if (nestedItem.tagName().equals("li")) {
                    WikiForgingTree.Node nested = parseForgingTreeNode(nestedItem, depth + 1);
                    if (nested != null) {
                        children.add(nested);
                    }
                }
            }
        }

        if (content.isEmpty() && children.isEmpty()) {
            return null;
        }

        String classes = String.join(" ", item.classNames()).toLowerCase(Locale.ROOT);
        String rowText = rowCopy.text().toLowerCase(Locale.ROOT);
        boolean expanded = depth == 0
                || classes.contains("expanded")
                || "true".equalsIgnoreCase(item.attr("aria-expanded"))
                || "true".equalsIgnoreCase(item.attr("data-expanded"))
                || rowText.contains("[collapse]");
        return new WikiForgingTree.Node(content, children, expanded);
    }

    protected static WikiContent normalizeForgingTreeContent(WikiContent content) {
        List<WikiText.Span> spans = new ArrayList<>();
        boolean quantityNormalized = false;

        for (WikiText.Span span : content.text().spans()) {
            String text = FORGING_TOGGLE_LABEL.matcher(span.text()).replaceAll(" ");
            if (!quantityNormalized && !text.isBlank()) {
                Matcher matcher = FORGING_QUANTITY.matcher(text);
                if (matcher.find()) {
                    text = matcher.group(1) + "x " + text.substring(matcher.end());
                    quantityNormalized = true;
                }
            }
            if (!text.isEmpty()) {
                spans.add(new WikiText.Span(
                        text,
                        span.href(),
                        span.bold(),
                        span.italic(),
                        span.cssClasses(),
                        span.hoverTitle(),
                        span.hoverText()
                ));
            }
        }

        return new WikiContent(
                new WikiText(spans),
                content.images(),
                content.itemSlots(),
                content.craftingGrids()
        );
    }

    protected static void appendMessageBox(Element element, List<WikiBlock> blocks) {
        WikiContent content = parseContent(element);
        if (content.isEmpty()) {
            return;
        }

        String classes = String.join(" ", element.classNames()).toLowerCase(java.util.Locale.ROOT);
        WikiBlock.MessageBox.Tone tone;
        if (classes.contains("boxcol-green")) {
            tone = WikiBlock.MessageBox.Tone.GREEN;
        } else if (classes.contains("boxcol-red")) {
            tone = WikiBlock.MessageBox.Tone.RED;
        } else if (classes.contains("boxcol-blue")) {
            tone = WikiBlock.MessageBox.Tone.BLUE;
        } else if (classes.contains("boxcol-yellow")) {
            tone = WikiBlock.MessageBox.Tone.YELLOW;
        } else if (classes.contains("boxcol-orange")) {
            tone = WikiBlock.MessageBox.Tone.ORANGE;
        } else if (classes.contains("boxcol-purple")) {
            tone = WikiBlock.MessageBox.Tone.PURPLE;
        } else if (classes.contains("boxcol-gray") || classes.contains("boxcol-grey")) {
            tone = WikiBlock.MessageBox.Tone.GRAY;
        } else {
            tone = WikiBlock.MessageBox.Tone.DEFAULT;
        }
        blocks.add(new WikiBlock.MessageBox(content, tone));
    }

    protected static void appendList(Element list, List<WikiBlock> blocks, boolean ordered, int depth) {
        for (Element child : list.children()) {
            if (!child.tagName().equals("li")) {
                continue;
            }
            Element contentCopy = child.clone();
            for (Element nested : new ArrayList<>(contentCopy.children())) {
                if (nested.tagName().equals("ul") || nested.tagName().equals("ol")) {
                    nested.remove();
                }
            }
            WikiContent content = parseContent(contentCopy);
            if (!content.isEmpty()) {
                blocks.add(new WikiBlock.ListItem(ordered, depth, content));
            }
            for (Element nested : child.children()) {
                if (nested.tagName().equals("ul") || nested.tagName().equals("ol")) {
                    appendList(nested, blocks, nested.tagName().equals("ol"), depth + 1);
                }
            }
        }
    }

}