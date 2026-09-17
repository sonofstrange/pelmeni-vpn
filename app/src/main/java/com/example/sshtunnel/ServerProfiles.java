package com.example.sshtunnel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ServerProfiles {
    private static final String PROFILES_KEY = "server_profiles";
    private static final String ACTIVE_KEY = "active_server_profile";
    private static final String PERFORMANCE_MIGRATION_KEY =
            "performance_defaults_v8";

    static final class Profile {
        final String id;
        final String name;
        final String host;
        final String sshPort;
        final String user;
        final String socksPort;
        final int windowKiB;
        final int packetKiB;
        final int mtu;
        /** pool_id публичного сервера, если профиль получен через каталог. Иначе пустая строка. */
        final String poolId;

        Profile(String id, String name, String host, String sshPort, String user,
                String socksPort, int windowKiB, int packetKiB, int mtu) {
            this(id, name, host, sshPort, user, socksPort, windowKiB, packetKiB, mtu, "");
        }

        Profile(String id, String name, String host, String sshPort, String user,
                String socksPort, int windowKiB, int packetKiB, int mtu, String poolId) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.sshPort = sshPort;
            this.user = user;
            this.socksPort = socksPort;
            this.windowKiB = windowKiB;
            this.packetKiB = packetKiB;
            this.mtu = mtu;
            this.poolId = poolId == null ? "" : poolId;
        }
    }

    static void migrateLegacy(SecureStore store) {
        if (!list(store).isEmpty()) return;
        String host = store.getPlain("host", "").trim();
        if (host.isEmpty()) return;
        Profile profile = new Profile(newId(), host, host,
                store.getPlain("port", "22"),
                store.getPlain("user", "root"),
                store.getPlain("socks_port", "1080"),
                NetworkTuning.windowKiB(store),
                NetworkTuning.packetKiB(store),
                NetworkTuning.vpnMtu(store));
        List<Profile> profiles = new ArrayList<>();
        profiles.add(profile);
        write(store, profiles);
        store.putPlain(ACTIVE_KEY, profile.id);
        putPassword(store, profile.id, store.getSecret());
        TlsTransport.snapshotForProfile(store, profile.id);
    }

    /**
     * Moves profiles that still use the exact old balanced preset to the larger
     * receive window. Custom tuning is left untouched.
     */
    static void migratePerformanceDefaults(SecureStore store) {
        if (store.getBoolean(PERFORMANCE_MIGRATION_KEY, false)) return;
        List<Profile> profiles = list(store);
        String activeId = store.getPlain(ACTIVE_KEY, "");
        boolean changed = false;
        boolean activeChanged = false;
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            if ((profile.windowKiB == 384 || profile.windowKiB == 512 || profile.windowKiB == 768 || profile.windowKiB == 1024 || profile.windowKiB == 4096 || profile.windowKiB == 16384)
                    && profile.mtu == NetworkTuning.DEFAULT_MTU) {
                profiles.set(i, new Profile(profile.id, profile.name, profile.host,
                        profile.sshPort, profile.user, profile.socksPort,
                        NetworkTuning.DEFAULT_WINDOW_KIB, NetworkTuning.DEFAULT_PACKET_KIB, profile.mtu));
                changed = true;
                activeChanged |= profile.id.equals(activeId);
            }
        }
        if (changed) write(store, profiles);
        if (activeChanged || profiles.isEmpty()
                || "384".equals(store.getPlain("ssh_window_kib", ""))
                || "512".equals(store.getPlain("ssh_window_kib", ""))
                || "768".equals(store.getPlain("ssh_window_kib", ""))
                || "1024".equals(store.getPlain("ssh_window_kib", ""))
                || "4096".equals(store.getPlain("ssh_window_kib", ""))
                || "16384".equals(store.getPlain("ssh_window_kib", ""))) {
            store.putPlain("ssh_window_kib",
                    Integer.toString(NetworkTuning.DEFAULT_WINDOW_KIB));
            store.putPlain("ssh_packet_kib",
                    Integer.toString(NetworkTuning.DEFAULT_PACKET_KIB));
        }
        store.putBoolean(PERFORMANCE_MIGRATION_KEY, true);
    }

    static List<Profile> list(SecureStore store) {
        List<Profile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store.getPlain(PROFILES_KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                profiles.add(new Profile(
                        item.getString("id"),
                        item.optString("name", item.getString("host")),
                        item.getString("host"),
                        item.optString("ssh_port", "22"),
                        item.optString("user", "root"),
                        item.optString("socks_port", "1080"),
                        item.optInt("window_kib", NetworkTuning.DEFAULT_WINDOW_KIB),
                        item.optInt("packet_kib", NetworkTuning.DEFAULT_PACKET_KIB),
                        item.optInt("mtu", NetworkTuning.DEFAULT_MTU),
                        item.optString("pool_id", "")));
            }
        } catch (Exception ignored) {
        }
        return profiles;
    }

    static Profile active(SecureStore store) {
        List<Profile> profiles = list(store);
        if (profiles.isEmpty()) return null;
        String activeId = store.getPlain(ACTIVE_KEY, "");
        for (Profile profile : profiles) {
            if (profile.id.equals(activeId)) return profile;
        }
        store.putPlain(ACTIVE_KEY, profiles.get(0).id);
        return profiles.get(0);
    }

    static String password(SecureStore store, String id) {
        byte[] value = store.getEncrypted(passwordKey(id));
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }

    static Profile create(String name, String host, String sshPort, String user,
                          String socksPort, int windowKiB, int packetKiB, int mtu) {
        return new Profile(newId(), name, host, sshPort, user, socksPort,
                windowKiB, packetKiB, mtu);
    }

    static void saveAndActivate(SecureStore store, Profile profile, String password)
            throws Exception {
        String oldActiveId = store.getPlain(ACTIVE_KEY, "");
        if (!oldActiveId.isEmpty() && !oldActiveId.equals(profile.id)) {
            TlsTransport.snapshotForProfile(store, oldActiveId);
        }
        List<Profile> profiles = list(store);
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                Profile previous = profiles.get(i);
                if (!previous.host.equalsIgnoreCase(profile.host)
                        || !previous.sshPort.equals(profile.sshPort)) {
                    SshHostKeys.clearProfile(store, profile.id);
                }
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(profile);
        write(store, profiles);
        putPassword(store, profile.id, password);
        store.putPlain(ACTIVE_KEY, profile.id);
        apply(store, profile, password);
        if (!oldActiveId.equals(profile.id)) {
            TlsTransport.restoreForProfile(store, profile.id);
        }
    }

    static boolean activate(SecureStore store, String id) throws Exception {
        Profile target = find(store, id);
        if (target == null) return false;
        String oldActiveId = store.getPlain(ACTIVE_KEY, "");
        if (!oldActiveId.equals(id)) {
            if (!oldActiveId.isEmpty()) {
                TlsTransport.snapshotForProfile(store, oldActiveId);
            }
            store.putPlain(ACTIVE_KEY, id);
            apply(store, target, password(store, id));
            TlsTransport.restoreForProfile(store, id);
        } else {
            apply(store, target, password(store, id));
        }
        return true;
    }

    static void updateActiveConnection(SecureStore store, String host, String sshPort,
                                       String user, String password, String socksPort)
            throws Exception {
        Profile active = active(store);
        if (active == null) return;
        if (!active.host.equalsIgnoreCase(host)
                || !active.sshPort.equals(sshPort)) {
            SshHostKeys.clearProfile(store, active.id);
        }
        Profile updated = new Profile(active.id, active.name, host, sshPort, user,
                socksPort, active.windowKiB, active.packetKiB, active.mtu);
        replace(store, updated);
        putPassword(store, active.id, password);
    }

    static void updateActiveTuning(SecureStore store, int windowKiB, int packetKiB,
                                   int mtu) {
        Profile active = active(store);
        if (active == null) return;
        replace(store, new Profile(active.id, active.name, active.host,
                active.sshPort, active.user, active.socksPort,
                windowKiB, packetKiB, mtu));
    }

    static boolean delete(SecureStore store, String id) throws Exception {
        List<Profile> profiles = list(store);
        if (profiles.size() <= 1) return false;
        String activeId = store.getPlain(ACTIVE_KEY, "");
        if (activeId.equals(id)) TlsTransport.snapshotForProfile(store, id);
        profiles.removeIf(profile -> profile.id.equals(id));
        write(store, profiles);
        store.removeEncrypted(passwordKey(id));
        SshHostKeys.clearProfile(store, id);
        TlsTransport.deleteProfileState(store, id);
        if (activeId.equals(id)) {
            Profile next = profiles.get(0);
            store.putPlain(ACTIVE_KEY, next.id);
            apply(store, next, password(store, next.id));
            TlsTransport.restoreForProfile(store, next.id);
        }
        return true;
    }

    /** Публично доступный replace для обновления полей профиля (например pool_id). */
    static void replaceProfile(SecureStore store, Profile updated) {
        replace(store, updated);
    }

    private static Profile find(SecureStore store, String id) {
        for (Profile profile : list(store)) {
            if (profile.id.equals(id)) return profile;
        }
        return null;
    }

    private static void replace(SecureStore store, Profile updated) {
        List<Profile> profiles = list(store);
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(updated.id)) {
                profiles.set(i, updated);
                write(store, profiles);
                return;
            }
        }
    }

    private static void apply(SecureStore store, Profile profile, String password)
            throws Exception {
        store.putPlain("host", profile.host);
        store.putPlain("port", profile.sshPort);
        store.putPlain("user", profile.user);
        store.putPlain("socks_port", profile.socksPort);
        store.putPlain("ssh_window_kib", Integer.toString(profile.windowKiB));
        store.putPlain("ssh_packet_kib", Integer.toString(profile.packetKiB));
        store.putPlain("vpn_mtu", Integer.toString(profile.mtu));
        store.putSecret(password);
    }

    private static void putPassword(SecureStore store, String id, String password) {
        try {
            store.putEncrypted(passwordKey(id),
                    password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void write(SecureStore store, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (Profile profile : profiles) {
                JSONObject obj = new JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("host", profile.host)
                        .put("ssh_port", profile.sshPort)
                        .put("user", profile.user)
                        .put("socks_port", profile.socksPort)
                        .put("window_kib", profile.windowKiB)
                        .put("packet_kib", profile.packetKiB)
                        .put("mtu", profile.mtu);
                if (!profile.poolId.isEmpty()) {
                    obj.put("pool_id", profile.poolId);
                }
                array.put(obj);
            }
            store.putPlain(PROFILES_KEY, array.toString());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Фоново проверяет наш реестр и таблицу переносов (migrations),
     * обновляя host/ssh_port у ВСЕХ профилей (гостевых, публичных, личных).
     * Возвращает true если хотя бы один профиль был обновлён.
     */
    static boolean applyServerMigrations(SecureStore store) {
        List<Profile> profiles = list(store);
        if (profiles.isEmpty()) return false;
        boolean anyChanged = false;

        Map<String, JSONObject> migrationsByOldHost = new HashMap<>();
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(PublicServerRegistry.REGISTRY_API + "/migrations").openConnection();
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(6_000);
            conn.setRequestProperty("User-Agent", "PelmeniVPN-Android");
            if (conn.getResponseCode() == 200) {
                byte[] bytes = conn.getInputStream().readAllBytes();
                JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String oldHost = item.optString("old_host", "").trim().toLowerCase(Locale.ROOT);
                    if (!oldHost.isEmpty()) {
                        migrationsByOldHost.put(oldHost, item);
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }

        Map<String, JSONObject> serversByPoolId = new HashMap<>();
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(PublicServerRegistry.REGISTRY_API + "/servers").openConnection();
            conn.setConnectTimeout(6_000);
            conn.setReadTimeout(6_000);
            conn.setRequestProperty("User-Agent", "PelmeniVPN-Android");
            if (conn.getResponseCode() == 200) {
                byte[] bytes = conn.getInputStream().readAllBytes();
                JSONArray arr = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String poolId = item.optString("pool_id", "").trim();
                    if (!poolId.isEmpty()) {
                        serversByPoolId.put(poolId, item);
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }

        String activeId = store.getPlain(ACTIVE_KEY, "");

        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            String currentHost = profile.host.trim();
            String currentHostLower = currentHost.toLowerCase(Locale.ROOT);
            String newHost = null;
            String newPort = profile.sshPort;
            String hostKeyType = "";
            String hostKey = "";

            if (migrationsByOldHost.containsKey(currentHostLower)) {
                JSONObject mig = migrationsByOldHost.get(currentHostLower);
                newHost = mig.optString("new_host", "").trim();
                newPort = Integer.toString(mig.optInt("ssh_port", parsePortInt(profile.sshPort, 22)));
                hostKeyType = mig.optString("host_key_type", "");
                hostKey = mig.optString("host_key", "");
            }

            if ((newHost == null || newHost.isEmpty()) && !profile.poolId.isEmpty() && serversByPoolId.containsKey(profile.poolId)) {
                JSONObject srv = serversByPoolId.get(profile.poolId);
                String srvHost = srv.optString("host", "").trim();
                if (!srvHost.isEmpty() && !srvHost.equalsIgnoreCase(currentHost)) {
                    newHost = srvHost;
                    newPort = Integer.toString(srv.optInt("ssh_port", parsePortInt(profile.sshPort, 22)));
                    hostKeyType = srv.optString("host_key_type", "");
                    hostKey = srv.optString("host_key", "");
                }
            }

            // Если не нашли в общем списке, пробуем точечный запрос к /migrations/{host}
            if (newHost == null || newHost.isEmpty()) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(PublicServerRegistry.REGISTRY_API + "/migrations/" + currentHost).openConnection();
                    conn.setConnectTimeout(4_000);
                    conn.setReadTimeout(4_000);
                    if (conn.getResponseCode() == 200) {
                        byte[] bytes = conn.getInputStream().readAllBytes();
                        JSONObject obj = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                        if (obj.optBoolean("migrated", false)) {
                            newHost = obj.optString("new_host", "").trim();
                            newPort = Integer.toString(obj.optInt("ssh_port", parsePortInt(profile.sshPort, 22)));
                            hostKeyType = obj.optString("host_key_type", "");
                            hostKey = obj.optString("host_key", "");
                        }
                    }
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }

            if (newHost != null && !newHost.isEmpty() && (!newHost.equalsIgnoreCase(currentHost) || !newPort.equals(profile.sshPort))) {
                SshHostKeys.ScannedKey oldKey = SshHostKeys.trustedKey(store, profile);
                SshHostKeys.clearProfile(store, profile.id);

                Profile updated = new Profile(profile.id, profile.name, newHost, newPort,
                        profile.user, profile.socksPort, profile.windowKiB, profile.packetKiB,
                        profile.mtu, profile.poolId);
                profiles.set(i, updated);
                anyChanged = true;

                // Перепривязываем ключ SSH для нового хоста
                if (!hostKey.isEmpty() && !hostKeyType.isEmpty()) {
                    try {
                        SshHostKeys.trust(store, updated, new SshHostKeys.ScannedKey(newHost, Integer.parseInt(newPort), hostKeyType, hostKey));
                    } catch (Exception ignored) {
                    }
                } else if (oldKey != null) {
                    try {
                        SshHostKeys.trust(store, updated, new SshHostKeys.ScannedKey(newHost, Integer.parseInt(newPort), oldKey.type, oldKey.encodedKey));
                    } catch (Exception ignored) {
                    }
                }

                if (profile.id.equals(activeId)) {
                    store.putPlain("host", newHost);
                    store.putPlain("port", newPort);
                }
            }
        }

        if (anyChanged) {
            write(store, profiles);
        }
        return anyChanged;
    }

    static void refreshHostsFromRegistry(SecureStore store) {
        applyServerMigrations(store);
    }

    private static int parsePortInt(String raw, int defaultValue) {
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port <= 65535 ? port : defaultValue;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String passwordKey(String id) {
        return "server_password_" + id;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private ServerProfiles() {
    }
}
