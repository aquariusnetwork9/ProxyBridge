package com.aquarius.proxybridge.web;

/** Body of the proxy's {@code POST /position}: the player's own live position, attributed to their token's UUID. */
public record PositionRequest(double x, double y, double z, String dimension) {}
