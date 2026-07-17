package com.nico.client.minions.base;

public final class MinionOutputDrop {
    private final String productId;
    private final double amountPerCycle;
    private final boolean affectedByOutputMultiplier;

    public MinionOutputDrop(String productId, double amountPerCycle, boolean affectedByOutputMultiplier) {
        if (productId == null || productId.trim().isEmpty()) {
            throw new IllegalArgumentException("productId cannot be null or empty");
        }

        if (amountPerCycle < 0.0D) {
            throw new IllegalArgumentException("amountPerCycle cannot be negative");
        }

        this.productId = productId;
        this.amountPerCycle = amountPerCycle;
        this.affectedByOutputMultiplier = affectedByOutputMultiplier;
    }

    public static MinionOutputDrop base(String productId, double amountPerCycle) {
        return new MinionOutputDrop(productId, amountPerCycle, true);
    }

    public static MinionOutputDrop extra(String productId, double expectedAmountPerCycle) {
        return new MinionOutputDrop(productId, expectedAmountPerCycle, false);
    }

    public String getProductId() {
        return productId;
    }

    public double getAmountPerCycle() {
        return amountPerCycle;
    }

    public boolean isAffectedByOutputMultiplier() {
        return affectedByOutputMultiplier;
    }
}