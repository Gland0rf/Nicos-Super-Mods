package com.nico.client.configuration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class NsmModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory getModConfigScreenFactory() {
        return NsmConfigManager::createScreen;
    }
}