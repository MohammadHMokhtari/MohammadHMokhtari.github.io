package com.darsbridge.app;

/** Pure routing decision, independently testable; no silent direct fallback. */
public final class ConnectionPolicy {
    public enum Mode { AUTO, SYSTEM, LOCAL }
    public enum Route { BLOCKED, SYSTEM, SOCKS }
    private ConnectionPolicy() {}
    public static Route choose(Mode mode, boolean hasVpn, boolean hasSocks, boolean proxySupported) {
        if (mode == Mode.SYSTEM) return Route.SYSTEM;
        if (mode == Mode.LOCAL) return hasSocks && proxySupported ? Route.SOCKS : Route.BLOCKED;
        if (hasVpn) return Route.SYSTEM;
        return hasSocks && proxySupported ? Route.SOCKS : Route.BLOCKED;
    }
    public static Mode parse(String name) {
        try { return Mode.valueOf(name); } catch (Exception e) { return Mode.AUTO; }
    }
    public static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (Exception ignored) { }
        throw new IllegalArgumentException("پورت باید عددی بین ۱ و ۶۵۵۳۵ باشد.");
    }
}
