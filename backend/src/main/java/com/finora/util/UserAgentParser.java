package com.finora.util;

/**
 * Best-effort, dependency-free browser/OS labels from a raw User-Agent header -- deliberately NOT
 * a full parsing library (no new dependency for something only used as a human-readable label on
 * a device-session row, never branched on). Order matters: check more specific tokens (Edg, OPR)
 * before the general ones they'd otherwise also match (Chrome, Safari), since Chromium-based
 * browsers all include "Chrome" (and Chrome itself includes "Safari") in their own UA string.
 */
public final class UserAgentParser {

    private UserAgentParser() {}

    public static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("edg/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera")) return "Opera";
        if (ua.contains("firefox/")) return "Firefox";
        if (ua.contains("chrome/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        return "Other";
    }

    public static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown";
        String ua = userAgent.toLowerCase();
        if (ua.contains("iphone")) return "iPhone";
        if (ua.contains("ipad")) return "iPad";
        if (ua.contains("android")) return ua.contains("mobile") ? "Android phone" : "Android tablet";
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os")) return "Mac";
        if (ua.contains("linux")) return "Linux";
        return "Other";
    }
}
