package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiBrowserStore;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiImageTextureCache;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;
import com.nico.client.wiki.WikiTitleResolver;
import com.nico.client.wiki.service.HypixelWikiService;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Hover detection and Wiki-style tooltip rendering. */
abstract class WikiScreenInteractionRenderer extends WikiScreenWidgetRenderer {
    protected WikiScreenInteractionRenderer(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    protected boolean containsToc(double mouseX, double mouseY) {
        for (TocHitbox hitbox : tocHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsTab(double mouseX, double mouseY) {
        for (TabHitbox hitbox : tabHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsLinkedSlot(double mouseX, double mouseY) {
        for (SlotHitbox hitbox : slotHitboxes) {
            if (hitbox.contains(mouseX, mouseY) && !hitbox.frame().link().isBlank()) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsLink(double mouseX, double mouseY) {
        for (LinkHitbox hitbox : linkHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsPageTab(double mouseX, double mouseY) {
        for (PageTabHitbox hitbox : pageTabHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsContextMenu(double mouseX, double mouseY) {
        for (ContextMenuHitbox hitbox : contextMenuHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsSearchSuggestion(double mouseX, double mouseY) {
        for (SearchSuggestionHitbox hitbox : searchSuggestionHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected boolean containsSpecialPageAction(double mouseX, double mouseY) {
        for (SpecialPageHitbox hitbox : specialPageHitboxes) {
            if (hitbox.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    protected void renderHoveredSlotTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        SlotHitbox hovered = null;
        for (int index = slotHitboxes.size() - 1; index >= 0; index--) {
            SlotHitbox candidate = slotHitboxes.get(index);
            if (candidate.contains(mouseX, mouseY)) {
                hovered = candidate;
                break;
            }
        }
        if (hovered == null) {
            return;
        }

        WikiItemSlot.Frame frame = hovered.frame();
        List<MutableComponent> logicalLines = buildTooltipLines(frame);
        if (logicalLines.isEmpty()) {
            return;
        }

        List<FormattedCharSequence> renderedLines = new ArrayList<>();
        for (MutableComponent line : logicalLines) {
            renderedLines.addAll(font.split(line, TOOLTIP_MAX_WIDTH));
        }
        if (renderedLines.isEmpty()) {
            return;
        }

        int tooltipWidth = 0;
        for (FormattedCharSequence line : renderedLines) {
            tooltipWidth = Math.max(tooltipWidth, font.width(line));
        }
        tooltipWidth += 12;
        int tooltipHeight = renderedLines.size() * LINE_HEIGHT + 10;
        if (renderedLines.size() > 1) {
            tooltipHeight += 2;
        }

        int tooltipX = mouseX + 14;
        int tooltipY = mouseY + 10;
        if (tooltipX + tooltipWidth > width - 5) {
            tooltipX = mouseX - tooltipWidth - 10;
        }
        if (tooltipY + tooltipHeight > height - 5) {
            tooltipY = height - tooltipHeight - 5;
        }
        tooltipX = Math.max(5, tooltipX);
        tooltipY = Math.max(5, tooltipY);

        graphics.nextStratum();
        graphics.fill(tooltipX - 2, tooltipY - 2,
                tooltipX + tooltipWidth + 2, tooltipY + tooltipHeight + 2,
                TOOLTIP_BORDER_OUTER);
        graphics.fill(tooltipX - 1, tooltipY - 1,
                tooltipX + tooltipWidth + 1, tooltipY + tooltipHeight + 1,
                TOOLTIP_BORDER_INNER);
        graphics.fill(tooltipX, tooltipY,
                tooltipX + tooltipWidth, tooltipY + tooltipHeight,
                TOOLTIP_BACKGROUND);

        int lineY = tooltipY + 5;
        for (int index = 0; index < renderedLines.size(); index++) {
            graphics.text(font, renderedLines.get(index), tooltipX + 6, lineY,
                    index == 0 ? 0xFFFFFFFF : TOOLTIP_TEXT, false);
            lineY += LINE_HEIGHT;
            if (index == 0 && renderedLines.size() > 1) {
                lineY += 2;
            }
        }
    }

    protected List<MutableComponent> buildTooltipLines(WikiItemSlot.Frame frame) {
        List<MutableComponent> result = new ArrayList<>();
        String title = frame.tooltipTitle().isBlank() ? frame.displayName() : frame.tooltipTitle();
        if (!title.isBlank()) {
            result.add(parseLegacyFormatting(title, ChatFormatting.AQUA));
        }

        String body = frame.tooltipText();
        if (!body.isBlank()) {
            for (String line : body.replace("\\n", "\n").split("\n", -1)) {
                result.add(line.isBlank()
                        ? Component.literal(" ")
                        : parseLegacyFormatting(line, ChatFormatting.GRAY));
            }
        }
        return result;
    }

    protected static MutableComponent parseLegacyFormatting(String value, ChatFormatting fallbackColor) {
        MutableComponent result = Component.empty();
        ChatFormatting color = fallbackColor;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        StringBuilder chunk = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character == '&' || character == '\u00A7') && index + 1 < value.length()) {
                ChatFormatting formatting = legacyFormatting(value.charAt(index + 1));
                if (formatting != null) {
                    appendTooltipChunk(result, chunk, color, bold, italic, underlined, strikethrough);
                    if (formatting == ChatFormatting.RESET) {
                        color = fallbackColor;
                        bold = false;
                        italic = false;
                        underlined = false;
                        strikethrough = false;
                    } else if (formatting.isColor()) {
                        color = formatting;
                        bold = false;
                        italic = false;
                        underlined = false;
                        strikethrough = false;
                    } else if (formatting == ChatFormatting.BOLD) {
                        bold = true;
                    } else if (formatting == ChatFormatting.ITALIC) {
                        italic = true;
                    } else if (formatting == ChatFormatting.UNDERLINE) {
                        underlined = true;
                    } else if (formatting == ChatFormatting.STRIKETHROUGH) {
                        strikethrough = true;
                    }
                    index++;
                    continue;
                }
            }
            chunk.append(character);
        }

        appendTooltipChunk(result, chunk, color, bold, italic, underlined, strikethrough);
        return result;
    }

    protected static void appendTooltipChunk(
            MutableComponent result,
            StringBuilder chunk,
            ChatFormatting color,
            boolean bold,
            boolean italic,
            boolean underlined,
            boolean strikethrough
    ) {
        if (chunk.isEmpty()) {
            return;
        }
        MutableComponent part = Component.literal(chunk.toString()).withStyle(color);
        if (bold) part.withStyle(ChatFormatting.BOLD);
        if (italic) part.withStyle(ChatFormatting.ITALIC);
        if (underlined) part.withStyle(ChatFormatting.UNDERLINE);
        if (strikethrough) part.withStyle(ChatFormatting.STRIKETHROUGH);
        result.append(part);
        chunk.setLength(0);
    }

    protected static ChatFormatting legacyFormatting(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> ChatFormatting.BLACK;
            case '1' -> ChatFormatting.DARK_BLUE;
            case '2' -> ChatFormatting.DARK_GREEN;
            case '3' -> ChatFormatting.DARK_AQUA;
            case '4' -> ChatFormatting.DARK_RED;
            case '5' -> ChatFormatting.DARK_PURPLE;
            case '6' -> ChatFormatting.GOLD;
            case '7' -> ChatFormatting.GRAY;
            case '8' -> ChatFormatting.DARK_GRAY;
            case '9' -> ChatFormatting.BLUE;
            case 'a' -> ChatFormatting.GREEN;
            case 'b' -> ChatFormatting.AQUA;
            case 'c' -> ChatFormatting.RED;
            case 'd' -> ChatFormatting.LIGHT_PURPLE;
            case 'e' -> ChatFormatting.YELLOW;
            case 'f' -> ChatFormatting.WHITE;
            case 'l' -> ChatFormatting.BOLD;
            case 'm' -> ChatFormatting.STRIKETHROUGH;
            case 'n' -> ChatFormatting.UNDERLINE;
            case 'o' -> ChatFormatting.ITALIC;
            case 'r' -> ChatFormatting.RESET;
            default -> null;
        };
    }

}
