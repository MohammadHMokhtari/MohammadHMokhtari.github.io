package com.darsbridge.app;

import java.net.URI;
import java.net.IDN;
import java.util.Locale;

/** Strict address handling; never treats scripts or file URLs as navigations. */
public final class UrlTools {
    private UrlTools() {}
    public static String normalize(String input) {
        if (input == null) throw bad();
        String s = input.trim();
        if (s.isEmpty() || s.length() > 8192) throw bad();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\'
                || (c >= '\u202a' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069')) throw bad();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (s.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) throw bad();
            s = "https://" + s;
        }
        try {
            URI u = new URI(s);
            String authority = u.getRawAuthority();
            if (authority == null || authority.isEmpty() || authority.contains("@")
                || authority.contains("%")) throw bad();
            if (u.getUserInfo() != null) throw bad();
            String host = u.getHost();
            int port = u.getPort();
            if (host == null) {
                // International domain names are converted to ASCII without resolving them.
                if (authority.contains(":")) throw bad();
                host = IDN.toASCII(authority, IDN.USE_STD3_ASCII_RULES);
            }
            if (host.isEmpty() || host.equals(".") || host.contains("..") || host.startsWith(".")) throw bad();
            if (port < -1 || port == 0 || port > 65535) throw bad();
            host = host.toLowerCase(Locale.ROOT);
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // This build deliberately supports HTTPS only. Upgrade typed HTTP URLs.
            String out = "https://" + host + (port == -1 ? "" : ":" + port) + path;
            if (u.getRawQuery() != null) out += "?" + u.getRawQuery();
            if (u.getRawFragment() != null) out += "#" + u.getRawFragment();
            return new URI(out).toASCIIString();
        } catch (Exception e) { throw bad(); }
    }
    public static boolean isHttps(String s) {
        try {
            URI u = new URI(s);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }
    public static String host(String s) {
        try { return new URI(s).getHost(); } catch (Exception e) { return ""; }
    }
    private static IllegalArgumentException bad() {
        return new IllegalArgumentException("یک آدرس معتبر سایت وارد کنید؛ لینک فایل، اسکریپت یا دارای نام کاربری پذیرفته نمی‌شود.");
    }
}
