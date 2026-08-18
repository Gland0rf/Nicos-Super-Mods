package com.nico.client.wiki.service;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiHtmlContract;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class WikiWidgetParser extends WikiContentParser{
    @FunctionalInterface
    protected interface PanelBlockParser {
        List<WikiBlock> parse(Element panel);
    }

    protected static WikiBlock.Table parseWikiTable(Element table) {
        List<WikiBlock.Table.Row> rows = new ArrayList<>();
        for (Element row : directTableRows(table)) {
            List<Element> sourceCells = new ArrayList<>();
            List<List<Element>> cellSegments = new ArrayList<>();
            int segmentCount = 1;
            boolean canExpandSegments = true;

            for (Element cell : row.children()) {
                if (!cell.tagName().equals("th") && !cell.tagName().equals("td")) {
                    continue;
                }
                sourceCells.add(cell);
                List<Element> segments = splitTableCellOnHorizontalRules(cell);
                cellSegments.add(segments);
                segmentCount = Math.max(segmentCount, segments.size());
                if (parsePositiveInt(cell.attr("rowspan"), 1) != 1) {
                    canExpandSegments = false;
                }
            }

            if (sourceCells.isEmpty()) {
                continue;
            }

            if (segmentCount > 1) {
                for (List<Element> segments : cellSegments) {
                    if (segments.size() != 1 && segments.size() != segmentCount) {
                        canExpandSegments = false;
                        break;
                    }
                }
            }

            if (segmentCount > 1 && canExpandSegments) {
                // Some wiki tables simulate multiple sub-rows by putting <hr>
                // separators inside individual cells. Promote those separators
                // to actual rows so related rarity/stat/icon entries stay aligned.
                for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                    List<WikiBlock.Table.Cell> cells = new ArrayList<>();
                    for (int cellIndex = 0; cellIndex < sourceCells.size(); cellIndex++) {
                        Element source = sourceCells.get(cellIndex);
                        List<Element> segments = cellSegments.get(cellIndex);
                        int columnSpan = parsePositiveInt(source.attr("colspan"), 1);

                        if (segments.size() == 1) {
                            if (segmentIndex == 0) {
                                cells.add(new WikiBlock.Table.Cell(
                                        parseContent(source),
                                        source.tagName().equals("th"),
                                        segmentCount,
                                        columnSpan
                                ));
                            }
                        } else {
                            Element segment = segments.get(segmentIndex);
                            cells.add(new WikiBlock.Table.Cell(
                                    parseContent(segment),
                                    source.tagName().equals("th"),
                                    1,
                                    columnSpan
                            ));
                        }
                    }
                    if (!cells.isEmpty()) {
                        rows.add(new WikiBlock.Table.Row(cells));
                    }
                }
                continue;
            }

            List<WikiBlock.Table.Cell> cells = new ArrayList<>();
            for (Element cell : sourceCells) {
                cells.add(new WikiBlock.Table.Cell(
                        parseContent(cell),
                        cell.tagName().equals("th"),
                        parsePositiveInt(cell.attr("rowspan"), 1),
                        parsePositiveInt(cell.attr("colspan"), 1)
                ));
            }
            rows.add(new WikiBlock.Table.Row(cells));
        }
        if (rows.isEmpty()) {
            if (DEBUG) {
                System.err.println("[NSM Wiki] Ignoring table.wikitable without direct table rows");
            }
            return null;
        }
        return new WikiBlock.Table(
                rows,
                table.hasClass(WikiHtmlContract.SORTABLE_TABLE),
                table.hasClass(WikiHtmlContract.PIXELATED)
        );
    }
    protected static List<Element> splitTableCellOnHorizontalRules(Element cell) {
        boolean hasDirectRule = false;
        for (Element child : cell.children()) {
            if (child.tagName().equals("hr")) {
                hasDirectRule = true;
                break;
            }
        }
        if (!hasDirectRule) {
            return List.of(cell);
        }

        List<Element> result = new ArrayList<>();
        Element current = emptyClone(cell);
        for (Node node : cell.childNodes()) {
            if (node instanceof Element child && child.tagName().equals("hr")) {
                result.add(current);
                current = emptyClone(cell);
            } else {
                current.appendChild(node.clone());
            }
        }
        result.add(current);
        return List.copyOf(result);
    }

    protected static Element emptyClone(Element source) {
        Element clone = source.clone();
        clone.empty();
        return clone;
    }

    protected static Elements directTableRows(Element table) {
        Elements rows = new Elements();
        for (Element child : table.children()) {
            if (child.tagName().equals("tr")) {
                rows.add(child);
            } else if (child.tagName().equals("thead")
                    || child.tagName().equals("tbody")
                    || child.tagName().equals("tfoot")) {
                for (Element sectionChild : child.children()) {
                    if (sectionChild.tagName().equals("tr")) {
                        rows.add(sectionChild);
                    }
                }
            }
        }
        return rows;
    }

    protected static WikiBlock.UiGroup tryParseUiGroup(Element root, PanelBlockParser parser) {
        if (root == null || !root.hasClass(WikiHtmlContract.UI_TABBER)) return null;

        List<Element> panelElements = directChildrenWithClass(root, WikiHtmlContract.UI_TAB_CONTENT);
        if (panelElements.isEmpty()) return null;

        List<String> ids = new ArrayList<>();
        for (int index = 0; index < panelElements.size(); index++) {
            Element panel = panelElements.get(index);
            String id = panel.id().trim();
            if (id.startsWith("ui-")) {
                id = id.substring(3);
            }
            if (id.isBlank()) {
                id = "panel-" + index;
            }
            ids.add(id);
        }

        // The wrapper normally has no id, so use its ordered panel ids as a
        // stable page-local key. This survives rebuilds and does not depend on
        // the layout order of ordinary tabbers/infobox tabs.
        String groupKey = String.join("|", ids);
        int selected = 0;
        List<WikiBlock.UiGroup.Panel> panels = new ArrayList<>();

        for (int index = 0; index < panelElements.size(); index++) {
            Element panel = panelElements.get(index);
            String style = panel.attr("style").toLowerCase(Locale.ROOT).replace(" ", "");
            if (!style.contains("display:none") && !panel.hasClass("hidden")) {
                selected = index;
            }

            // Mark the panel before its contents are parsed so every invslot
            // can remember which local UI group its goto-* target belongs to.
            panel.attr(WikiHtmlContract.UI_GROUP_ATTRIBUTE, groupKey);
            List<WikiBlock> panelBlocks = parser.parse(panel);
            panels.add(new WikiBlock.UiGroup.Panel(ids.get(index), panelBlocks));
        }

        return new WikiBlock.UiGroup(groupKey, panels, selected);
    }

    protected static WikiBlock.TabGroup tryParseTabGroup(Element root, PanelBlockParser parser) {
        if (!root.hasClass(WikiHtmlContract.TABBER)) {
            return null;
        }

        WikiBlock.TabGroup modern = tryParseTabberNeue(root, parser);
        if (modern != null) {
            return modern;
        }

        return tryParseLegacyTabber(root, parser);
    }

    protected static WikiBlock.TabGroup tryParseTabberNeue(Element root, PanelBlockParser parser) {
        Element header = directChildWithClass(root, WikiHtmlContract.TABBER_HEADER);
        Element section = directChildWithClass(root, WikiHtmlContract.TABBER_SECTION);
        if (header == null || section == null) {
            return null;
        }

        Element tabsRoot = directChildWithClass(header, WikiHtmlContract.TABBER_TABS);
        if (tabsRoot == null) {
            return null;
        }

        List<Element> tabElements = directChildrenWithClass(tabsRoot, WikiHtmlContract.TABBER_TAB);
        List<Element> panelElements = directChildrenWithClass(section, WikiHtmlContract.TABBER_PANEL);
        if (tabElements.isEmpty() || panelElements.isEmpty()) {
            return null;
        }

        Map<String, Element> panelsById = new java.util.LinkedHashMap<>();
        for (Element panel : panelElements) {
            if (!panel.id().isBlank()) {
                panelsById.put(panel.id(), panel);
            }
        }

        List<WikiBlock.TabGroup.Tab> tabs = new ArrayList<>();
        int selected = 0;
        for (int index = 0; index < tabElements.size(); index++) {
            Element tab = tabElements.get(index);
            String controlledId = tab.attr("aria-controls").trim();
            Element panel = controlledId.isBlank() ? null : panelsById.get(controlledId);
            if (panel == null && index < panelElements.size()) {
                panel = panelElements.get(index);
            }
            if (panel == null) {
                continue;
            }

            String title = tab.text().trim();
            if (title.isBlank()) {
                title = firstNonBlank(tab.attr("aria-label"), "Tab " + (tabs.size() + 1));
            }
            if ("true".equalsIgnoreCase(tab.attr("aria-selected")) || tab.hasClass("active")) {
                selected = tabs.size();
            }

            List<WikiBlock> panelBlocks = parser.parse(panel);
            tabs.add(new WikiBlock.TabGroup.Tab(title, panel.id(), panelBlocks));
        }

        return tabs.isEmpty() ? null : new WikiBlock.TabGroup(tabs, selected);
    }

    protected static WikiBlock.TabGroup tryParseLegacyTabber(Element root, PanelBlockParser parser) {
        List<Element> panels = directChildrenWithClass(root, WikiHtmlContract.LEGACY_TABBER_PANEL);
        if (panels.isEmpty()) {
            // Legacy markup sometimes wraps the panels in one neutral container.
            for (Element child : root.children()) {
                if (child.hasClass(WikiHtmlContract.LEGACY_TABBER_NAV)) {
                    continue;
                }
                panels.addAll(directChildrenWithClass(child, WikiHtmlContract.LEGACY_TABBER_PANEL));
            }
        }
        if (panels.isEmpty()) {
            return null;
        }

        List<WikiBlock.TabGroup.Tab> tabs = new ArrayList<>();
        int selected = 0;
        for (Element panel : panels) {
            String title = firstNonBlank(panel.attr("title"), panel.attr("data-title"));
            if (title.isBlank()) {
                title = "Tab " + (tabs.size() + 1);
            }
            if (panel.hasClass(WikiHtmlContract.LEGACY_TABBER_DEFAULT)
                    || panel.hasClass("active")) {
                selected = tabs.size();
            }

            List<WikiBlock> panelBlocks = parser.parse(panel);
            tabs.add(new WikiBlock.TabGroup.Tab(title, panel.id(), panelBlocks));
        }

        return new WikiBlock.TabGroup(tabs, selected);
    }
}
