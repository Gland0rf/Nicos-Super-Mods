package com.nico.client.hud;

public class HudElement {
    private final String id;
    private final String displayName;

    private int x;
    private int y;
    private int width;
    private int height;

    private double scale = 1.0D;
    private boolean seen = false;

    public HudElement(String id, String displayName, int defaultX, int defaultY) {
        this.id = id;
        this.displayName = displayName;
        this.x = defaultX;
        this.y = defaultY;
        this.width = 1;
        this.height = 1;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return Math.max(1, (int) Math.round(width * scale));
    }

    public int getHeight() {
        return Math.max(1, (int) Math.round(height * scale));
    }

    public double getScale() {
        return scale;
    }

    public boolean hasBeenSeen() {
        return seen;
    }

    public void markSeen() {
        this.seen = true;
    }

    public void setSeen(boolean seen) {
        this.seen = seen;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setMeasuredSize(int width, int height) {
        if (width > 0) {
            this.width = width;
        }

        if (height > 0) {
            this.height = height;
        }

        this.seen = true;
    }

    public void setScale(double scale) {
        this.scale = clamp(scale, 0.5D, 2.5D);
    }

    public void resizeByScroll(double scrollY) {
        if (scrollY > 0.0D) {
            setScale(scale + 0.05D);
        } else if (scrollY < 0.0D) {
            setScale(scale - 0.05D);
        }
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x
                && mouseX <= x + width
                && mouseY >= y
                && mouseY <= y + height;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }

        return Math.min(value, max);
    }
}
