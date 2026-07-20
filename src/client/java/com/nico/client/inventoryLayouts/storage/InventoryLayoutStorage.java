package com.nico.client.inventoryLayouts.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InventoryLayoutStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("nsm-inventory-layouts.json");

    private final List<InventoryLayout> layouts = new ArrayList<>();

    public synchronized void load() {
        layouts.clear();

        if (!Files.exists(PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(PATH)) {
            StoredLayouts stored = GSON.fromJson(reader, StoredLayouts.class);
            if (stored == null || stored.layouts == null) return;

            for (InventoryLayout layout : stored.layouts) {
                if (layout == null) continue;
                layout.sanitize();
                layouts.add(layout);
            }

            sortLayouts();
        } catch (IOException | RuntimeException e) {
            System.err.println("[NSM Inventory Layouts] Could not load layouts: " + e.getMessage());
        }
    }

    public synchronized List<InventoryLayout> getLayouts() {
        return List.copyOf(layouts);
    }

    public synchronized InventoryLayout findByName(String name) {
        if (name == null) {
            return null;
        }

        for (InventoryLayout layout : layouts) {
            if (layout.name().equalsIgnoreCase(name.trim())) {
                return layout;
            }
        }
        return null;
    }

    public synchronized void upsert(InventoryLayout layout) {
        if (layout == null) return;

        layout.sanitize();
        layouts.removeIf(existing -> existing.name().equalsIgnoreCase(layout.name()));
        layouts.add(layout);
        sortLayouts();
        save();
    }

    public synchronized void delete(String name) {
        if (name == null) return;

        if (layouts.removeIf(layout -> layout.name().equalsIgnoreCase(name))) {
            save();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Path temporaryPath = PATH.resolveSibling(PATH.getFileName().toString() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(temporaryPath)) {
                GSON.toJson(new StoredLayouts(layouts), writer);
            }

            try {
                Files.move(
                        temporaryPath,
                        PATH,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[NSM Inventory Layouts] Could not save layouts: " + e.getMessage());
        }
    }

    private void sortLayouts() {
        layouts.sort(Comparator.comparing(InventoryLayout::name, String.CASE_INSENSITIVE_ORDER));
    }

    private static final class StoredLayouts {
        private int version = 1;
        private List<InventoryLayout> layouts = new ArrayList<>();

        private StoredLayouts() { }

        private StoredLayouts(List<InventoryLayout> layouts) {
            this.layouts = new ArrayList<>(layouts);
        }
    }
}
