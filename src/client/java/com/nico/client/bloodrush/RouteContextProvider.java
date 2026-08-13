package com.nico.client.bloodrush;

import java.util.Optional;

public interface RouteContextProvider {
    Optional<RouteLocation> currentRouteLocation();

    DungeonRole currentRole();

    Optional<RouteSetupPosition> currentSetupPosition();

    boolean isInDungeon();
}
