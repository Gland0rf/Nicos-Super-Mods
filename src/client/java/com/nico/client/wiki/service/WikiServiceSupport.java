package com.nico.client.wiki.service;

import com.google.gson.JsonObject;
import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiHtmlContract;
import com.nico.client.wiki.WikiText;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WikiServiceSupport {
    protected static final String WIKI_API_ENDPOINT = "https://hypixelskyblock.minecraft.wiki/api.php";
    protected static final String WIKI_BASE = "https://hypixelskyblock.minecraft.wiki";
    protected static final String WIKI_ARTICLE_BASE = WIKI_BASE + "/w/";
    protected static final boolean DEBUG = Boolean.getBoolean("nsm.wiki.debug");

    protected static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    protected static String normalizeImageUrl(Element image, String value) {
        if (value == null || value.isBlank() || value.startsWith("data:")) {
            return "";
        }

        String result = value.trim();
        try {
            URI base = URI.create(image.baseUri().isBlank() ? WIKI_BASE + "/" : image.baseUri());
            result = base.resolve(result).toString();
        } catch (IllegalArgumentException ignored) {
            result = absoluteUrl(result);
        }
        return result.replace(" ", "%20");
    }

    protected static WikiText parseStyledText(Element element) {
        Element clone = element.clone();
        removeIgnoredElements(clone);
        List<MutableSpan> spans = new ArrayList<>();
        appendNodeText(clone, spans, "", false, false, "");
        trimSpans(spans);
        List<WikiText.Span> result = new ArrayList<>();
        for (MutableSpan span : spans) {
            if (!span.text.isEmpty()) {
                result.add(new WikiText.Span(span.text, span.href, span.bold, span.italic, span.cssClasses));
            }
        }
        return new WikiText(result);
    }

    protected static void appendNodeText(
            Node node,
            List<MutableSpan> spans,
            String href,
            boolean bold,
            boolean italic,
            String cssClasses
    ) {
        if (node instanceof TextNode textNode) {
            appendText(spans, textNode.getWholeText(), href, bold, italic, cssClasses);
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.tagName();
        if (tag.equals("script") || tag.equals("style") || tag.equals("noscript")
                || tag.equals("img") || tag.equals("svg") || tag.equals("canvas")) {
            return;
        }
        if (tag.equals("br")) {
            appendText(spans, " ", href, bold, italic, cssClasses);
            return;
        }
        String nextHref = tag.equals("a") ? absoluteUrl(element, "href") : href;
        boolean nextBold = bold || tag.equals("b") || tag.equals("strong");
        boolean nextItalic = italic || tag.equals("i") || tag.equals("em");
        String nextClasses = mergeClassStrings(cssClasses, String.join(" ", element.classNames()));
        for (Node child : element.childNodes()) {
            appendNodeText(child, spans, nextHref, nextBold, nextItalic, nextClasses);
        }
        if (tag.equals("p") || tag.equals("li") || tag.equals("dd") || tag.equals("dt") || tag.equals("div")) {
            appendText(spans, " ", href, bold, italic, cssClasses);
        }
    }

    protected static void appendText(
            List<MutableSpan> spans,
            String raw,
            String href,
            boolean bold,
            boolean italic,
            String cssClasses
    ) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String text = raw.replace('\u00a0', ' ').replaceAll("\\s+", " ");
        if (spans.isEmpty()) {
            text = text.stripLeading();
        } else if (spans.get(spans.size() - 1).text.endsWith(" ") && text.startsWith(" ")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return;
        }
        if (!spans.isEmpty()) {
            MutableSpan previous = spans.get(spans.size() - 1);
            if (previous.sameFormat(href, bold, italic, cssClasses)) {
                previous.text += text;
                return;
            }
        }
        spans.add(new MutableSpan(text, href, bold, italic, cssClasses));
    }

    protected static void trimSpans(List<MutableSpan> spans) {
        while (!spans.isEmpty() && spans.get(0).text.isBlank()) {
            spans.remove(0);
        }
        while (!spans.isEmpty() && spans.get(spans.size() - 1).text.isBlank()) {
            spans.remove(spans.size() - 1);
        }
        if (!spans.isEmpty()) {
            spans.get(0).text = spans.get(0).text.stripLeading();
            spans.get(spans.size() - 1).text = spans.get(spans.size() - 1).text.stripTrailing();
        }
    }

    protected static boolean isEmptyBlock(WikiBlock block) {
        if (block instanceof WikiBlock.Heading heading) {
            return heading.text().isBlank();
        }
        if (block instanceof WikiBlock.Paragraph paragraph) {
            return paragraph.content().isEmpty();
        }
        if (block instanceof WikiBlock.ListItem item) {
            return item.content().isEmpty();
        }
        if (block instanceof WikiBlock.Table table) {
            return table.rows().isEmpty();
        }
        if (block instanceof WikiBlock.TabGroup tabs) {
            return tabs.tabs().isEmpty();
        }
        if (block instanceof WikiBlock.Crafting crafting) {
            return crafting.grid().isEmpty();
        }
        if (block instanceof WikiBlock.Image image) {
            return image.image().isEmpty();
        }
        return false;
    }

    protected static void removeIgnoredElements(Element root) {
        root.select("script,style,noscript").remove();
        root.getElementsByClass(WikiHtmlContract.EDIT_SECTION).remove();
        root.getElementsByClass(WikiHtmlContract.REFERENCE).remove();
        root.getElementsByClass(WikiHtmlContract.REFERENCES).remove();
        root.getElementsByClass(WikiHtmlContract.PRINT_FOOTER).remove();
        root.getElementsByClass(WikiHtmlContract.CATEGORY_LINKS).remove();
        root.getElementsByClass(WikiHtmlContract.NAVBOX).remove();

        Element tocById = root.getElementById("toc");
        if (tocById != null) {
            tocById.remove();
        }
        root.getElementsByClass("toc").remove();
        root.getElementsByClass("mw-table-of-contents").remove();
    }

    protected static boolean isIgnoredElement(Element element) {
        return element.hasClass(WikiHtmlContract.EDIT_SECTION)
                || element.hasClass(WikiHtmlContract.REFERENCE)
                || element.hasClass(WikiHtmlContract.REFERENCES)
                || element.hasClass(WikiHtmlContract.PRINT_FOOTER)
                || element.hasClass(WikiHtmlContract.CATEGORY_LINKS)
                || element.hasClass(WikiHtmlContract.NAVBOX)
                || element.hasClass(WikiHtmlContract.NO_EXCERPT)
                || element.hasClass(WikiHtmlContract.NOT_SEARCHABLE)
                || element.id().equals("toc")
                || element.hasClass("toc")
                || element.hasClass("mw-table-of-contents");
    }

    protected static Element directChildWithClass(Element parent, String className) {
        if (parent == null) {
            return null;
        }
        for (Element child : parent.children()) {
            if (child.hasClass(className)) {
                return child;
            }
        }
        return null;
    }

    protected static List<Element> directChildrenWithClass(Element parent, String className) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (Element child : parent.children()) {
            if (child.hasClass(className)) {
                result.add(child);
            }
        }
        return result;
    }

    protected static Element ownedDescendantWithClass(
            Element owner,
            String targetClass,
            String ownerClass
    ) {
        if (owner == null) {
            return null;
        }
        for (Element candidate : owner.getElementsByClass(targetClass)) {
            Element nearestOwner = nearestAncestorWithClass(candidate, ownerClass);
            if (nearestOwner == owner) {
                return candidate;
            }
        }
        return null;
    }

    protected static boolean isInsideWidget(Element element) {
        return hasAncestorClass(element, WikiHtmlContract.INVENTORY_SLOT)
                || hasAncestorClass(element, WikiHtmlContract.CRAFTING_TABLE)
                || hasAncestorClass(element, WikiHtmlContract.CRAFTING_ROOT);
    }

    protected static boolean hasAncestorClass(Element element, String className) {
        return nearestAncestorWithClass(element, className) != null;
    }

    protected static Element nearestAncestorWithClass(Element element, String className) {
        Element current = element.parent();
        while (current != null) {
            if (current.hasClass(className)) {
                return current;
            }
            current = current.parent();
        }
        return null;
    }

    protected static String absoluteUrl(Element element, String attribute) {
        String absolute = element.absUrl(attribute);
        return absolute.isBlank() ? absoluteUrl(element.attr(attribute)) : absolute;
    }

    protected static String absoluteUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = value.trim();
        if (result.startsWith("//")) {
            return "https:" + result;
        }
        if (result.startsWith("/")) {
            return WIKI_BASE + result;
        }
        return result;
    }

    protected static String mergeClassStrings(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right.trim();
        }
        if (right == null || right.isBlank()) {
            return left.trim();
        }
        return left.trim() + " " + right.trim();
    }

    protected static int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    protected static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    protected static String jsonString(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    protected static HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "NSM-Mod/1.0 Hypixel-SkyBlock-Wiki-Reader")
                .header("Accept-Language", "en-US,en;q=0.9");
    }

    protected static String validateResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new HypixelWikiService.WikiRequestException("Wiki HTTP " + response.statusCode() + " from " + response.uri());
        }
        if (response.body() == null || response.body().isBlank()) {
            throw new HypixelWikiService.WikiRequestException("Wiki returned an empty response");
        }
        return response.body();
    }

    protected static URI buildApiUri(Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(WIKI_API_ENDPOINT).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                result.append('&');
            }
            first = false;
            result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(result.toString());
    }

    protected static void logContractElements(Element root) {
        System.out.println("[Wiki] article root classes=" + root.classNames());
        for (String className : List.of(
                WikiHtmlContract.INFOBOX,
                WikiHtmlContract.CRAFTING_TABLE,
                WikiHtmlContract.TABBER,
                WikiHtmlContract.WIKITABLE,
                WikiHtmlContract.INVENTORY_SLOT
        )) {
            System.out.println("[Wiki] ." + className + " count=" + root.getElementsByClass(className).size());
        }
    }

    protected static final class MutableSpan {
        private String text;
        private final String href;
        private final boolean bold;
        private final boolean italic;
        private final String cssClasses;

        private MutableSpan(String text, String href, boolean bold, boolean italic, String cssClasses) {
            this.text = text;
            this.href = href == null ? "" : href;
            this.bold = bold;
            this.italic = italic;
            this.cssClasses = cssClasses == null ? "" : cssClasses;
        }

        private boolean sameFormat(String otherHref, boolean otherBold, boolean otherItalic, String otherClasses) {
            return href.equals(otherHref == null ? "" : otherHref)
                    && bold == otherBold
                    && italic == otherItalic
                    && cssClasses.equals(otherClasses == null ? "" : otherClasses);
        }
    }
}
