package com.aquarius.proxybridge.config;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    /**
     * Use the instant {@code proxybridge:main} plugin channel for pearl pulls when connected through the proxy
     * (the proxy resolves your own pearl, gated by its RBAC {@code pearl.pull}). When off, or when you're not
     * connected through the proxy, {@code /pb pull} / the pull keybind / the {@code /w <bot> load} fast-path use the
     * bot's HTTP API instead (the path that works for players out in the world).
     */
    public boolean bridgePull = true;
    /** Proxy command sent by {@code /proxybridge swap}. */
    public String swapCommand = "swap";
    /** Which registered bot {@code /pb pull} (no id) and the pull keybind target when not connected through a proxy.
     *  Blank + exactly one bot registered = that bot. Set with {@code /pb bots default <id>}. */
    public String defaultBotId = "";

    /**
     * Reroute a whispered command to a registered bot over its HTTP API instead of sending it as in-game chat, so a
     * chat-muted player can still drive their bot. Only fires when the whisper target matches a registered bot's IGN
     * (or id); ordinary whispers pass through. See {@code com.aquarius.proxybridge.feature.WhisperInterceptor}.
     */
    public boolean interceptWhispers = true;
    /** Command verbs treated as whispers for the intercept (matched case-insensitively, no leading slash). */
    public final ArrayList<String> whisperVerbs = new ArrayList<>(List.of("w", "msg", "tell", "whisper", "m"));

    /** Remote pearl bots the player has access to (each gated by the owner-supplied token). */
    public final ArrayList<PearlBot> bots = new ArrayList<>();

    /**
     * One remote pearl bot reachable via its HTTP command API.
     * @param id      a short label the player picks, used in {@code /pb pull <id>}
     * @param ign     the bot's in-game name to match whispers against (blank = match the id instead)
     * @param url     host[:port] of the bot's command API (http:// assumed if no scheme)
     * @param token   the Authorization token the bot's owner shared
     * @param pearlId pearl id to load; blank = use the player's own username
     */
    public static final class PearlBot {
        public String id = "";
        public String ign = "";
        public String url = "";
        public String token = "";
        public String pearlId = "";
    }

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
