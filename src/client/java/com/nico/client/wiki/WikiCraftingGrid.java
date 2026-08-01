package com.nico.client.wiki;

import java.util.ArrayList;
import java.util.List;

public record WikiCraftingGrid(List<WikiItemSlot> inputs, WikiItemSlot output, boolean shapeless, boolean fixed) {
    public static final int INPUT_SLOT_COUNT = 9;

    public WikiCraftingGrid {
        List<WikiItemSlot> normalized = new ArrayList<>(INPUT_SLOT_COUNT);
        if (inputs != null) {
            for (WikiItemSlot input : inputs) {
                if (normalized.size() >= INPUT_SLOT_COUNT) {
                    break;
                }
                normalized.add(input == null ? WikiItemSlot.empty() : input);
            }
        }
        while (normalized.size() < INPUT_SLOT_COUNT) {
            normalized.add(WikiItemSlot.empty());
        }
        inputs = List.copyOf(normalized);
        output = output == null ? WikiItemSlot.empty() : output;
    }

    public static WikiCraftingGrid empty() {
        return new WikiCraftingGrid(List.of(), WikiItemSlot.empty(), false, false);
    }

    public boolean isEmpty() {
        if (!output.isEmpty()) {
            return false;
        }
        return inputs.stream().allMatch(WikiItemSlot::isEmpty);
    }

    public WikiItemSlot input(int row, int column) {
        if (row < 0 || row >= 3 || column < 0 || column >= 3) {
            throw new IndexOutOfBoundsException("Crafting position must be inside a 3x3 grid");
        }
        return inputs.get(row * 3 + column);
    }
}