package com.aquarius.proxybridge.xaero;

import com.aquarius.proxybridge.ProxyBridgeMod;
import com.aquarius.proxybridge.feature.BridgeWaypoint;
import com.aquarius.proxybridge.feature.WaypointStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Optional integration that mirrors bridged waypoints into a dedicated "ProxyBridge" waypoint set in
 * Xaero's Minimap (so they show as real minimap waypoints, not just the built-in box/HUD overlay).
 *
 * <p>Compiled against Xaero's Minimap as a {@code modCompileOnly} soft dependency and fully guarded: it no-ops
 * unless {@code xerominimap} is loaded and {@code config.useXaero} is on, and every Xaero call is wrapped in
 * try/catch so an API mismatch can never break the mod. We only touch the player's <i>current</i> minimap world
 * because the proxy only publishes waypoints for the bot's current dimension.
 */
public final class XaeroWaypointSync {
    private XaeroWaypointSync() {}

    private static final String SET_NAME = "ProxyBridge";
    private static final int SYNC_INTERVAL_TICKS = 20;

    private static boolean available;
    private static int tickCounter;
    private static int lastSyncedVersion = -1;

    public static void init() {
        available = FabricLoader.getInstance().isModLoaded("xaerominimap");
        if (!available) {
            ProxyBridgeMod.LOG.info("Xaero's Minimap not present — minimap waypoint sync disabled (using built-in overlay)");
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(XaeroWaypointSync::onTick);
    }

    private static void onTick(Minecraft mc) {
        if (!available || ProxyBridgeMod.config == null || !ProxyBridgeMod.config.useXaero) return;
        if (mc.level == null || mc.player == null) return;
        if (++tickCounter < SYNC_INTERVAL_TICKS) return;
        tickCounter = 0;
        int v = WaypointStore.INSTANCE.version();
        if (v == lastSyncedVersion) return;
        if (syncNow()) lastSyncedVersion = v;
    }

    /** Replace the ProxyBridge waypoint set in the current minimap world with the current store contents. */
    private static boolean syncNow() {
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) return false;
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null) return false;

            WaypointSet set = world.getWaypointSet(SET_NAME);
            if (set == null) {
                world.addWaypointSet(SET_NAME);
                set = world.getWaypointSet(SET_NAME);
            }
            if (set == null) return false;

            set.clear();
            for (BridgeWaypoint wp : WaypointStore.INSTANCE.all()) {
                set.add(toXaero(wp));
            }
            return true;
        } catch (Throwable t) {
            // never let a Xaero API mismatch break the mod — fall back to the built-in renderer
            ProxyBridgeMod.LOG.warn("Xaero waypoint sync failed; disabling (built-in overlay still works)", t);
            available = false;
            return false;
        }
    }

    private static Waypoint toXaero(BridgeWaypoint wp) {
        String symbol = wp.name().isEmpty() ? "?" : wp.name().substring(0, Math.min(2, wp.name().length()));
        WaypointColor color = WaypointColor.fromIndex(
            Math.floorMod(wp.name().hashCode(), WaypointColor.values().length));
        return new Waypoint(wp.x(), wp.y(), wp.z(), wp.name(), symbol, color, WaypointPurpose.NORMAL);
    }
}
