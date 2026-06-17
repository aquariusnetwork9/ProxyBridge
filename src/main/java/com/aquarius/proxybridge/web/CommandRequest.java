package com.aquarius.proxybridge.web;

/** Request body for the proxy's HTTP command API (POST {url}/command). */
public record CommandRequest(String command) {}
