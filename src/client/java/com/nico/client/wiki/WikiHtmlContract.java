package com.nico.client.wiki;

/**
 * Exact HTML classes currently emitted by the independent Hypixel SkyBlock Wiki.
 * The parser deliberately fails closed instead of scoring or guessing arbitrary elements.
 */
public final class WikiHtmlContract {
    private WikiHtmlContract() { }

    public static final String ARTICLE_ROOT = "mw-parser-output";

    public static final String MESSAGEBOX_NARROW_WRAPPER = "messagebox-narrow-wrapper";
    public static final String MESSAGEBOX = "messagebox";
    public static final String MESSAGEBOX_MAIN = "messagebox-main";
    public static final String MESSAGEBOX_IMAGE = "messagebox-image";
    public static final String MESSAGEBOX_TEXT = "messagebox-text";

    public static final String AMBOX = "ambox";
    public static final String MBOX_IMAGE = "mbox-image";
    public static final String MBOX_TEXT = "mbox-text";

    public static final String DARK_MESSAGEBOX = "darkmsgbox";
    public static final String DARK_MESSAGEBOX_IMAGE = "darkmsgbox-image";
    public static final String DARK_MESSAGEBOX_TEXT = "darkmsgbox-text";
    public static final String DARK_MESSAGEBOX_BOTTOM = "darkmsgbox-bottom";

    public static final String INFOBOX = "infobox";
    public static final String INFOBOX_TITLE = "infobox-title";
    public static final String INFOBOX_PANEL = "infobox-panel";
    public static final String INFOBOX_SECTION_LABELS = "infobox-section-labels";
    public static final String INFOBOX_SECTION = "section";
    public static final String INFOBOX_ACTIVE_SECTION = "active";
    public static final String INFOBOX_GROUP = "group";
    public static final String INFOBOX_HEADER = "infobox-header";
    public static final String INFOBOX_ROW_CONTAINER = "infobox-row-container";
    public static final String INFOBOX_ROW_LABEL = "infobox-row-label";
    public static final String INFOBOX_ROW_VALUE = "infobox-row-value";
    public static final String INFOBOX_IMAGE_CONTAINER = "infobox-image-container";
    public static final String INFOBOX_IMAGE = "infobox-image";
    public static final String INFOBOX_IMAGE_CAPTION = "infobox-image-caption";

    public static final String CRAFTING_ROOT = "mcui";
    public static final String CRAFTING_TABLE = "mcui-Crafting_Table";
    public static final String CRAFTING_INPUT = "mcui-input";
    public static final String CRAFTING_ROW = "mcui-row";
    public static final String CRAFTING_ARROW = "mcui-arrow";
    public static final String CRAFTING_OUTPUT = "mcui-output";
    public static final String CRAFTING_SHAPELESS = "mcui-shapeless";
    public static final String CRAFTING_FIXED = "mcui-fixed";

    public static final String INVENTORY_SLOT = "invslot";
    public static final String INVENTORY_SLOT_LARGE = "invslot-large";
    public static final String INVENTORY_SLOT_ITEM = "invslot-item";
    public static final String INVENTORY_SLOT_ITEM_IMAGE = "invslot-item-image";
    public static final String INVENTORY_SLOT_STACK_SIZE = "invslot-stacksize";
    public static final String INVENTORY_SLOT_ACTIVE_FRAME = "animated-active";

    public static final String UI_TABBER = "sbw-ui-tabber";
    public static final String UI_TAB_CONTENT = "sbw-ui-tab-content";
    public static final String UI_GOTO_PREFIX = "goto-";
    public static final String UI_GROUP_ATTRIBUTE = "data-nsm-ui-group";

    public static final String TABBER = "tabber";
    public static final String TABBER_HEADER = "tabber__header";
    public static final String TABBER_TABS = "tabber__tabs";
    public static final String TABBER_TAB = "tabber__tab";
    public static final String TABBER_SECTION = "tabber__section";
    public static final String TABBER_PANEL = "tabber__panel";

    // Legacy Extension:Tabber markup still appears on some transcluded pages.
    public static final String LEGACY_TABBER_NAV = "tabbernav";
    public static final String LEGACY_TABBER_PANEL = "tabbertab";
    public static final String LEGACY_TABBER_DEFAULT = "tabbertabdefault";

    public static final String WIKITABLE = "wikitable";
    public static final String SORTABLE_TABLE = "sortable";
    public static final String PIXELATED = "pixelated";

    public static final String NAVBOX = "navbox";
    public static final String NO_EXCERPT = "noexcerpt";
    public static final String NOT_SEARCHABLE = "navigation-not-searchable";
    public static final String EDIT_SECTION = "mw-editsection";
    public static final String REFERENCE = "reference";
    public static final String REFERENCES = "mw-references-wrap";
    public static final String PRINT_FOOTER = "printfooter";
    public static final String CATEGORY_LINKS = "catlinks";
}