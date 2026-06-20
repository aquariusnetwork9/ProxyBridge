package com.aquarius.proxybridge.feature;

import com.aquarius.proxybridge.ProxyBridgeMod;
import com.aquarius.proxybridge.config.Config;
import com.aquarius.proxybridge.web.WebAPI;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aquarius.proxybridge.ProxyBridgeMod.LOG;
import static com.aquarius.proxybridge.ProxyBridgeMod.config;

/**
 * Reports the player's live position to their default bot's HTTP API ({@code POST /position}) so the bot's
 * WhisperControl can {@code come}/{@code follow} the player even when the player is far outside the bot's render
 * distance — the only mechanism that works across separate server sessions (a plugin channel can't).
 *
 * <p>Opt-in ({@code config.reportPosition}, default off — it shares your location with the bot owner). Sends about
 * once per {@code reportPositionIntervalTicks} on a worker thread; in-flight de-duped so a slow/blocked request can't
 * pile up or stall the client.
 */
public final class PositionReporter {
    private static int tickCtr = 0;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    private PositionReporter() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PositionReporter::onTick);
    }

    private static void onTick(Minecraft client) {
        if (config == null || !config.reportPosition) return;
        if (client.player == null) return;
        int interval = Math.max(1, config.reportPositionIntervalTicks);
        if (++tickCtr % interval != 0) return;

        Config.PearlBot bot = ProxyBridgeMod.defaultBot();
        if (bot == null || bot.url == null || bot.url.isBlank() || bot.token == null || bot.token.isBlank()) return;
        if (!inFlight.compareAndSet(false, true)) return;   // a previous send is still running — skip this tick

        final double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        final String dim = client.player.level().dimension().location().toString();
        final String url = bot.url, token = bot.token;
        ForkJoinPool.commonPool().execute(() -> {
            try {
                WebAPI.INSTANCE.reportPosition(x, y, z, dim, url, token);
            } catch (Exception e) {
                LOG.debug("position report failed: {}", e.toString());
            } finally {
                inFlight.set(false);
            }
        });
    }
}
