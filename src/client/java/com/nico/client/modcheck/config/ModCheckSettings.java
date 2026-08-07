package com.nico.client.modcheck.config;

import com.nico.client.configuration.category.CategoryOther;

public record ModCheckSettings (
        boolean enabled,
        boolean showWarningScreen,
        boolean warnAboutUnknownMods
) {
    public static ModCheckSettings defaults() {
        return new ModCheckSettings(
                true,
                true,
                true
        );
    }

    public static ModCheckSettings from(CategoryOther.ModCheck config) {
        if (config == null) return defaults();

        return new ModCheckSettings(
                config.enabled,
                config.showWarningScreen,
                config.warnAboutUnknownMods
        );
    }
}