package com.nico.client.bloodrush;

public final class BloodRoutes {

    private static final boolean RENDER_ROUTES = false; // TEMP

    private BloodRoutes() { }

    public static RouteEditor initialize(RouteContextProvider context) {
        RouteRepository repository = new RouteRepository();
        BloodRushState bloodRushState = new BloodRushState();
        bloodRushState.register(context::isInDungeon);

        RouteEditor editor = new RouteEditor(context, repository, bloodRushState);
        if (RENDER_ROUTES) RouteRenderer.register(editor);
        return editor;
    }

}
