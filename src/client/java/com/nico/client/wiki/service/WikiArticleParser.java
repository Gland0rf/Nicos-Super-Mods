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
import org.jsoup.select.Elements;

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

        WikiInfobox infobox = parseInfoboxes(workingRoot);
        workingRoot.getElementsByClass(WikiHtmlContract.INFOBOX).remove();

        List<WikiBlock> blocks = new ArrayList<>();
        appendChildrenAsBlocks(workingRoot, blocks, 0);
        blocks.removeIf(WikiArticleParser::isEmptyBlock);
        trimFooterNavigation(blocks);

        if (blocks.isEmpty() && infobox.isEmpty()) {
            throw new HypixelWikiService.WikiRequestException("The Wiki article contained no supported content");
        }
        return new WikiPage(title, "Hypixel SkyBlock Wiki", pageUri, revisionId, infobox, blocks);
    }

    /**
     * Parse every top-level infobox on the page. Some articles deliberately
     * place multiple infoboxes next to each other; treating only the first one
     * as an infobox would let the others fall through into normal article
     * parsing and turn their internal icons into standalone content.
     */
    protected static WikiInfobox parseInfoboxes(Element articleRoot) {
        List<Element> roots = new ArrayList<>();
        for (Element candidate : articleRoot.getElementsByClass(WikiHtmlContract.INFOBOX)) {
            if (nearestAncestorWithClass(candidate, WikiHtmlContract.INFOBOX) == null) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty()) {
            return WikiInfobox.empty();
        }

        String title = "";
        List<WikiInfobox.Entry> entries = new ArrayList<>();
        for (Element root : roots) {
            Element titleElement = root.getElementsByClass(WikiHtmlContract.INFOBOX_TITLE).first();
            String rootTitle = titleElement == null ? "" : titleElement.text().trim();
            if (title.isBlank()) title = rootTitle;
            else if (!rootTitle.isBlank() && !rootTitle.equalsIgnoreCase(title)) {
                entries.add(new WikiInfobox.Header(WikiText.plain(rootTitle)));
            }

            List<WikiInfobox.Entry> rootEntries = new ArrayList<>();
            walkInfobox(root, rootEntries);
            entries.addAll(removeUnsupportedDynamicPriceEntries(rootEntries));
        }

        WikiInfobox result = new WikiInfobox(title, entries);
        if (result.isEmpty() && DEBUG) {
            System.err.println("[NSM Wiki] Ignoring an unsupported .infobox structure on this page");
        }
        return result;
    }

    protected static List<WikiInfobox.Entry> removeUnsupportedDynamicPriceEntries(List<WikiInfobox.Entry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();

        List<WikiInfobox.Entry> result = new ArrayList<>(entries.size());
        for (WikiInfobox.Entry entry : entries) {
            if (entry instanceof WikiInfobox.Header header
                    && isUnsupportedDynamicPriceHeader(header.text().plainText())) {
                continue;
            }

            if (entry instanceof WikiInfobox.Row row
                    && isUnsupportedDynamicPriceLabel(row.label().plainText())) {
                continue;
            }

            if (entry instanceof WikiInfobox.PanelTabs tabs && !tabs.sections().isEmpty()) {
                List<String> labels = new ArrayList<>();
                List<List<WikiInfobox.Entry>> sections = new ArrayList<>();
                int active = 0;

                int count = Math.min(tabs.labels().size(), tabs.sections().size());
                for (int index = 0; index < count; index++) {
                    String label = tabs.labels().get(index);
                    if (isUnsupportedDynamicPricePanel(label)) continue;

                    List<WikiInfobox.Entry> cleaned = removeUnsupportedDynamicPriceEntries(tabs.sections().get(index));
                    if (cleaned.isEmpty()) continue;

                    if (index == tabs.activeIndex()) active = labels.size();
                    labels.add(label);
                    sections.add(cleaned);
                }

                if (labels.isEmpty()) continue;
                if (labels.size() == 1) {
                    result.addAll(sections.get(0));
                    continue;
                }

                result.add(new WikiInfobox.PanelTabs(labels, active, sections));
                continue;
            }

            result.add(entry);
        }

        return List.copyOf(result);
    }

    protected static boolean isUnsupportedDynamicPriceHeader(String value) {
        String label = normalizeInfoboxLabel(value);
        return label.equals("auction house")
                || label.equals("auction house prices")
                || label.equals("auction house price")
                || label.equals("ah prices")
                || label.equals("ah price");
    }

    protected static boolean isUnsupportedDynamicPriceLabel(String value) {
        String label = normalizeInfoboxLabel(value);
        return label.startsWith("lowest bin")
                || label.equals("ah cost")
                || label.equals("ah cost daily average")
                || label.equals("auction house cost")
                || label.equals("auction house cost daily average")
                || label.equals("bazaar material cost")
                || label.equals("bazaar material cost to upgrade");
    }

    protected static boolean isUnsupportedDynamicPricePanel(String value) {
        String label = normalizeInfoboxLabel(value);
        return label.equals("auction house")
                || label.equals("auction house prices")
                || label.equals("ah")
                || label.equals("ah prices");
    }

    protected static String normalizeInfoboxLabel(String value) {
        return value == null
                ? ""
                : value.replace('\u00A0', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    protected static void walkInfobox(Element parent, List<WikiInfobox.Entry> entries) {
        for (Element child : parent.children()) {
            if (child.hasClass(WikiHtmlContract.INFOBOX_TITLE)) {
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_PANEL)) {
                parseInfoboxPanel(child, entries);
                continue;
            }
            if (child.hasClass(WikiHtmlContract.INFOBOX_IMAGE_CONTAINER)) {
                parseInfoboxImageContainer(child, entries);
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
                parseInfoboxRowContainer(child, entries);
                continue;
            }
            walkInfobox(child, entries);
        }
    }

    /**
     * Parses an infobox data row while preserving image-only rows.
     *
     * <p>The Wiki's infobox image helper currently emits ordinary images via
     * an unlabeled {@code .infobox-row-value} instead of the older
     * {@code .infobox-image-container} markup. Those rows therefore need to
     * be promoted to {@link WikiInfobox.Image} entries before the normal
     * label/value row parser runs.</p>
     */
    protected static void parseInfoboxRowContainer(Element rowContainer, List<WikiInfobox.Entry> entries) {
        Elements labels = rowContainer.getElementsByClass(WikiHtmlContract.INFOBOX_ROW_LABEL);
        Elements values = rowContainer.getElementsByClass(WikiHtmlContract.INFOBOX_ROW_VALUE);

        if (labels.isEmpty() && values.size() == 1) {
            Element value = values.get(0);
            Element imageElement = selectInfoboxThumbnail(value);

            if (imageElement != null) {
                WikiImage image = parseImage(imageElement);
                Element captionElement = value.getElementsByClass(WikiHtmlContract.INFOBOX_INLINE_IMAGE_CAPTION).first();
                WikiText caption = captionElement == null ? WikiText.empty() : parseStyledText(captionElement);
                if (!image.isEmpty() || !caption.isBlank()) {
                    entries.add(new WikiInfobox.Image(image, caption));
                    return;
                }
            }
        }

        entries.addAll(parseInfoboxRows(rowContainer));
    }

    /**
     * Finds the image produced by the Wiki's infobox image helper.
     *
     * <p>MediaWiki 1.40+ applies the {@code class=} file option to the wrapper
     * around the image instead of directly to the {@code <img>} element. Keep
     * the older form as a fallback so the parser works with either DOM shape.</p>
     */
    private static Element selectInfoboxThumbnail(Element value) {
        String thumbnailClass = WikiHtmlContract.INFOBOX_IMAGE_THUMBNAIL;

        Element image = value.selectFirst(
                ".animated-active ." + thumbnailClass + " img, "
                        + ".animated-active img." + thumbnailClass
        );
        if (image != null) return image;

        return value.selectFirst(
                "." + thumbnailClass + " img, img." + thumbnailClass
        );
    }

    protected static void parseInfoboxPanel(Element panel, List<WikiInfobox.Entry> entries) {
        Element labelsContainer = ownedDescendantWithClass(
                panel,
                WikiHtmlContract.INFOBOX_SECTION_LABELS,
                WikiHtmlContract.INFOBOX_PANEL
        );
        if (labelsContainer == null) {
            walkInfobox(panel, entries);
            return;
        }

        WikiInfobox.PanelTabs parsedTabs = parseInfoboxPanelTabs(labelsContainer);
        if (parsedTabs.labels().isEmpty()) {
            walkInfobox(panel, entries);
            return;
        }

        List<Element> sectionElements = new ArrayList<>();
        for (Element section : panel.getElementsByClass(WikiHtmlContract.INFOBOX_SECTION)) {
            if (nearestAncestorWithClass(section, WikiHtmlContract.INFOBOX_PANEL) == panel) {
                sectionElements.add(section);
            }
        }

        // Fail closed if PortableInfobox changes its markup. Flattening the
        // content is preferable to wiring a tab to the wrong section.
        if (sectionElements.size() != parsedTabs.labels().size()) {
            walkInfobox(panel, entries);
            return;
        }

        List<List<WikiInfobox.Entry>> sections = new ArrayList<>(sectionElements.size());
        for (Element section : sectionElements) {
            List<WikiInfobox.Entry> sectionEntries = new ArrayList<>();
            walkInfobox(section, sectionEntries);
            sections.add(List.copyOf(sectionEntries));
        }

        entries.add(new WikiInfobox.PanelTabs(
                parsedTabs.labels(),
                parsedTabs.activeIndex(),
                sections
        ));
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
        if (element.hasClass(WikiHtmlContract.MESSAGEBOX_NARROW_WRAPPER)) {
            Element box = element.getElementsByClass(WikiHtmlContract.MESSAGEBOX).first();
            if (box == null) {
                box = element.getElementsByClass(WikiHtmlContract.MESSAGEBOX_MAIN).first();
            }
            if (box != null) {
                appendMessageBox(box, blocks);
            } else {
                appendMessageBox(element, blocks);
            }
            return;
        }
        if (isMessageBoxContainer(element)) {
            appendMessageBox(element, blocks);
            return;
        }

        if (isIgnoredElement(element)) return;

        // SkyBlock's interactive inventory UIs use hidden sibling panels and
        // goto-* classes on slots. Parse the whole wrapper as one switchable
        // group before generic recursion can flatten all of its panels.
        WikiBlock.UiGroup uiGroup = tryParseUiGroup(element, WikiArticleParser::parsePanelBlocks);
        if (uiGroup != null) {
            blocks.add(uiGroup);
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
        
        if (isWikiGallery(element)) {
            appendGallery(element, blocks);
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
                            caption == null
                                    ? WikiText.empty()
                                    : parseStyledText(caption),
                            isRightFloatingFigure(element)
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

    protected static boolean isWikiGallery(Element element) {
        if (element == null) {
            return false;
        }
        String classes = String.join(" ", element.classNames()).toLowerCase(Locale.ROOT);
        return element.hasClass("gallery")
                || classes.contains("mw-gallery")
                || classes.contains("gallerybox");
    }

    protected static void appendGallery(Element gallery, List<WikiBlock> blocks) {
        boolean found = false;

        for (Element item : gallery.children()) {
            if (!item.tagName().equals("li") && !item.hasClass("gallerybox")) {
                continue;
            }

            Element imageElement = item.selectFirst("img");
            if (imageElement == null || isInsideWidget(imageElement)) {
                continue;
            }

            WikiImage image = parseImage(imageElement);
            if (image.isEmpty()) {
                continue;
            }

            Element captionElement = item.selectFirst(".gallerytext");
            if (captionElement == null) {
                captionElement = item.selectFirst("figcaption");
            }
            WikiText caption = captionElement == null ? WikiText.empty() : parseStyledText(captionElement);

            blocks.add(new WikiBlock.Image(image, caption, false));
            found = true;
        }

        if (!found) {
            for (Element imageElement : gallery.select("img")) {
                if (isInsideWidget(imageElement)) continue;
                WikiImage image = parseImage(imageElement);
                if (!image.isEmpty()) {
                    blocks.add(new WikiBlock.Image(image, WikiText.empty(), false));
                }
            }
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
                        span.hoverText(),
                        span.inlineImage()
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
        } else if (classes.contains("ambox-content") || classes.contains("ambox-notice")) {
            tone = WikiBlock.MessageBox.Tone.BLUE;
        } else if (classes.contains("ambox-style")) {
            tone = WikiBlock.MessageBox.Tone.YELLOW;
        } else if (classes.contains("ambox-speedy")) {
            tone = WikiBlock.MessageBox.Tone.RED;
        } else {
            tone = WikiBlock.MessageBox.Tone.DEFAULT;
        }
        blocks.add(new WikiBlock.MessageBox(content, tone));
    }

    protected static boolean isMessageBoxContainer(Element element) {
        if (element == null) return false;
        if (element.hasClass(WikiHtmlContract.MESSAGEBOX)
                || element.hasClass(WikiHtmlContract.MESSAGEBOX_MAIN)
                || element.hasClass(WikiHtmlContract.AMBOX)
                || element.hasClass(WikiHtmlContract.DARK_MESSAGEBOX)){
            return true;
        }

        String classes = String.join(" ", element.classNames()).toLowerCase(Locale.ROOT);
        boolean boxLike = classes.contains("messagebox")
                || classes.contains("message-box")
                || classes.contains("darkmsgbox")
                || classes.contains("ambox")
                || classes.contains("mbox")
                || classes.contains("maintenance")
                || classes.contains("notice")
                || classes.contains("outdated");

        if (!boxLike) return false;

        String text = element.text().trim();
        return text.length() >= 12;
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

    /**
     * The independent Wiki appends a large H2 "Navigation" navbox to most
     * articles. It is site chrome rather than article content, so stop the
     * parsed document at the final Navigation heading.
     */
    protected static void trimFooterNavigation(List<WikiBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        for (int index = blocks.size() - 1; index >= 0; index--) {
            WikiBlock block = blocks.get(index);
            if (block instanceof WikiBlock.Heading heading
                    && heading.level() == 2
                    && heading.text().plainText().equalsIgnoreCase("Navigation")) {
                blocks.subList(index, blocks.size()).clear();
                return;
            }
        }
    }


    protected static boolean isRightFloatingFigure(Element element) {
        if (element == null) return false;

        boolean mediaWikiThumb = false;
        Element current = element;
        while (current != null) {
            String classes = String.join(" ", current.classNames()).toLowerCase(Locale.ROOT);
            String style = current.attr("style").toLowerCase(Locale.ROOT).replace(" ", "");
            String type = current.attr("typeof").toLowerCase(Locale.ROOT);

            // Explicit alignment always wins over the normal thumbnail default.
            if (classes.contains("mw-halign-left")
                    || classes.contains("floatleft")
                    || classes.contains("float-left")
                    || classes.contains("tleft")
                    || current.attr("align").equalsIgnoreCase("left")
                    || style.contains("float:left")) {
                return false;
            }
            if (classes.contains("mw-halign-right")
                    || classes.contains("floatright")
                    || classes.contains("float-right")
                    || classes.contains("tright")
                    || current.attr("align").equalsIgnoreCase("right")
                    || style.contains("float:right")) {
                return true;
            }

            mediaWikiThumb |= type.contains("mw:file/thumb")
                    || classes.contains("thumb")
                    || classes.contains("thumbinner");

            current = current.parent();
        }
        return mediaWikiThumb;
    }
}