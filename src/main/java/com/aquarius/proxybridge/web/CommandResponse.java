package com.aquarius.proxybridge.web;

import java.util.List;

/** Response body from the proxy's HTTP command API. */
public record CommandResponse(
    String embed,
    String embedComponent,
    List<String> multiLineOutput
) {}
