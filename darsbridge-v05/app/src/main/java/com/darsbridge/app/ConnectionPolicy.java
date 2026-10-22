package com.darsbridge.app;
/** No silent direct fallback from an automatic route. */
public final class ConnectionPolicy {
    public enum Mode { AUTO, SYSTEM, LOCAL, EMBEDDED }
    public enum Route { BLOCKED, SYSTEM, SOCKS, INTERNAL }
    private ConnectionPolicy() {}
    public static Route choose(Mode mode, boolean hasVpn, boolean hasLocalSocks,
                               boolean proxySupported, boolean internalReady) {
        if (mode == Mode.SYSTEM) return Route.SYSTEM;
        if (mode == Mode.LOCAL) return hasLocalSocks && proxySupported ? Route.SOCKS : Route.BLOCKED;
        if (mode == Mode.EMBEDDED) return internalReady && proxySupported ? Route.INTERNAL : Route.BLOCKED;
        if (hasVpn) return Route.SYSTEM;
        if (internalReady && proxySupported) return Route.INTERNAL;
        return hasLocalSocks && proxySupported ? Route.SOCKS : Route.BLOCKED;
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
