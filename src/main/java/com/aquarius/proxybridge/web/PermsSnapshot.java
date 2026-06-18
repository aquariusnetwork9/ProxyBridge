package com.aquarius.proxybridge.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side mirror of the proxy's {@code perms export} JSON (AquariusProxy's {@code PermissionsSnapshot}). Gson maps
 * by field name, so these must stay in sync with the proxy record's component names. Used by the in-game RBAC admin
 * screen to render the user table without scraping human-formatted command output.
 */
public class PermsSnapshot {
    public boolean enabled;
    public Api api = new Api();
    public String defaultRole = "none";
    public String minConnectRole = "guest";
    public List<String> roles = new ArrayList<>();
    public List<String> groups = new ArrayList<>();
    public List<User> users = new ArrayList<>();

    public static class Api {
        public boolean enabled;
        public String bindHost = "";
        public int port;
        public int requestsPerMinutePerToken;
    }

    public static class User {
        public String uuid = "";
        public String name = "";
        public String role = "guest";
        public List<String> grants = new ArrayList<>();
        public List<String> denies = new ArrayList<>();
        public int tokens;
        public String connectMode = "control";
        public String pearlScope = "self";
    }

    /** Extract the snapshot from a {@code perms export} response — the line that looks like the JSON object. */
    public static PermsSnapshot fromResponse(CommandResponse resp, com.google.gson.Gson gson) {
        if (resp == null || resp.multiLineOutput() == null) return null;
        for (String line : resp.multiLineOutput()) {
            if (line == null) continue;
            String s = line.strip();
            if (s.startsWith("{") && s.endsWith("}")) {
                try {
                    return gson.fromJson(s, PermsSnapshot.class);
                } catch (Exception ignored) {
                    // not the JSON line, keep scanning
                }
            }
        }
        return null;
    }
}
