package com.nico.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class HudLayoutManager {
    public static final String MINION_OUTPUT = "minion_output";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, HudElement> elements = new LinkedHashMap<>();
    private final Path configPath;

    public HudLayoutManager() {
        this.configPath = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nicos_super_mods")
                .resolve("hud_layout.json");

        registerDefaults();
        load();
    }

    private void registerDefaults() {
        register(new HudElement(
                MINION_OUTPUT,
                "Minion Output",
                12,
                12
        ));
    }

    private void register(HudElement element) {
        elements.put(element.getId(), element);
    }

    public HudElement get(String id) {
        return elements.get(id);
    }

    public Collection<HudElement> getAll() {
        return elements.values();
    }

    public Collection<HudElement> getSeenElements() {
        Collection<HudElement> seen = new ArrayList<>();

        for (HudElement element : elements.values()) {
            if (element.hasBeenSeen()) {
                seen.add(element);
            }
        }

        return seen;
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());

            SavedLayout layout = new SavedLayout();

            for (HudElement element : elements.values()) {
                SavedElement saved = new SavedElement();
                saved.x = element.getX();
                saved.y = element.getY();
                saved.width = element.getWidth();
                saved.height = element.getHeight();
                saved.scale = element.getScale();
                saved.seen = element.hasBeenSeen();

                layout.elements.put(element.getId(), saved);
            }

            Files.write(
                    configPath,
                    GSON.toJson(layout).getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void load() {
        if (!Files.exists(configPath)) {
            save();
            return;
        }

        try {
            String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
            SavedLayout layout = GSON.fromJson(json, SavedLayout.class);

            if (layout == null || layout.elements == null) return;

            for (Map.Entry<String, SavedElement> entry : layout.elements.entrySet()) {
                HudElement element = elements.get(entry.getKey());
                SavedElement saved = entry.getValue();

                if (element == null || saved == null) continue;

                element.setPosition(saved.x, saved.y);
                element.setMeasuredSize(saved.width, saved.height);
                element.setScale(saved.scale);
                element.setSeen(saved.seen);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final class SavedLayout {
        private Map<String, SavedElement> elements = new LinkedHashMap<>();
    }

    private static final class SavedElement {
        private int x;
        private int y;
        private int width;
        private int height;
        private double scale = 1.0D;
        private boolean seen = false;
    }
}
