package com.aquarius.proxybridge.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The raw {@code proxybridge:main} custom payload. The body bytes are the {@link BridgeProtocol} envelope; the codec
 * just carries the whole buffer so framing lives entirely in {@link BridgeProtocol} (mapping-independent).
 */
public record BridgePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BridgePayload> TYPE = new CustomPacketPayload.Type<>(
        ResourceLocation.fromNamespaceAndPath(BridgeProtocol.CHANNEL_NAMESPACE, BridgeProtocol.CHANNEL_PATH));

    public static final StreamCodec<FriendlyByteBuf, BridgePayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeBytes(payload.data),
        buf -> {
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return new BridgePayload(b);
        });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
