package com.example.sshtunnel;

/** Pure policy for accepting an Android network as the physical SSH transport. */
final class UnderlyingNetworkPolicy {
    static final long SWITCH_STABILIZATION_MS = 750;

    static boolean usable(
            boolean internet, boolean validated, boolean notVpn, boolean notSuspended) {
        return internet && validated && notVpn && notSuspended;
    }

    /**
     * The first validated physical network releases the initial SSH attempt from
     * its wait and must not cancel that same attempt. Only replacing an already
     * active physical network requires a reconnect.
     */
    static boolean reconnectAfterCommit(boolean replacingActiveNetwork) {
        return replacingActiveNetwork;
    }

    private UnderlyingNetworkPolicy() {
    }
}
