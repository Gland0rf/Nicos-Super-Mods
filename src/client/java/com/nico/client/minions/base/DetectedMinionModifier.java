package com.nico.client.minions.base;

public final class DetectedMinionModifier {
    private final String displayName;
    private final MinionModifier modifier;
    private final String note;

    public DetectedMinionModifier(String displayName, MinionModifier modifier, String note) {
        this.displayName = displayName;
        this.modifier = modifier;
        this.note = note;
    }

    public static DetectedMinionModifier modeled(String displayName, MinionModifier modifier) {
        return new DetectedMinionModifier(displayName, modifier, "Modeled");
    }

    public static DetectedMinionModifier unsupported(String displayName, String note) {
        return new DetectedMinionModifier(displayName, null, note);
    }

    public String getDisplayName() {
        return displayName;
    }

    public MinionModifier getModifier() {
        return modifier;
    }

    public String getNote() {
        return note;
    }

    public boolean isModeled() {
        return modifier != null;
    }
}