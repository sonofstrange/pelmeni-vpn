package com.example.sshtunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NetworkTuningTest {
    @Test public void acceptsInclusiveLimits() {
        assertTrue(NetworkTuning.valid(128, 128, 1024));
        assertTrue(NetworkTuning.valid(1024, 128, 1024));
    }

    @Test public void rejectsValuesOutsideLimits() {
        assertFalse(NetworkTuning.valid(127, 128, 1024));
        assertFalse(NetworkTuning.valid(1025, 128, 1024));
    }

    @Test public void socketBufferFollowsWindowWithinSafeLimits() {
        assertEquals(512 * 1024, NetworkTuning.socketBufferBytes(128 * 1024));
        assertEquals(4 * 1024 * 1024,
                NetworkTuning.socketBufferBytes(4 * 1024 * 1024));
        assertEquals(8 * 1024 * 1024,
                NetworkTuning.socketBufferBytes(16 * 1024 * 1024));
    }
}
