package com.aquarius.proxybridge.render;

import com.aquarius.proxybridge.ProxyBridgeMod;
import com.aquarius.proxybridge.feature.BridgeWaypoint;
import com.aquarius.proxybridge.feature.WaypointStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders bridged waypoints "Xaero's-style": a colored line box at each waypoint in the world, plus an on-screen
 * list (name, distance, coords) sorted by proximity. Only waypoints in the player's current dimension are shown.
 */
public final class WaypointRenderer {
    private WaypointRenderer() {}

    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(WaypointRenderer::renderWorld);
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> renderHud(graphics));
    }

    private static void renderWorld(WorldRenderContext context) {
        if (ProxyBridgeMod.config == null || !ProxyBridgeMod.config.renderWorldBoxes) return;
        WaypointStore store = WaypointStore.INSTANCE;
        if (store.count() == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PoseStack ps = context.matrixStack();
        if (ps == null) return;
        var consumers = context.consumers();
        if (consumers == null) return;

        Vec3 cam = context.camera().getPosition();
        VertexConsumer vc = consumers.getBuffer(RenderType.lines());
        String dim = mc.level.dimension().location().toString();

        for (BridgeWaypoint wp : store.all()) {
            if (!dimensionMatches(dim, wp.dimension())) continue;
            float r = ((wp.color() >> 16) & 0xFF) / 255f;
            float g = ((wp.color() >> 8) & 0xFF) / 255f;
            float b = (wp.color() & 0xFF) / 255f;
            ps.pushPose();
            ps.translate(wp.x() - cam.x, wp.y() - cam.y, wp.z() - cam.z);
            ShapeRenderer.renderLineBox(ps, vc, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, r, g, b, 1f);
            ps.popPose();
        }
    }

    private static void renderHud(GuiGraphics graphics) {
        if (ProxyBridgeMod.config == null || !ProxyBridgeMod.config.renderHud) return;
        WaypointStore store = WaypointStore.INSTANCE;
        if (store.count() == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.level == null) return;

        String dim = mc.level.dimension().location().toString();
        Vec3 p = mc.player.position();
        List<BridgeWaypoint> list = new ArrayList<>();
        for (BridgeWaypoint wp : store.all()) {
            if (dimensionMatches(dim, wp.dimension())) list.add(wp);
        }
        if (list.isEmpty()) return;
        list.sort(Comparator.comparingDouble(wp -> distSq(p, wp)));

        int max = Math.min(list.size(), Math.max(1, ProxyBridgeMod.config.maxHudEntries));
        int y = 5;
        graphics.drawString(mc.font, "ProxyBridge (" + list.size() + ")", 5, y, 0xFF55FFFF);
        y += 11;
        for (int i = 0; i < max; i++) {
            BridgeWaypoint wp = list.get(i);
            int dist = (int) Math.sqrt(distSq(p, wp));
            String s = " " + wp.name() + "  " + dist + "m  [" + wp.x() + " " + wp.y() + " " + wp.z() + "]";
            graphics.drawString(mc.font, s, 5, y, 0xFF000000 | (wp.color() & 0xFFFFFF));
            y += 10;
        }
    }

    private static double distSq(Vec3 p, BridgeWaypoint wp) {
        double dx = (wp.x() + 0.5) - p.x;
        double dy = (wp.y() + 0.5) - p.y;
        double dz = (wp.z() + 0.5) - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean dimensionMatches(String current, String wpDim) {
        if (wpDim == null || wpDim.isEmpty()) return true;
        if (current.equals(wpDim)) return true;
        return path(current).equals(path(wpDim));
    }

    private static String path(String s) {
        int i = s.indexOf(':');
        return i >= 0 ? s.substring(i + 1) : s;
    }
}
