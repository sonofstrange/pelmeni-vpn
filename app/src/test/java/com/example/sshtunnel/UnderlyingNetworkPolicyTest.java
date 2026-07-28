package com.example.sshtunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnderlyingNetworkPolicyTest {
    @Test public void acceptsValidatedPhysicalInternet() {
        assertTrue(UnderlyingNetworkPolicy.usable(true, true, true, true));
    }

    @Test public void rejectsNetworkBeforeAndroidValidation() {
        assertFalse(UnderlyingNetworkPolicy.usable(true, false, true, true));
    }

    @Test public void rejectsVpnAndSuspendedNetworks() {
        assertFalse(UnderlyingNetworkPolicy.usable(true, true, false, true));
        assertFalse(UnderlyingNetworkPolicy.usable(true, true, true, false));
    }

    @Test public void rejectsNetworkWithoutInternetCapability() {
        assertFalse(UnderlyingNetworkPolicy.usable(false, true, true, true));
    }

    @Test public void initialNetworkDoesNotCancelFirstConnectionAttempt() {
        assertFalse(UnderlyingNetworkPolicy.reconnectAfterCommit(false));
    }

    @Test public void replacingActiveNetworkReconnectsTransport() {
        assertTrue(UnderlyingNetworkPolicy.reconnectAfterCommit(true));
    }
}
