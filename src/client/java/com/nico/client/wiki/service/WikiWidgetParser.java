package com.nico.client.wiki.service;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiHtmlContract;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class WikiWidgetParser extends WikiContentParser{
    @FunctionalInterface
    protected interface PanelBlockParser {
        List<WikiBlock> parse(Element panel);
    }

    protected static WikiBlock.Table parseWikiTable(Element table) {
        List<WikiBlock.Table.Row> rows = new ArrayList<>();
        for (Element row : directTableRows(table)) {
            List<WikiBlock.Table.Cell> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                if (!cell.tagName().equals("th") && !cell.tagName().equals("td")) {
                    continue;
                }
                cells.add(new WikiBlock.Table.Cell(
                        parseContent(cell),
                        cell.tagName().equals("th"),
                        parsePositiveInt(cell.attr("rowspan"), 1),
                        parsePositiveInt(cell.attr("colspan"), 1)
                ));
            }
            if (!cells.isEmpty()) {
                rows.add(new WikiBlock.Table.Row(cells));
            }
        }
        if (rows.isEmpty()) {
            if (DEBUG) {
                System.err.println("[Wiki] Ignoring table.wikitable without direct table rows");
            }
            return null;
        }
        return new WikiBlock.Table(
                rows,
                table.hasClass(WikiHtmlContract.SORTABLE_TABLE),
                table.hasClass(WikiHtmlContract.PIXELATED)
        );
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
