package com.nico.client.configuration.category;

import com.nico.client.configuration.FeatureId;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class CategoryPerformance {
    @FeatureId(value = "lagMonitor", legacyNames = {"dungeonLagMonitor"})
    @ConfigOption(name = "Lag Monitor", desc = "Tracks TPS, ping, jitter, and connection stalls with a customizable HUD.")
    @Accordion
    public FeatureOptions.LagMonitor lagMonitor = new FeatureOptions.LagMonitor();

     @FeatureId(value = "memLeak")
    @ConfigOption(name = "Memory Leak Detector", desc = "Detects sustained heap growth and identify likely mod allocation sources.")
    @Accordion
    public FeatureOptions.MemLeak memLeak = new FeatureOptions.MemLeak();
}
