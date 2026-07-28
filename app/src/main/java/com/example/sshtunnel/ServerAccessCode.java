package com.example.sshtunnel;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ServerAccessCode {
    private static final String PREFIX = "PEL1-";

    static ServerProfiles.Profile importCode(SecureStore store, String raw)
            throws Exception {
        JSONObject json = decode(raw);
        String host = json.getString("host").trim();
        String sshPort = json.optString("ssh_port", "22").trim();
        String username = json.getString("username").trim();
        String password = json.getString("password");
        String socksPort = json.optString("socks_port", "1080").trim();
        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new Exception("Код доступа повреждён.");
        }
        boolean tlsEnabled = json.optBoolean("tls_enabled", false);
        ServerProfiles.Profile existing = null;
        for (ServerProfiles.Profile candidate : ServerProfiles.list(store)) {
            if (candidate.host.equalsIgnoreCase(host)
                    && candidate.user.equals(username)) {
                existing = candidate;
                break;
            }
        }
        ServerProfiles.Profile profile;
        if (existing == null) {
            profile = ServerProfiles.create(
                    json.optString("name", host),
                    host, sshPort, username, socksPort,
                    json.optInt("window_kib", NetworkTuning.DEFAULT_WINDOW_KIB),
                    json.optInt("packet_kib", NetworkTuning.DEFAULT_PACKET_KIB),
                    json.optInt("mtu", NetworkTuning.DEFAULT_MTU));
        } else {
            profile = new ServerProfiles.Profile(
                    existing.id, existing.name, host, sshPort, username, socksPort,
                    json.optInt("window_kib", existing.windowKiB),
                    json.optInt("packet_kib", existing.packetKiB),
                    json.optInt("mtu", existing.mtu));
        }
        ServerProfiles.saveAndActivate(store, profile, password);
        if (json.has("tls_enabled")) {
            if (!tlsEnabled) {
                TlsTransport.setEnabledByUser(store, false);
                TlsTransport.snapshotForProfile(store, profile.id);
            }
        }
        UserAccessPolicy.saveFromCode(store, profile.id, json);
        return profile;
    }

    static boolean requestsTls(String raw) throws Exception {
        return decode(raw).optBoolean("tls_enabled", false);
    }

    /**
     * Completes a TLS-enabled import only after MainActivity has explicitly
     * confirmed and pinned the SSH host key for the newly activated profile.
     */
    static void importTls(
            SecureStore store, ServerProfiles.Profile profile, String raw)
            throws Exception {
        JSONObject json = decode(raw);
        if (!json.optBoolean("tls_enabled", false)) return;
        if (!profile.host.equalsIgnoreCase(json.getString("host").trim())
                || !profile.user.equals(json.getString("username").trim())) {
            throw new Exception("Код доступа не совпадает с выбранным сервером.");
        }
        ServerAccessManager.TlsBundle tlsBundle =
                ServerAccessManager.fetchTlsBundle(store, profile);
        int tlsPort = json.optInt("tls_port", TlsTransport.DEFAULT_PORT);
        TlsTransport.save(store, profile.host, tlsPort,
                tlsBundle.pkcs12, tlsBundle.password);
        List<Integer> ports = new ArrayList<>();
        for (String rawPort : json.optString(
                "tls_ports", Integer.toString(tlsPort)).split(",")) {
            try {
                ports.add(Integer.parseInt(rawPort.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        TlsTransport.setAvailablePorts(store, ports);
        TlsTransport.snapshotForProfile(store, profile.id);
    }

    private static JSONObject decode(String raw) throws Exception {
        String value = raw.trim();
        if (!value.startsWith(PREFIX)) {
            throw new Exception("Это не код доступа Пельмени VPN.");
        }
        byte[] decoded;
        try {
            decoded = Base64.decode(value.substring(PREFIX.length()),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (IllegalArgumentException error) {
            throw new Exception("Код доступа повреждён.");
        }
        JSONObject json = new JSONObject(
                new String(decoded, StandardCharsets.UTF_8));
        if (json.optInt("format", 0) != 1) {
            throw new Exception("Эта версия кода пока не поддерживается.");
        }
        return json;
    }

    private ServerAccessCode() {
    }
}
