package com.example.sshtunnel;

final class QuickSettingsModeHistory {
    private static final String HAS_LAST_MODE = "tile_has_last_mode";
    private static final String LAST_VPN_MODE = "tile_last_vpn_mode";
    private static final String LAST_PROXY_MODE = "tile_last_proxy_mode";

    private QuickSettingsModeHistory() {}

    static void remember(SecureStore store) {
        boolean vpn = store.getBoolean("vpn_mode", false);
        boolean proxy = store.getBoolean("telegram_proxy", !vpn);
        if (!vpn && !proxy) return;
        store.putBoolean(HAS_LAST_MODE, true);
        store.putBoolean(LAST_VPN_MODE, vpn);
        store.putBoolean(LAST_PROXY_MODE, proxy);
    }

    static Mode restore(SecureStore store) {
        boolean vpn = store.getBoolean("vpn_mode", false);
        boolean proxy = store.getBoolean("telegram_proxy", !vpn);
        boolean hasLast = store.getBoolean(HAS_LAST_MODE, false);
        Mode mode = select(vpn, proxy, hasLast,
                store.getBoolean(LAST_VPN_MODE, false),
                store.getBoolean(LAST_PROXY_MODE, false));
        if (mode.vpn != vpn) store.putBoolean("vpn_mode", mode.vpn);
        if (mode.proxy != proxy) store.putBoolean("telegram_proxy", mode.proxy);
        if (mode.hasSelection()) remember(store);
        return mode;
    }

    static Mode select(boolean vpn, boolean proxy, boolean hasLast,
                       boolean lastVpn, boolean lastProxy) {
        if (vpn || proxy) return new Mode(vpn, proxy);
        if (hasLast && (lastVpn || lastProxy)) {
            return new Mode(lastVpn, lastProxy);
        }
        return new Mode(false, false);
    }

    static final class Mode {
        final boolean vpn;
        final boolean proxy;

        Mode(boolean vpn, boolean proxy) {
            this.vpn = vpn;
            this.proxy = proxy;
        }

        boolean hasSelection() {
            return vpn || proxy;
        }
    }
}
