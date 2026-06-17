package com.aquarius.proxybridge.feature;

/**
 * A waypoint received over the ProxyBridge channel, mirroring the proxy-side {@code BridgeWaypoint}.
 * Coordinates are absolute block positions in {@code dimension}; {@code color} is {@code 0xRRGGBB}.
 */
public record BridgeWaypoint(
    String id,
    String name,
    String dimension,
    int x,
    int y,
    int z,
    int color,
    int ttlTicks
) {}
