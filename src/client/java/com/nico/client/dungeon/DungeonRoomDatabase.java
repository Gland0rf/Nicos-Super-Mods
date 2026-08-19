package com.nico.client.dungeon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DungeonRoomDatabase {

    private static final String RESOURCE = "/dungeon/rooms.json";

    private static Map<Integer, DungeonRoomData> roomsByCore = new HashMap<>();

    private static final DungeonRoomDatabase INSTANCE = new DungeonRoomDatabase();

    private DungeonRoomDatabase() {
        load();
    }

    public static DungeonRoomDatabase getInstance() {
        return INSTANCE;
    }

    public DungeonRoomData findByCore(int core) {
        return roomsByCore.get(core);
    }

    private void load() {
        InputStream stream = DungeonRoomDatabase.class.getResourceAsStream(RESOURCE);

        if (stream == null) {
            throw new IllegalStateException(
                    "Could not find dungeon room database: " + RESOURCE
            );
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonArray rooms = JsonParser.parseReader(reader).getAsJsonArray();

            for (JsonElement element : rooms) {
                JsonObject json = element.getAsJsonObject();

                String name = json.get("name").getAsString();

                DungeonRoomData.Type type = DungeonRoomData.Type.valueOf(json.get("type").getAsString());
                DungeonRoomData.Shape shape = DungeonRoomData.Shape.fromJson(json.get("shape").getAsString());

                List<Integer> cores = new ArrayList<>();

                for (JsonElement core : json.getAsJsonArray("cores")) {
                    cores.add(core.getAsInt());
                }

                DungeonRoomData data = new DungeonRoomData(name, type, shape, cores);

                for (int core : cores) {
                    roomsByCore.put(core, data);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to load dungeon room database,",
                    exception
            );
        }
    }

}
