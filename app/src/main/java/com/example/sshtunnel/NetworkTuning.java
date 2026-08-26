package com.example.sshtunnel;

final class NetworkTuning {
    static final int LEGACY_WINDOW_KIB = 1024;
    static final int DEFAULT_WINDOW_KIB = 4096;
    static final int DEFAULT_PACKET_KIB = 32;
    static final int DEFAULT_MTU = 1500;
    static final int HIGH_LATENCY_WINDOW_KIB = 8192;
    static final int STREAM_BUFFER_BYTES = 128 * 1024;

    static final int MIN_WINDOW_KIB = 128;
    static final int MAX_WINDOW_KIB = 32768;
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
        return read(store, "vpn_mtu", DEFAULT_MTU, MIN_MTU, MAX_MTU);
    }

    static boolean valid(int value, int min, int max) {
        return value >= min && value <= max;
    }

    static int socketBufferBytes(int sshWindowBytes) {
        return Math.max(512 * 1024, Math.min(8 * 1024 * 1024, sshWindowBytes));
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
