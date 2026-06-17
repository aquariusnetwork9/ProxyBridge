package com.aquarius.proxybridge.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the waypoints currently published by the proxy, keyed by group then waypoint id. Thread-safe: the network
 * receiver mutates it; the renderer reads it. Groups are replaced wholesale by {@code wp/sync} (the proxy's primary
 * operation), so chambers that fill up simply stop being re-synced and disappear here on the next sync.
 */
public final class WaypointStore {
    public static final WaypointStore INSTANCE = new WaypointStore();

    private final Map<String, Map<String, BridgeWaypoint>> groups = new ConcurrentHashMap<>();

    public void sync(String group, List<BridgeWaypoint> waypoints) {
        Map<String, BridgeWaypoint> m = new ConcurrentHashMap<>();
        for (BridgeWaypoint wp : waypoints) m.put(wp.id(), wp);
        if (m.isEmpty()) groups.remove(group);
        else groups.put(group, m);
    }

    public void upsert(String group, BridgeWaypoint wp) {
        groups.computeIfAbsent(group, g -> new ConcurrentHashMap<>()).put(wp.id(), wp);
    }

    public void remove(String group, String id) {
        Map<String, BridgeWaypoint> m = groups.get(group);
        if (m != null) {
            m.remove(id);
            if (m.isEmpty()) groups.remove(group);
        }
    }

    public void clear(String group) {
        groups.remove(group);
    }

    public void clearAll() {
        groups.clear();
    }

    /** Snapshot of all current waypoints across every group. */
    public List<BridgeWaypoint> all() {
        List<BridgeWaypoint> out = new ArrayList<>();
        for (Map<String, BridgeWaypoint> m : groups.values()) out.addAll(m.values());
        return out;
    }

    public int count() {
        int n = 0;
        for (Map<String, BridgeWaypoint> m : groups.values()) n += m.size();
        return n;
    }
}
