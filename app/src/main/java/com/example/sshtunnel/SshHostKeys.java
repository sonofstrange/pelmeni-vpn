package com.example.sshtunnel;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Pins one SSH host key to each server profile.
 *
 * <p>Scanning never supplies authentication credentials: it performs only the SSH
 * key exchange, captures the presented public host key and lets authentication
 * fail. A password-bearing session is created only with strict checking against
 * the previously confirmed key.</p>
 */
final class SshHostKeys {
    private static final String KEY_PREFIX = "ssh_host_key_";

    static final class ScannedKey {
        final String host;
        final int port;
        final String type;
        final String encodedKey;
        final String fingerprint;

        ScannedKey(String host, int port, String type, String encodedKey) throws Exception {
            this.host = host;
            this.port = port;
            this.type = type;
            this.encodedKey = encodedKey;
            this.fingerprint = fingerprint(Base64.getDecoder().decode(encodedKey));
        }
    }

    static ScannedKey scan(SecureStore store) throws Exception {
        ServerProfiles.Profile profile = requireActiveProfile(store);
        return scan(store, profile);
    }

    static ScannedKey scan(
            SecureStore store, ServerProfiles.Profile profile) throws Exception {
        if (profile == null) throw new JSchException("No server profile");
        String host = profile.host.trim();
        int port = parsePort(profile.sshPort);
        JSch jsch = new JSch();
        Session session = jsch.getSession(profile.user.trim(), host, port);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "none");
        ServerProfiles.Profile active = ServerProfiles.active(store);
        boolean activeProfile = active != null && active.id.equals(profile.id);
        session.setSocketFactory(activeProfile
                && TlsTransport.isEnabledFor(store, host)
                ? TlsTransport.socketFactory(store, null)
                : new LowLatencySocketFactory(null));
        session.setTimeout(15_000);
        try {
            session.connect(15_000);
        } catch (JSchException authenticationExpected) {
            // Most servers reject the "none" method after key exchange. The host
            // key is already available and no password has been transmitted.
        }
        try {
            HostKey hostKey = session.getHostKey();
            if (hostKey == null || hostKey.getKey() == null) {
                throw new JSchException("SSH server did not present a host key");
            }
            return new ScannedKey(host, port, hostKey.getType(), hostKey.getKey());
        } finally {
            session.disconnect();
        }
    }

    static boolean isTrusted(SecureStore store, ScannedKey scanned) {
        return isTrusted(store, ServerProfiles.active(store), scanned);
    }

    static boolean isTrusted(
            SecureStore store, ServerProfiles.Profile profile,
            ScannedKey scanned) {
        if (profile == null) return false;
        StoredKey stored = read(store, profile);
        return stored != null
                && stored.host.equalsIgnoreCase(scanned.host)
                && stored.port == scanned.port
                && MessageDigest.isEqual(stored.key, decode(scanned.encodedKey));
    }

    static String trustedFingerprint(SecureStore store) {
        return trustedFingerprint(store, ServerProfiles.active(store));
    }

    static String trustedFingerprint(
            SecureStore store, ServerProfiles.Profile profile) {
        if (profile == null) return "";
        StoredKey stored = read(store, profile);
        if (stored == null) return "";
        try {
            return fingerprint(stored.key);
        } catch (Exception ignored) {
            return "";
        }
    }

    static void trust(SecureStore store, ScannedKey scanned) throws Exception {
        ServerProfiles.Profile profile = requireActiveProfile(store);
        trust(store, profile, scanned);
    }

    static void trust(
            SecureStore store, ServerProfiles.Profile profile,
            ScannedKey scanned) throws Exception {
        if (profile == null) throw new JSchException("No server profile");
        if (!profile.host.equalsIgnoreCase(scanned.host)
                || parsePort(profile.sshPort) != scanned.port) {
            throw new JSchException("Server profile changed during host key verification");
        }
        String value = scanned.host + "\n" + scanned.port + "\n"
                + scanned.type + "\n" + scanned.encodedKey;
        store.putEncrypted(storageKey(profile.id),
                value.getBytes(StandardCharsets.UTF_8));
    }

    static Session newPinnedSession(
            SecureStore store, String user, String host, int port) throws Exception {
        ServerProfiles.Profile profile = requireActiveProfile(store);
        return newPinnedSession(store, profile, user, host, port);
    }

    static Session newPinnedSession(
            SecureStore store, ServerProfiles.Profile profile,
            String user, String host, int port) throws Exception {
        if (profile == null) throw new JSchException("No server profile");
        StoredKey stored = read(store, profile);
        if (stored == null
                || !profile.host.equalsIgnoreCase(host)
                || parsePort(profile.sshPort) != port
                || !stored.host.equalsIgnoreCase(host)
                || stored.port != port) {
            throw new JSchException("SSH host key is not trusted");
        }
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(new PinnedRepository(host, stored.type, stored.key));
        Session session = jsch.getSession(user, host, port);
        session.setConfig("StrictHostKeyChecking", "yes");
        return session;
    }

    static void clearProfile(SecureStore store, String profileId) {
        if (profileId != null && !profileId.isEmpty()) {
            store.removeEncrypted(storageKey(profileId));
        }
    }

    private static ServerProfiles.Profile requireActiveProfile(SecureStore store)
            throws JSchException {
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        if (profile == null) throw new JSchException("No active server profile");
        return profile;
    }

    private static StoredKey read(SecureStore store) {
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        if (profile == null) return null;
        return read(store, profile);
    }

    private static StoredKey read(
            SecureStore store, ServerProfiles.Profile profile) {
        byte[] bytes = store.getEncrypted(storageKey(profile.id));
        if (bytes == null) return null;
        try {
            String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\n", 4);
            if (parts.length != 4) return null;
            return new StoredKey(parts[0], parsePort(parts[1]), parts[2], decode(parts[3]));
        } catch (Exception ignored) {
            return null;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    static String fingerprint(byte[] key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }

    private static int parsePort(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SSH port");
        return port;
    }

    private static String storageKey(String profileId) {
        return KEY_PREFIX + profileId;
    }

    private static final class StoredKey {
        final String host;
        final int port;
        final String type;
        final byte[] key;

        StoredKey(String host, int port, String type, byte[] key) {
            this.host = host;
            this.port = port;
            this.type = type;
            this.key = key;
        }
    }

    private static final class PinnedRepository implements HostKeyRepository {
        private final String host;
        private final String type;
        private final byte[] key;

        PinnedRepository(String host, String type, byte[] key) {
            this.host = host;
            this.type = type;
            this.key = Arrays.copyOf(key, key.length);
        }

        @Override public int check(String ignoredHost, byte[] presentedKey) {
            return MessageDigest.isEqual(key, presentedKey) ? OK : CHANGED;
        }

        @Override public void add(HostKey hostKey, UserInfo userInfo) {
            // Trust is changed only by the explicit confirmation flow.
        }

        @Override public void remove(String host, String type) {
        }

        @Override public void remove(String host, String type, byte[] key) {
        }

        @Override public String getKnownHostsRepositoryID() {
            return "Pelmeni VPN pinned host key";
        }

        @Override public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override public HostKey[] getHostKey(String requestedHost, String requestedType) {
            return new HostKey[0];
        }
    }

    private SshHostKeys() {
    }
}
