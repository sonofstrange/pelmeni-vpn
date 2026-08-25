package com.example.sshtunnel;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SplitTunnelTest {
    @Test public void parsesCommentsWhitespaceAndDuplicates() {
        List<String> parsed = SplitTunnel.parseLines(
                " example.com  # comment\n\n10.0.0.0/8\nexample.com\n");
        assertEquals(Arrays.asList("example.com", "10.0.0.0/8"), parsed);
    }

    @Test public void normalizesUrlsInProfiles() {
        SplitTunnel.Profile profile = SplitTunnel.create(
                "Test", SplitTunnel.MODE_ONLY,
                Arrays.asList("https://example.com/path", "EXAMPLE.COM"));
        assertEquals(Arrays.asList("example.com"), profile.entries);
    }

    @Test public void cidrContainsAndOverlaps() {
        SplitTunnel.Cidr parent = new SplitTunnel.Cidr(0, 0); // 0.0.0.0/0
        SplitTunnel.Cidr child = new SplitTunnel.Cidr(0x0a000000, 8); // 10.0.0.0/8
        assertEquals(true, parent.contains(child));
        assertEquals(false, child.contains(parent));
        assertEquals(true, parent.overlaps(child));
        assertEquals(true, child.overlaps(parent));
    }
}
