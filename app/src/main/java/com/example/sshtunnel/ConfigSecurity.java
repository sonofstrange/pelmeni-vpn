package com.example.sshtunnel;

import org.json.JSONObject;

/** Guards exported configuration files against accidental secret disclosure. */
final class ConfigSecurity {
    private static final String[] FORBIDDEN_KEYS = {
            "password", "secret", "passphrase", "private_key", "pkcs12"
    };

    static void verifySafeExport(JSONObject config) {
        if (config.optInt("format", -1) < 2) {
            throw new IllegalArgumentException("Only password-free config format 2 may be exported");
        }
        for (String key : FORBIDDEN_KEYS) {
            if (config.has(key) && !config.optString(key, "").isEmpty()) {
                throw new IllegalArgumentException(
                        "Exported configuration contains forbidden secret: " + key);
            }
        }
    }

    private ConfigSecurity() {
    }
}
