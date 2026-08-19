package com.nico.client.bloodrush;

public enum RouteKey {
    HEALER("Healer", "healer"),

    MAGE("Mage", "mage"),

    ARCHER("Archer", "archer");

    private final String displayName;
    private final String fileName;

    RouteKey(String displayName, String fileName) {
        this.displayName = displayName;
        this.fileName = fileName;
    }

    public String displayName() {
        return displayName;
    }

    public String fileName() {
        return fileName;
    }

    public int red() {
        return switch (this) {
            case HEALER -> 80;
            case MAGE -> 80;
            case ARCHER -> 255;
        };
    }

    public int green() {
        return switch (this) {
            case HEALER -> 255;
            case MAGE -> 200;
            case ARCHER -> 190;
        };
    }

    public int blue() {
        return switch (this) {
            case HEALER -> 120;
            case MAGE -> 255;
            case ARCHER -> 60;
        };
    }
}
