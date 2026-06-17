package com.aquarius.proxybridge.net;

import com.aquarius.proxybridge.ProxyBridgeMod;
import com.aquarius.proxybridge.feature.BridgeWaypoint;
import com.aquarius.proxybridge.feature.WaypointStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the {@code proxybridge:main} channel, decodes inbound proxy messages into {@link WaypointStore}, and
 * sends the {@code hello} handshake on join (which — together with Fabric's automatic channel announcement — lets the
 * proxy detect this mod and start publishing).
 */
public final class BridgeNetworking {
    private BridgeNetworking() {}

    public static final String MOD_VERSION = "0.1.0";

    public static void init() {
        PayloadTypeRegistry.playS2C().register(BridgePayload.TYPE, BridgePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BridgePayload.TYPE, BridgePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(BridgePayload.TYPE, (payload, context) -> {
            final byte[] data = payload.data();
            context.client().execute(() -> handle(data));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(BridgeNetworking::sendHello));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            WaypointStore.INSTANCE.clearAll());
    }

    private static void handle(byte[] data) {
        try {
            BridgeProtocol.Reader r = new BridgeProtocol.Reader(data);
            r.readVarInt(); // protocol version
            String topic = r.readString();
            switch (topic) {
                case BridgeProtocol.TOPIC_WP_SYNC -> {
                    String group = r.readString();
                    int n = r.readVarInt();
                    List<BridgeWaypoint> wps = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) wps.add(BridgeProtocol.readWaypoint(r));
                    WaypointStore.INSTANCE.sync(group, wps);
                }
                case BridgeProtocol.TOPIC_WP_UPSERT -> {
                    String group = r.readString();
                    WaypointStore.INSTANCE.upsert(group, BridgeProtocol.readWaypoint(r));
                }
                case BridgeProtocol.TOPIC_WP_REMOVE -> {
                    String group = r.readString();
                    String id = r.readString();
                    WaypointStore.INSTANCE.remove(group, id);
                }
                case BridgeProtocol.TOPIC_WP_CLEAR -> WaypointStore.INSTANCE.clear(r.readString());
                case BridgeProtocol.TOPIC_HELLO -> {
                    String side = r.readString();
                    String version = r.readString();
                    ProxyBridgeMod.LOG.info("Bridge connected: {} {}", side, version);
                }
                case BridgeProtocol.TOPIC_TOAST -> {
                    String msg = r.readString();
                    var mc = Minecraft.getInstance();
                    if (mc.player != null) mc.player.displayClientMessage(Component.literal(msg), true);
                }
                default -> ProxyBridgeMod.LOG.debug("Unknown bridge topic: {}", topic);
            }
        } catch (Exception e) {
            ProxyBridgeMod.LOG.warn("Failed to handle bridge message", e);
        }
    }

    public static void sendHello() {
        if (!ClientPlayNetworking.canSend(BridgePayload.TYPE)) return;
        List<String> features = List.of(BridgeProtocol.TOPIC_WP_SYNC, BridgeProtocol.TOPIC_CMD_INVOKE);
        send(BridgeProtocol.encodeHello("client", MOD_VERSION, features));
    }

    /** Optional client -> proxy command path (the proxy gates these by its allow-list). */
    public static void sendCmdInvoke(String name, String args) {
        if (!ClientPlayNetworking.canSend(BridgePayload.TYPE)) return;
        send(BridgeProtocol.encodeCmdInvoke(name, args));
    }

    private static void send(byte[] bytes) {
        ClientPlayNetworking.send(new BridgePayload(bytes));
    }
}
