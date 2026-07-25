package com.example.sshtunnel;

final class TunnelMode {
    private TunnelMode() {}

    static int telegramSocksPort(SecureStore store) {
        return parsePort(store.getPlain("socks_port", "1080"));
    }

    static int vpnSocksPort(SecureStore store) {
        int configured = telegramSocksPort(store);
        return configured <= 64511 ? configured + 1024 : configured - 1024;
    }

    static int testSocksPort(SecureStore store) {
        return store.getBoolean("telegram_proxy", true)
                ? telegramSocksPort(store) : vpnSocksPort(store);
    }

    static String portsLabel(SecureStore store) {
        boolean vpn = store.getBoolean("vpn_mode", false);
        boolean telegram = store.getBoolean("telegram_proxy", true);
        if (vpn && telegram) {
            return "TG " + telegramSocksPort(store) + " · VPN " + vpnSocksPort(store);
        }
        if (vpn) return "VPN " + vpnSocksPort(store);
        return "TG " + telegramSocksPort(store);
    }

    static String label(SecureStore store) {
        boolean vpn = store.getBoolean("vpn_mode", false);
        boolean telegram = store.getBoolean("telegram_proxy", true);
        if (vpn && telegram) return "VPN + Telegram";
        if (vpn) return "VPN";
        return "Telegram";
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : 1080;
        } catch (Exception ignored) {
            return 1080;
        }
    }
}
