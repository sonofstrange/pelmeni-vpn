package com.example.sshtunnel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ServerProfiles {
    private static final String PROFILES_KEY = "server_profiles";
    private static final String ACTIVE_KEY = "active_server_profile";
    private static final String PERFORMANCE_MIGRATION_KEY =
            "performance_defaults_v5";

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

        Profile(String id, String name, String host, String sshPort, String user,
                String socksPort, int windowKiB, int packetKiB, int mtu) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.sshPort = sshPort;
            this.user = user;
            this.socksPort = socksPort;
            this.windowKiB = windowKiB;
            this.packetKiB = packetKiB;
            this.mtu = mtu;
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
            if ((profile.windowKiB == 1024 || profile.windowKiB == 4096 || profile.windowKiB == 16384)
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
                        item.optInt("mtu", NetworkTuning.DEFAULT_MTU)));
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
                array.put(new JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("host", profile.host)
                        .put("ssh_port", profile.sshPort)
                        .put("user", profile.user)
                        .put("socks_port", profile.socksPort)
                        .put("window_kib", profile.windowKiB)
                        .put("packet_kib", profile.packetKiB)
                        .put("mtu", profile.mtu));
            }
            store.putPlain(PROFILES_KEY, array.toString());
        } catch (Exception error) {
            throw new IllegalStateException(error);
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
