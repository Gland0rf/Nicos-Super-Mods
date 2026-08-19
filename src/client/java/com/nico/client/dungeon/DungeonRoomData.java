package com.nico.client.dungeon;

import java.util.List;

public record DungeonRoomData (
        String name,
        Type type,
        Shape shape,
        List<Integer> cores
) {

    public enum Type {
        ENTRANCE,
        FAIRY,
        NORMAL,
        RARE,
        BLOOD,
        CHAMPION,
        UNKNOWN,
        PUZZLE,
        TRAP,
        UNDISCOVERED
    }

    public enum Shape {
        L,
        ONE_BY_ONE,
        ONE_BY_TWO,
        ONE_BY_THREE,
        ONE_BY_FOUR,
        TWO_BY_TWO;

        public static Shape fromJson(String value) {
            return switch (value) {
                case "L" -> L;
                case "1x1", "ONE_BY_ONE" -> ONE_BY_ONE;
                case "1x2", "ONE_BY_TWO" -> ONE_BY_TWO;
                case "1x3", "ONE_BY_THREE" -> ONE_BY_THREE;
                case "1x4", "ONE_BY_FOUR" -> ONE_BY_FOUR;
                case "2x2", "TWO_BY_TWO" -> TWO_BY_TWO;

                default -> throw new IllegalArgumentException(
                        "Unknown dungeon room shape: " + value
                );
            };
        }
    }
}
