package com.aquarius.proxybridge;

import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.feature.WaypointStore;
import com.aquarius.proxybridge.net.BridgeNetworking;
import com.aquarius.proxybridge.net.BridgePayload;
import com.aquarius.proxybridge.render.WaypointRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
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
                .then(literal("pull").executes(c -> {
                    sendProxyCommand(config.pullCommand);
                    c.getSource().sendFeedback(Component.literal("Sent: /" + config.pullCommand));
                    return 1;
                }))
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
                }))));
            dispatcher.register(literal("pb").redirect(root));
        });
    }
}
