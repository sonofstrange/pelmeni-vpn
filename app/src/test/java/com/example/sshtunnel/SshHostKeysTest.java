package com.example.sshtunnel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class SshHostKeysTest {
    @Test public void formatsOpenSshSha256Fingerprint() throws Exception {
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0",
                SshHostKeys.fingerprint("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
