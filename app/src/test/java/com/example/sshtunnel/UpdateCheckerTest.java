package com.example.sshtunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateCheckerTest {
    @Test public void comparesStableVersions() {
        assertTrue(UpdateChecker.isNewer("v1.28.0", "1.27.9"));
        assertFalse(UpdateChecker.isNewer("1.27.9", "1.28.0"));
        assertFalse(UpdateChecker.isNewer("1.28.0", "1.28.0"));
    }

    @Test public void stableReleaseWinsOverPrerelease() {
        assertTrue(UpdateChecker.isNewer("1.28.0", "1.28.0-beta.3"));
        assertFalse(UpdateChecker.isNewer("1.28.0-beta.3", "1.28.0"));
    }

    @Test public void comparesPrereleaseNumbers() {
        assertTrue(UpdateChecker.isNewer("1.28.0-beta.4", "1.28.0-beta.3"));
        assertFalse(UpdateChecker.isNewer("1.28.0-beta.2", "1.28.0-beta.3"));
    }
}
