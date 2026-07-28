package com.example.sshtunnel;

import org.junit.Test;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SocksProxyTest {
    @Test public void coalescesQueuedBytesIntoOneRead() throws Exception {
        byte[] source = new byte[96 * 1024];
        for (int i = 0; i < source.length; i++) source[i] = (byte) i;
        byte[] target = new byte[128 * 1024];

        int count = SocksProxy.readAvailable(
                new ByteArrayInputStream(source), target);

        assertEquals(source.length, count);
        byte[] copied = new byte[count];
        System.arraycopy(target, 0, copied, 0, count);
        assertArrayEquals(source, copied);
    }

    @Test public void reportsEndOfStream() throws Exception {
        assertEquals(-1, SocksProxy.readAvailable(
                new ByteArrayInputStream(new byte[0]), new byte[32]));
    }
}
