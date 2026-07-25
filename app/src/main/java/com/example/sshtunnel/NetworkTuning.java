package com.example.sshtunnel;

final class NetworkTuning {
    static final int DEFAULT_WINDOW_KIB = 1024;
    static final int DEFAULT_PACKET_KIB = 32;
    static final int DEFAULT_MTU = 1400;

    static final int MIN_WINDOW_KIB = 128;
    static final int MAX_WINDOW_KIB = 16384;
    static final int MIN_PACKET_KIB = 16;
    static final int MAX_PACKET_KIB = 64;
    static final int MIN_MTU = 1280;
    static final int MAX_MTU = 16000;

    static int windowKiB(SecureStore store) {
        return read(store, "ssh_window_kib", DEFAULT_WINDOW_KIB,
                MIN_WINDOW_KIB, MAX_WINDOW_KIB);
    }

    static int packetKiB(SecureStore store) {
        return read(store, "ssh_packet_kib", DEFAULT_PACKET_KIB,
                MIN_PACKET_KIB, MAX_PACKET_KIB);
    }

    static int vpnMtu(SecureStore store) {
        if (!store.getBoolean("mtu_1400_migrated", false)) {
            if ("8500".equals(store.getPlain("vpn_mtu", "8500"))) {
                store.putPlain("vpn_mtu", Integer.toString(DEFAULT_MTU));
            }
            store.putBoolean("mtu_1400_migrated", true);
        }
        return read(store, "vpn_mtu", DEFAULT_MTU, MIN_MTU, MAX_MTU);
    }

    static boolean valid(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static int read(SecureStore store, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(store.getPlain(key, Integer.toString(fallback)));
            return valid(value, min, max) ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private NetworkTuning() {
    }
}
