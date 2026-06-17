package com.aquarius.proxybridge;

import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.feature.WaypointStore;
import com.aquarius.proxybridge.net.BridgeNetworking;
import com.aquarius.proxybridge.net.BridgePayload;
import com.aquarius.proxybridge.render.WaypointRenderer;
import com.aquarius.proxybridge.web.CommandResponse;
import com.aquarius.proxybridge.web.WebAPI;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
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
        registerCommands();
        LOG.info("ProxyBridge initialized");
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
                    .executes(c -> {
                        // no bot id: pull from the proxy we're connected to
                        String cmd = (config.pullCommand == null || config.pullCommand.isBlank())
                            ? pullCommandFor(null) : config.pullCommand;
                        sendProxyCommand(cmd);
                        c.getSource().sendFeedback(Component.literal("Sent: /" + cmd));
                        return 1;
                    })
                    .then(argument("bot", word()).executes(c -> {
                        // pull from one specific remote bot we have access to
                        String botId = getString(c, "bot");
                        Config.PearlBot bot = findBot(botId);
                        if (bot == null) {
                            c.getSource().sendFeedback(Component.literal("No bot '" + botId + "' (try /pb bots list)"));
                            return 0;
                        }
                        String command = pullCommandFor(bot);
                        c.getSource().sendFeedback(Component.literal("Pulling your pearl from " + bot.id + "…"));
                        runRemote(c.getSource(), bot, command);
                        return 1;
                    })))
                .then(literal("bots")
                    .then(literal("list").executes(c -> {
                        if (config.bots.isEmpty()) {
                            c.getSource().sendFeedback(Component.literal("No bots configured. /pb bots add <id> <url> <token>"));
                        } else {
                            c.getSource().sendFeedback(Component.literal("Pearl bots:"));
                            for (Config.PearlBot b : config.bots) {
                                c.getSource().sendFeedback(Component.literal(" " + b.id + " -> " + b.url));
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
                }))));
            dispatcher.register(literal("pb").redirect(root));
        });
    }

    private static Config.PearlBot findBot(String id) {
        for (Config.PearlBot b : config.bots) {
            if (b.id.equalsIgnoreCase(id)) return b;
        }
        return null;
    }

    /** Build the load command: {@code pearlplus load <me> <pearlId|me>}. A null bot means a self/local pull. */
    private static String pullCommandFor(Config.PearlBot bot) {
        String me = Minecraft.getInstance().getUser().getName();
        String pearlId = (bot == null || bot.pearlId == null || bot.pearlId.isBlank()) ? me : bot.pearlId;
        return "pearlplus load " + me + " " + pearlId;
    }

    /** Fire a command at a remote bot's HTTP API off-thread, then report the result back in chat. */
    private static void runRemote(FabricClientCommandSource source, Config.PearlBot bot, String command) {
        ForkJoinPool.commonPool().execute(() -> {
            String result;
            try {
                CommandResponse resp = WebAPI.INSTANCE.execute(command, bot.url, bot.token);
                result = summarize(bot, resp);
            } catch (Exception e) {
                result = bot.id + ": error — " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            final String msg = result;
            Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(msg)));
        });
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
