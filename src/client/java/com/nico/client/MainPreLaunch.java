package com.nico.client;

import com.nico.client.modcheck.ModCheckRuntime;
import com.nico.client.modcheck.config.ModCheckConfigReader;
import com.nico.client.modcheck.config.ModCheckSettings;
import com.nico.client.modcheck.registry.RegistryFetchResult;
import com.nico.client.modcheck.registry.RemoteTrustRegistry;
import com.nico.client.modcheck.scan.ModScanner;
import com.nico.client.modcheck.scan.ScanReport;
import com.nico.client.modcheck.util.ReportWriter;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class MainPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("[NSM ModCheck]");

    @Override
    public void onPreLaunch() {
        ModCheckSettings settings = ModCheckConfigReader.load();

        ModCheckRuntime.setSettings(settings);

        if (!settings.enabled()) {
            System.out.println(
                    "[NSM ModCheck] Startup scan disabled "
                            + "by configuration."
            );
        } else {

            Path gameDirectory = FabricLoader.getInstance().getGameDir();
            Path modsDirectory = resolveModsDirectory(gameDirectory);

            RegistryFetchResult registry = null;
            Exception registryFailure = null;
            try {
                registry = new RemoteTrustRegistry().fetch();
                LOGGER.info(
                        "Verified NSM ModCheck registry: {} projects, {} release hashes",
                        registry.registry().projectCount(),
                        registry.registry().releaseCount()
                );
            } catch (Exception e) {
                registryFailure = e;
                LOGGER.error("Could not fetch or verify the NSM ModCheck registry", e);
            }

            ScanReport report = new ModScanner().scan(
                    gameDirectory,
                    modsDirectory,
                    registry,
                    registryFailure
            );
            ModCheckRuntime.setReport(report);

            try {
                Path reportPath = ReportWriter.write(gameDirectory, report);
                LOGGER.info("Report written to {}", reportPath);
            } catch (Exception e) {
                LOGGER.error("Could not write report to file", e);
            }

            if (report.criticalCount() > 0) {
                LOGGER.error("Found {} critical verification issue(s)", report.criticalCount());
            }
            if (report.warningCount() > 0) {
                LOGGER.error("Found {} verification warning(s)", report.warningCount());
            }
            LOGGER.info("Scanned all mod JARs");
        }
    }

    private static Path resolveModsDirectory(Path gameDirectory) {
        String configured = System.getProperty("fabric.modsFolder");
        if (configured == null || configured.isBlank()) {
            return gameDirectory.resolve("mods").normalize();
        }

        Path configuredPath = Path.of(configured);
        return configuredPath.isAbsolute()
                ? configuredPath.normalize()
                : gameDirectory.resolve(configuredPath).normalize();
    }
}
