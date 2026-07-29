package com.example.sshtunnel;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuickSettingsModeHistoryTest {
    @Test public void currentSelectionWinsOverHistory() {
        QuickSettingsModeHistory.Mode mode = QuickSettingsModeHistory.select(
                true, false, true, false, true);

        assertTrue(mode.vpn);
        assertFalse(mode.proxy);
    }

    @Test public void emptySelectionRestoresLastCombination() {
        QuickSettingsModeHistory.Mode mode = QuickSettingsModeHistory.select(
                false, false, true, true, true);

        assertTrue(mode.vpn);
        assertTrue(mode.proxy);
    }

    @Test public void emptySelectionWithoutHistoryStaysEmpty() {
        QuickSettingsModeHistory.Mode mode = QuickSettingsModeHistory.select(
                false, false, false, false, false);

        assertFalse(mode.vpn);
        assertFalse(mode.proxy);
    }
}
