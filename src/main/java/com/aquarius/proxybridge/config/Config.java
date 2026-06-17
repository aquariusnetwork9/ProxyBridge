package com.aquarius.proxybridge.config;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

import static com.aquarius.proxybridge.ProxyBridgeMod.LOG;

/** Persisted client settings for ProxyBridge (config/proxybridge.json). */
public class Config {

    /** Draw a colored box at each waypoint in the world. */
    public boolean renderWorldBoxes = true;
    /** Draw the on-screen list of waypoints (name + distance + coords). */
    public boolean renderHud = true;
    /** Mirror waypoints into Xaero's Minimap (a "ProxyBridge" set) when that mod is installed. */
    public boolean useXaero = true;
    /** Max waypoints shown in the HUD list. */
    public int maxHudEntries = 12;
    /** Proxy command sent by {@code /proxybridge pull} (the stasis-pull side). */
    public String pullCommand = "pearlplus pull";
    /** Proxy command sent by {@code /proxybridge swap}. */
    public String swapCommand = "swap";

    public static Path configPath = FabricLoader.getInstance().getConfigDir().resolve("proxybridge.json");

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public static Config load() {
        if (!configPath.toFile().exists()) {
            Config c = new Config();
            c.save();
            return c;
        }
        try (var reader = new FileReader(configPath.toFile())) {
            Config c = GSON.fromJson(reader, Config.class);
            return c != null ? c : new Config();
        } catch (Exception e) {
            LOG.error("Failed to load config", e);
            return new Config();
        }
    }

    public void save() {
        try {
            File tempFile = File.createTempFile("proxybridge", null);
            try (var writer = new FileWriter(tempFile)) {
                GSON.toJson(this, writer);
            }
            Files.move(tempFile, configPath.toFile());
        } catch (Exception e) {
            LOG.error("Failed to write config", e);
        }
    }
}
