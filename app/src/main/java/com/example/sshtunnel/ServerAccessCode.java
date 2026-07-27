package com.example.sshtunnel;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

final class ServerAccessCode {
    private static final String PREFIX = "PEL1-";

    static ServerProfiles.Profile importCode(SecureStore store, String raw)
            throws Exception {
        String value = raw.trim();
        if (!value.startsWith(PREFIX)) {
            throw new Exception("Это не код доступа Пельмени VPN.");
        }
        byte[] decoded = Base64.decode(value.substring(PREFIX.length()),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        if (json.optInt("format", 0) != 1) {
            throw new Exception("Эта версия кода пока не поддерживается.");
        }
        String host = json.getString("host").trim();
        String sshPort = json.optString("ssh_port", "22").trim();
        String username = json.getString("username").trim();
        String password = json.getString("password");
        String socksPort = json.optString("socks_port", "1080").trim();
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new Exception("Код доступа повреждён.");
        }
        ServerProfiles.Profile profile = ServerProfiles.create(
                json.optString("name", host),
                host, sshPort, username, socksPort,
                json.optInt("window_kib", NetworkTuning.DEFAULT_WINDOW_KIB),
                json.optInt("packet_kib", NetworkTuning.DEFAULT_PACKET_KIB),
                json.optInt("mtu", NetworkTuning.DEFAULT_MTU));
        ServerProfiles.saveAndActivate(store, profile, password);
        return profile;
    }

    private ServerAccessCode() {
    }
}
