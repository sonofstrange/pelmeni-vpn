package com.example.sshtunnel;

import org.json.JSONObject;
import org.junit.Test;

public class ConfigSecurityTest {
    @Test public void acceptsPasswordFreeFormat() throws Exception {
        ConfigSecurity.verifySafeExport(new JSONObject()
                .put("format", 2)
                .put("host", "vpn.example.com")
                .put("requires_password", true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPlaintextPassword() throws Exception {
        ConfigSecurity.verifySafeExport(new JSONObject()
                .put("format", 2)
                .put("password", "must-not-leak"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLegacyExportFormat() throws Exception {
        ConfigSecurity.verifySafeExport(new JSONObject().put("format", 1));
    }
}
