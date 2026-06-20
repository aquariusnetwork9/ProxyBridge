package com.aquarius.proxybridge;

import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.feature.WaypointStore;
import com.aquarius.proxybridge.net.BridgeNetworking;
import com.aquarius.proxybridge.net.BridgePayload;
import com.aquarius.proxybridge.render.WaypointRenderer;
import com.aquarius.proxybridge.web.CommandResponse;
import com.aquarius.proxybridge.web.WebAPI;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * ProxyBridge — a Fabric client mod that pairs with AquariusProxy/ZenithProxy. It receives live waypoints from the
 * proxy over the {@code proxybridge:main} plugin channel and renders them, and sends control commands back up to the
 * proxy (swap, pearl pull, arbitrary commands) via unsigned chat commands.
 *
 * <p>Based on rfresh2/ZenithProxyMod (1.21.4).
 */
public class ProxyBridgeMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("ProxyBridge");
    public static Config config;

    /** Pull-your-pearl keybind (default unbound). Fires an instant bridge pull when connected through the proxy. */
    private static KeyMapping pullKey;

    /** True if the client is connected through an Aquarius/Zenith proxy (best-effort, via the server brand). */
    public static boolean onProxyServer() {
        var mc = Minecraft.getInstance();
        var connection = mc.getConnection();
        if (connection == null) return false;
        String brand = connection.serverBrand();
        if (brand == null) {
            var server = mc.getCurrentServer();
            if (server == null || server.version == null) return false;
            brand = server.version.tryCollapseToString();
            if (brand == null) return false;
        }
        return brand.contains("AquariusProxy") || brand.contains("ZenithProxy");
    }

    /** Sends an unsigned command to the proxy (parsed by the proxy's in-game command handler). */
    public static void sendProxyCommand(String command) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.connection.sendUnsignedCommand(command);
    }

    @Override
    public void onInitializeClient() {
        config = Config.load();
        BridgeNetworking.init();
        WaypointRenderer.init();
        com.aquarius.proxybridge.xaero.XaeroWaypointSync.init();
        com.aquarius.proxybridge.feature.WhisperInterceptor.init();
        com.aquarius.proxybridge.feature.PositionReporter.init();
        registerCommands();
        registerKeybinds();
        LOG.info("ProxyBridge initialized");
    }

    private void registerKeybinds() {
        pullKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.proxybridge.pull", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.proxybridge"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pullKey.consumeClick()) onPullKey(client);
        });
    }

    /** Instant default-pearl pull: the plugin channel when connected through the proxy, else the default bot's HTTP API. */
    private static void onPullKey(Minecraft client) {
        if (config == null) return;
        if (config.bridgePull && BridgeNetworking.sendPearlPull("")) {
            chat(client, "⚡ Pulling your pearl over the bridge…");
            return;
        }
        Config.PearlBot bot = defaultBot();
        if (bot != null && hasApi(bot)) {
            chat(client, "⚡ Pulling your pearl from " + bot.id + "…");
            runRemoteToChat(bot, remotePull(bot, null));
            return;
        }
        chat(client, "No bot to pull from — add one with /pb bots add <id> <url> <token>, then bind a key (Controls → ProxyBridge).");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = dispatcher.register(literal("proxybridge")
                .then(literal("status").executes(c -> {
                    boolean connected = ClientPlayNetworking.canSend(BridgePayload.TYPE);
                    c.getSource().sendFeedback(Component.literal(
                        "ProxyBridge — proxy: " + onProxyServer()
                            + ", channel: " + (connected ? "ready" : "not ready")
                            + ", waypoints: " + WaypointStore.INSTANCE.count()));
                    return 1;
                }))
                .then(literal("swap").executes(c -> {
                    sendProxyCommand(config.swapCommand);
                    c.getSource().sendFeedback(Component.literal("Sent: /" + config.swapCommand));
                    return 1;
                }))
                .then(literal("pull")
                    // no bot id: pull my pearl from my default bot, best transport (channel if connected, else HTTP)
                    .executes(c -> pullMyPearl(c.getSource(), defaultBot(), null))
                    .then(argument("bot", word()).executes(c -> {
                        // pull my pearl from one specific bot I have a token for (out in the world → over its HTTP API)
                        String botId = getString(c, "bot");
                        Config.PearlBot bot = findBot(botId);
                        if (bot == null) {
                            c.getSource().sendFeedback(Component.literal("No bot '" + botId + "' (try /pb bots list)"));
                            return 0;
                        }
                        return pullMyPearl(c.getSource(), bot, null);
                    })))
                .then(literal("bots")
                    .then(literal("list").executes(c -> {
                        if (config.bots.isEmpty()) {
                            c.getSource().sendFeedback(Component.literal("No bots configured. /pb bots add <id> <url> <token>"));
                        } else {
                            c.getSource().sendFeedback(Component.literal("Pearl bots:"));
                            for (Config.PearlBot b : config.bots) {
                                String ign = (b.ign == null || b.ign.isBlank()) ? "" : "  (whisper: " + b.ign + ")";
                                c.getSource().sendFeedback(Component.literal(" " + b.id + " -> " + b.url + ign));
                            }
                        }
                        return 1;
                    }))
                    .then(literal("add").then(argument("id", word()).then(argument("url", string()).then(argument("token", string()).executes(c -> {
                        String id = getString(c, "id");
                        config.bots.removeIf(b -> b.id.equalsIgnoreCase(id));
                        Config.PearlBot b = new Config.PearlBot();
                        b.id = id;
                        b.url = getString(c, "url");
                        b.token = getString(c, "token");
                        config.bots.add(b);
                        config.save();
                        c.getSource().sendFeedback(Component.literal("Added bot " + id));
                        return 1;
                    })))))
                    .then(literal("del").then(argument("id", word()).executes(c -> {
                        String id = getString(c, "id");
                        boolean removed = config.bots.removeIf(b -> b.id.equalsIgnoreCase(id));
                        config.save();
                        c.getSource().sendFeedback(Component.literal(removed ? "Removed bot " + id : "No bot " + id));
                        return 1;
                    })))
                    .then(literal("default").then(argument("id", word()).executes(c -> {
                        Config.PearlBot b = findBot(getString(c, "id"));
                        if (b == null) {
                            c.getSource().sendFeedback(Component.literal("No bot " + getString(c, "id")));
                            return 0;
                        }
                        config.defaultBotId = b.id;
                        config.save();
                        c.getSource().sendFeedback(Component.literal("Default pull bot = " + b.id
                            + " (used by /pb pull and the pull keybind)"));
                        return 1;
                    })))
                    .then(literal("ign").then(argument("id", word()).then(argument("ign", word()).executes(c -> {
                        Config.PearlBot b = findBot(getString(c, "id"));
                        if (b == null) {
                            c.getSource().sendFeedback(Component.literal("No bot " + getString(c, "id")));
                            return 0;
                        }
                        b.ign = getString(c, "ign");
                        config.save();
                        c.getSource().sendFeedback(Component.literal("Whisper IGN for " + b.id + " = " + b.ign
                            + " (now: /w " + b.ign + " <command> reroutes to its API)"));
                        return 1;
                    }))))
                    .then(literal("pearlid").then(argument("id", word()).then(argument("pearlId", string()).executes(c -> {
                        Config.PearlBot b = findBot(getString(c, "id"));
                        if (b == null) {
                            c.getSource().sendFeedback(Component.literal("No bot " + getString(c, "id")));
                            return 0;
                        }
                        b.pearlId = getString(c, "pearlId");
                        config.save();
                        c.getSource().sendFeedback(Component.literal("Pearl id for " + b.id + " = " + b.pearlId));
                        return 1;
                    }))))
                    .then(literal("cmd").then(argument("id", word()).then(argument("command", greedyString()).executes(c -> {
                        Config.PearlBot b = findBot(getString(c, "id"));
                        if (b == null) {
                            c.getSource().sendFeedback(Component.literal("No bot " + getString(c, "id")));
                            return 0;
                        }
                        runRemote(c.getSource(), b, getString(c, "command"));
                        return 1;
                    })))))
                .then(literal("cmd").then(argument("command", greedyString()).executes(c -> {
                    String command = getString(c, "command");
                    sendProxyCommand(command);
                    c.getSource().sendFeedback(Component.literal("Sent: /" + command));
                    return 1;
                })))
                .then(literal("goto")
                    .then(argument("x", integer())
                    .then(argument("y", integer())
                    .then(argument("z", integer()).executes(c -> gotoSecure(c.getSource(),
                        getInteger(c, "x"), getInteger(c, "y"), getInteger(c, "z")))))))
                .then(literal("hud").then(argument("enabled", bool()).executes(c -> {
                    config.renderHud = getBool(c, "enabled");
                    config.save();
                    c.getSource().sendFeedback(Component.literal("HUD list: " + config.renderHud));
                    return 1;
                })))
                .then(literal("boxes").then(argument("enabled", bool()).executes(c -> {
                    config.renderWorldBoxes = getBool(c, "enabled");
                    config.save();
                    c.getSource().sendFeedback(Component.literal("World boxes: " + config.renderWorldBoxes));
                    return 1;
                })))
                .then(literal("xaero").then(argument("enabled", bool()).executes(c -> {
                    config.useXaero = getBool(c, "enabled");
                    config.save();
                    c.getSource().sendFeedback(Component.literal("Xaero's Minimap waypoints: " + config.useXaero));
                    return 1;
                })))
                .then(literal("whisper").then(argument("enabled", bool()).executes(c -> {
                    config.interceptWhispers = getBool(c, "enabled");
                    config.save();
                    c.getSource().sendFeedback(Component.literal("Whisper intercept (muted-safe API reroute): "
                        + config.interceptWhispers + " — set a bot's match name with /pb bots ign <id> <ign>"));
                    return 1;
                })))
                .then(literal("bridgepull").then(argument("enabled", bool()).executes(c -> {
                    config.bridgePull = getBool(c, "enabled");
                    config.save();
                    c.getSource().sendFeedback(Component.literal("Bridge pearl pulls (instant, over the proxy channel): "
                        + config.bridgePull + " — covers /pb pull, the pull keybind, and the /w <bot> load fast-path"));
                    return 1;
                })))
                .then(literal("reportpos")
                    .then(argument("enabled", bool()).executes(c -> {
                        config.reportPosition = getBool(c, "enabled");
                        config.save();
                        Config.PearlBot b = defaultBot();
                        String tail = !config.reportPosition ? ""
                            : (b == null ? " — but no default bot is set (/pb bots add <id> <url> <token>, then /pb bots default <id>)"
                                         : " → " + b.id + " every " + config.reportPositionIntervalTicks + " ticks");
                        c.getSource().sendFeedback(Component.literal(
                            "Position reporting (POST /position so the bot can come/follow you out of range): "
                                + config.reportPosition + tail));
                        return 1;
                    }))
                    .then(literal("interval").then(argument("ticks", integer(1)).executes(c -> {
                        config.reportPositionIntervalTicks = getInteger(c, "ticks");
                        config.save();
                        c.getSource().sendFeedback(Component.literal("Position report interval = "
                            + config.reportPositionIntervalTicks + " ticks (~"
                            + String.format("%.1f", config.reportPositionIntervalTicks / 20.0) + "s)"));
                        return 1;
                    }))))
                .then(literal("admin")
                    .executes(c -> openAdmin(c.getSource(), null))
                    .then(argument("bot", word()).executes(c -> openAdmin(c.getSource(), getString(c, "bot"))))));
            dispatcher.register(literal("pb").redirect(root));
        });
    }

    private static Config.PearlBot findBot(String id) {
        for (Config.PearlBot b : config.bots) {
            if (b.id.equalsIgnoreCase(id)) return b;
        }
        return null;
    }

    /** Open the in-game RBAC admin screen for a registered bot (needs an admin token for that bot). */
    private static int openAdmin(FabricClientCommandSource source, String botId) {
        Config.PearlBot bot;
        if (botId == null) {
            if (config.bots.size() == 1) {
                bot = config.bots.get(0);
            } else {
                source.sendFeedback(Component.literal("Specify a bot: /pb admin <id> (you have "
                    + config.bots.size() + " — see /pb bots list)"));
                return 0;
            }
        } else {
            bot = findBot(botId);
            if (bot == null) {
                source.sendFeedback(Component.literal("No bot '" + botId + "' (try /pb bots list)"));
                return 0;
            }
        }
        final Config.PearlBot fbot = bot;
        // defer so this runs after the client closes the command chat screen
        Minecraft.getInstance().execute(() ->
            Minecraft.getInstance().setScreen(new com.aquarius.proxybridge.client.RbacAdminScreen(fbot)));
        return 1;
    }

    /**
     * Pull MY pearl using the best available transport: the instant {@code proxybridge:main} plugin channel when I'm
     * connected through the proxy (mostly the owner), otherwise the bot's HTTP API — {@code pearlpull}, which is
     * self-scoped and gated by my token's {@code pearl.pull}, so it works for a player out in the world with just an
     * IP + token. The channel attempt is a fast no-op when not connected through the proxy.
     */
    private static int pullMyPearl(FabricClientCommandSource source, Config.PearlBot bot, String pearlId) {
        if (config.bridgePull && BridgeNetworking.sendPearlPull(pearlId == null ? "" : pearlId)) {
            source.sendFeedback(Component.literal("⚡ Pulling your pearl over the bridge…"));
            return 1;
        }
        if (bot != null && hasApi(bot)) {
            source.sendFeedback(Component.literal("⚡ Pulling your pearl from " + bot.id + "…"));
            runRemote(source, bot, remotePull(bot, pearlId));
            return 1;
        }
        source.sendFeedback(Component.literal(
            "No bot to pull from — register one with /pb bots add <id> <url> <token> (ask the bot admin for the IP + token)."));
        return 0;
    }

    /** The HTTP pull command for a bot: self-scoped {@code pearlpull [pearlId]} (blank = your default pearl). */
    private static String remotePull(Config.PearlBot bot, String pearlId) {
        String id = (pearlId != null && !pearlId.isBlank()) ? pearlId
            : (bot != null && bot.pearlId != null && !bot.pearlId.isBlank() ? bot.pearlId : "");
        return id.isBlank() ? "pearlpull" : "pearlpull " + id;
    }

    /** Target for {@code /pb pull} (no id) + the keybind: the configured default bot, or the sole registered bot. */
    public static Config.PearlBot defaultBot() {
        if (config.bots.isEmpty()) return null;
        if (config.defaultBotId != null && !config.defaultBotId.isBlank()) {
            Config.PearlBot b = findBot(config.defaultBotId);
            if (b != null) return b;
        }
        return config.bots.size() == 1 ? config.bots.get(0) : null;
    }

    private static boolean hasApi(Config.PearlBot bot) {
        return bot != null && bot.url != null && !bot.url.isBlank() && bot.token != null && !bot.token.isBlank();
    }

    /**
     * Send the bot to coordinates over its HTTP API <b>only</b> ({@code wc goto x y z}) — the coordinates are NEVER
     * written to Minecraft chat (public, whisper, or otherwise). Requires a default bot with a url + token; if none is
     * configured this refuses rather than falling back to chat, so a base location can never leak.
     */
    private static int gotoSecure(FabricClientCommandSource source, int x, int y, int z) {
        Config.PearlBot bot = defaultBot();
        if (bot == null || !hasApi(bot)) {
            source.sendError(Component.literal(
                "goto needs a bot with an HTTP API token — coordinates are NEVER sent over chat. "
                    + "Register one with /pb bots add <id> <url> <token>, then /pb bots default <id>."));
            return 0;
        }
        source.sendFeedback(Component.literal("Sending goto to " + bot.id + " over its API (off-chat)…"));
        runRemote(source, bot, "wc goto " + x + " " + y + " " + z);
        return 1;
    }

    private static void chat(Minecraft client, String msg) {
        if (client.player != null) client.player.displayClientMessage(Component.literal("[ProxyBridge] " + msg), true);
    }

    /** Fire a command at a remote bot's HTTP API off-thread, then report the result back in chat. */
    private static void runRemote(FabricClientCommandSource source, Config.PearlBot bot, String command) {
        ForkJoinPool.commonPool().execute(() -> {
            final String msg = runRemoteSync(bot, command);
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(msg)));
        });
    }

    /**
     * Fire a command at a remote bot's HTTP API off-thread, reporting the result to the local chat HUD. Used where
     * there's no command source — notably the whisper-intercept ({@link com.aquarius.proxybridge.feature.WhisperInterceptor}).
     */
    public static void runRemoteToChat(Config.PearlBot bot, String command) {
        ForkJoinPool.commonPool().execute(() -> {
            final String msg = runRemoteSync(bot, command);
            Minecraft.getInstance().execute(() -> {
                var p = Minecraft.getInstance().player;
                if (p != null) p.displayClientMessage(Component.literal(msg), false);
            });
        });
    }

    private static String runRemoteSync(Config.PearlBot bot, String command) {
        try {
            CommandResponse resp = WebAPI.INSTANCE.execute(command, bot.url, bot.token);
            return summarize(bot, resp);
        } catch (Exception e) {
            return bot.id + ": error — " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static String summarize(Config.PearlBot bot, CommandResponse resp) {
        if (resp == null) return bot.id + ": no response";
        if (resp.multiLineOutput() != null && !resp.multiLineOutput().isEmpty()) {
            return bot.id + ": " + String.join(" | ", resp.multiLineOutput());
        }
        if (resp.embed() != null && !resp.embed().isBlank()) return bot.id + ": " + resp.embed();
        return bot.id + ": ok";
    }
}
