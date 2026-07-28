package com.example.sshtunnel;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class AppSplitTunnelTest {
    @Test public void packageListRoundTripIsStableAndUnique() {
        String encoded = AppSplitTunnel.encodePackages(Arrays.asList(
                "org.telegram.messenger", " com.android.chrome ",
                "org.telegram.messenger", ""));

        assertEquals(
                Arrays.asList("org.telegram.messenger", "com.android.chrome"),
                AppSplitTunnel.decodePackages(encoded));
    }

    @Test public void onlyModeSummaryShowsSelectedCount() {
        AppSplitTunnel.Config config = new AppSplitTunnel.Config(
                true, AppSplitTunnel.MODE_ONLY,
                Arrays.asList("org.telegram.messenger", "com.android.chrome"));

        assertEquals("VPN только для выбранных · 2",
                AppSplitTunnel.summary(config));
    }

    @Test public void disabledConfigDoesNotExposeStaleMode() {
        AppSplitTunnel.Config config = new AppSplitTunnel.Config(
                false, AppSplitTunnel.MODE_BYPASS,
                Arrays.asList("org.telegram.messenger"));

        assertEquals("Выключено", AppSplitTunnel.summary(config));
    }
}
