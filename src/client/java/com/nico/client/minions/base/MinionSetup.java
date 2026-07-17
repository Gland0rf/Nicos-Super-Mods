package com.nico.client.minions.base;

import com.nico.client.utils.BazaarService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MinionSetup {
    private final MinionDefinition definition;
    private final int tier;
    private final List<MinionModifier> modifiers = new ArrayList<>();

    private BazaarService.PriceMode priceMode = BazaarService.PriceMode.INSTANT_SELL;

    public MinionSetup(MinionDefinition definition, int tier) {
        if (definition == null) {
            throw new IllegalArgumentException("definition cannot be null");
        }

        if (tier <= 0) {
            throw new IllegalArgumentException("tier must be greater than zero");
        }

        this.definition = definition;
        this.tier = tier;
    }

    public MinionSetup addModifier(MinionModifier modifier) {
        if (modifier != null) {
            modifiers.add(modifier);
        }

        return this;
    }

    public MinionSetup setPriceMode(BazaarService.PriceMode priceMode) {
        if (priceMode != null) {
            this.priceMode = priceMode;
        }

        return this;
    }

    public MinionDefinition getDefinition() {
        return definition;
    }

    public int getTier() {
        return tier;
    }

    public List<MinionModifier> getModifiers() {
        return Collections.unmodifiableList(modifiers);
    }

    public BazaarService.PriceMode getPriceMode() {
        return priceMode;
    }
}