package com.example.sshtunnel;

import android.net.Network;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

final class TlsTransport {
    static final int DEFAULT_PORT = 443;
    private static final String BUNDLE_KEY = "tls_pkcs12";
    private static final String PASSWORD_KEY = "tls_pkcs12_password";
    private static final String USER_DISABLED_KEY = "tls_user_disabled";

    static boolean isConfigured(SecureStore store) {
        return store.getEncrypted(BUNDLE_KEY) != null
                && store.getEncrypted(PASSWORD_KEY) != null
                && !store.getPlain("tls_host", "").isEmpty();
    }

    static boolean isConfiguredForProfile(
            SecureStore store, ServerProfiles.Profile profile) {
        if (profile == null) return false;
        ServerProfiles.Profile active = ServerProfiles.active(store);
        if (active != null && active.id.equals(profile.id)) {
            return isConfigured(store)
                    && profile.host.equalsIgnoreCase(
                    store.getPlain("tls_host", ""));
        }
        return store.getEncrypted(profileKey(profile.id, BUNDLE_KEY)) != null
                && store.getEncrypted(profileKey(profile.id, PASSWORD_KEY)) != null
                && profile.host.equalsIgnoreCase(store.getPlain(
                profileKey(profile.id, "tls_host"), ""));
    }

    static int portForProfile(SecureStore store, ServerProfiles.Profile profile) {
        if (profile == null) return DEFAULT_PORT;
        ServerProfiles.Profile active = ServerProfiles.active(store);
        String key = active != null && active.id.equals(profile.id)
                ? "tls_port" : profileKey(profile.id, "tls_port");
        try {
            int value = Integer.parseInt(store.getPlain(
                    key, Integer.toString(DEFAULT_PORT)));
            return value >= 1 && value <= 65535 ? value : DEFAULT_PORT;
        } catch (Exception ignored) {
            return DEFAULT_PORT;
        }
    }

    static String portsForProfile(
            SecureStore store, ServerProfiles.Profile profile) {
        if (profile == null) return Integer.toString(DEFAULT_PORT);
        ServerProfiles.Profile active = ServerProfiles.active(store);
        String key = active != null && active.id.equals(profile.id)
                ? "tls_ports" : profileKey(profile.id, "tls_ports");
        return store.getPlain(
                key, Integer.toString(portForProfile(store, profile)));
    }

    static boolean isEnabledFor(SecureStore store, String host) {
        return store.getBoolean("tls_enabled", false)
                && isConfigured(store)
                && host.equalsIgnoreCase(store.getPlain("tls_host", ""));
    }

    static void enableAutomatically(SecureStore store, String host) {
        if (isConfigured(store) && !store.contains(USER_DISABLED_KEY)) {
            store.putBoolean(USER_DISABLED_KEY,
                    !store.getBoolean("tls_enabled", false));
        }
        if (!store.getBoolean(USER_DISABLED_KEY, false)
                && isConfigured(store)
                && host.equalsIgnoreCase(store.getPlain("tls_host", ""))) {
            store.putBoolean("tls_enabled", true);
        }
    }

    static int port(SecureStore store) {
        try {
            int value = Integer.parseInt(store.getPlain(
                    "tls_port", Integer.toString(DEFAULT_PORT)));
            return value >= 1 && value <= 65535 ? value : DEFAULT_PORT;
        } catch (Exception ignored) {
            return DEFAULT_PORT;
        }
    }

    static void save(SecureStore store, String host, int port,
                     byte[] pkcs12, String password) throws Exception {
        loadKeyStore(pkcs12, password.toCharArray());
        store.putEncrypted(BUNDLE_KEY, pkcs12);
        store.putEncrypted(PASSWORD_KEY, password.getBytes(StandardCharsets.UTF_8));
        store.putPlain("tls_host", host);
        store.putPlain("tls_port", Integer.toString(port));
        store.putPlain("tls_ports", Integer.toString(port));
        store.putBoolean(USER_DISABLED_KEY, false);
        store.putBoolean("tls_enabled", true);
    }

    static void setAvailablePorts(SecureStore store, List<Integer> ports) {
        StringBuilder value = new StringBuilder();
        for (int port : ports) {
            if (port < 1 || port > 65535) continue;
            if (value.length() > 0) value.append(',');
            value.append(port);
        }
        if (value.length() > 0) store.putPlain("tls_ports", value.toString());
    }

    static void setEnabled(SecureStore store, boolean enabled) {
        store.putBoolean("tls_enabled", enabled && isConfigured(store));
    }

    static void setEnabledByUser(SecureStore store, boolean enabled) {
        store.putBoolean(USER_DISABLED_KEY, !enabled);
        setEnabled(store, enabled);
    }

    static void clear(SecureStore store) {
        store.removeEncrypted(BUNDLE_KEY);
        store.removeEncrypted(PASSWORD_KEY);
        store.remove("tls_host", "tls_port", "tls_ports", "tls_enabled",
                USER_DISABLED_KEY);
    }

    static void snapshotForProfile(SecureStore store, String profileId) {
        deleteProfileState(store, profileId);
        try {
            byte[] bundle = store.getEncrypted(BUNDLE_KEY);
            byte[] password = store.getEncrypted(PASSWORD_KEY);
            if (bundle != null) store.putEncrypted(profileKey(profileId, BUNDLE_KEY), bundle);
            if (password != null) {
                store.putEncrypted(profileKey(profileId, PASSWORD_KEY), password);
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        copyPlainToProfile(store, profileId, "tls_host", "");
        copyPlainToProfile(store, profileId, "tls_port",
                Integer.toString(DEFAULT_PORT));
        copyPlainToProfile(store, profileId, "tls_ports", "");
        store.putBoolean(profileKey(profileId, "tls_enabled"),
                store.getBoolean("tls_enabled", false));
        store.putBoolean(profileKey(profileId, USER_DISABLED_KEY),
                store.getBoolean(USER_DISABLED_KEY, false));
    }

    static void restoreForProfile(SecureStore store, String profileId) {
        clear(store);
        try {
            byte[] bundle = store.getEncrypted(profileKey(profileId, BUNDLE_KEY));
            byte[] password = store.getEncrypted(profileKey(profileId, PASSWORD_KEY));
            if (bundle != null) store.putEncrypted(BUNDLE_KEY, bundle);
            if (password != null) store.putEncrypted(PASSWORD_KEY, password);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        copyPlainFromProfile(store, profileId, "tls_host");
        copyPlainFromProfile(store, profileId, "tls_port");
        copyPlainFromProfile(store, profileId, "tls_ports");
        String enabledKey = profileKey(profileId, "tls_enabled");
        String disabledKey = profileKey(profileId, USER_DISABLED_KEY);
        if (store.contains(enabledKey)) {
            store.putBoolean("tls_enabled", store.getBoolean(enabledKey, false));
        }
        if (store.contains(disabledKey)) {
            store.putBoolean(USER_DISABLED_KEY, store.getBoolean(disabledKey, false));
        }
    }

    static void deleteProfileState(SecureStore store, String profileId) {
        store.removeEncrypted(profileKey(profileId, BUNDLE_KEY));
        store.removeEncrypted(profileKey(profileId, PASSWORD_KEY));
        store.remove(profileKey(profileId, "tls_host"),
                profileKey(profileId, "tls_port"),
                profileKey(profileId, "tls_ports"),
                profileKey(profileId, "tls_enabled"),
                profileKey(profileId, USER_DISABLED_KEY));
    }

    private static void copyPlainToProfile(
            SecureStore store, String profileId, String key, String fallback) {
        String value = store.getPlain(key, fallback);
        if (!value.isEmpty()) store.putPlain(profileKey(profileId, key), value);
    }

    private static void copyPlainFromProfile(
            SecureStore store, String profileId, String key) {
        String value = store.getPlain(profileKey(profileId, key), "");
        if (!value.isEmpty()) store.putPlain(key, value);
    }

    private static String profileKey(String profileId, String key) {
        return "profile_" + profileId + "_" + key;
    }

    static SocketFactory socketFactory(
            SecureStore store, Network network) throws Exception {
        return socketFactory(store, network,
                NetworkTuning.DEFAULT_WINDOW_KIB * 1024);
    }

    static SocketFactory socketFactory(
            SecureStore store, Network network, int sshWindowBytes) throws Exception {
        byte[] bundle = store.getEncrypted(BUNDLE_KEY);
        byte[] passwordBytes = store.getEncrypted(PASSWORD_KEY);
        if (bundle == null || passwordBytes == null) {
            throw new IOException("TLS credentials are missing");
        }
        char[] password = new String(passwordBytes, StandardCharsets.UTF_8).toCharArray();
        KeyStore keyStore = loadKeyStore(bundle, password);
        Arrays.fill(password, '\0');

        KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        char[] keyPassword =
                new String(passwordBytes, StandardCharsets.UTF_8).toCharArray();
        keys.init(keyStore, keyPassword);
        Arrays.fill(keyPassword, '\0');

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null);
        java.util.Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain != null && chain.length > 0) {
                trustStore.setCertificateEntry("pelmeni-ca",
                        chain[chain.length - 1]);
                break;
            }
            Certificate cert = keyStore.getCertificate(alias);
            if (cert != null) {
                trustStore.setCertificateEntry("pelmeni-ca", cert);
                break;
            }
        }
        if (!trustStore.aliases().hasMoreElements()) {
            throw new IOException("TLS CA certificate is missing");
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return new TlsSocketFactory(store, network, context, ports(store),
                NetworkTuning.socketBufferBytes(sshWindowBytes));
    }

    private static int[] ports(SecureStore store) {
        List<Integer> values = new ArrayList<>();
        int preferred = port(store);
        values.add(preferred);
        for (String raw : store.getPlain(
                "tls_ports", Integer.toString(DEFAULT_PORT)).split(",")) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value >= 1 && value <= 65535 && !values.contains(value)) {
                    values.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static KeyStore loadKeyStore(byte[] bundle, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new java.io.ByteArrayInputStream(bundle), password);
        if (!keyStore.aliases().hasMoreElements()) {
            throw new IOException("TLS credential bundle is empty");
        }
        return keyStore;
    }

    private TlsTransport() {
    }

    static void clearDnsCache() {
        TlsSocketFactory.DNS_CACHE.clear();
    }

    private static final class TlsSocketFactory implements SocketFactory {
        private static final class DnsEntry {
            final java.net.InetAddress address;
            final long expiresAt;

            DnsEntry(java.net.InetAddress address) {
                this.address = address;
                this.expiresAt = android.os.SystemClock.elapsedRealtime() + 60_000L;
            }

            boolean isValid() {
                return android.os.SystemClock.elapsedRealtime() < expiresAt;
            }
        }

        private static final java.util.Map<String, DnsEntry> DNS_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final SecureStore store;
        private final Network network;
        private final SSLContext context;
        private final int[] tlsPorts;
        private final int socketBufferBytes;

        TlsSocketFactory(
                SecureStore store, Network network, SSLContext context, int[] tlsPorts,
                int socketBufferBytes) {
            this.store = store;
            this.network = network;
            this.context = context;
            this.tlsPorts = tlsPorts;
            this.socketBufferBytes = socketBufferBytes;
        }

        @Override public Socket createSocket(String host, int ignoredSshPort)
                throws IOException {
            Exception lastError = null;
            for (int tlsPort : tlsPorts) {
                try {
                    Socket socket = connect(host, tlsPort);
                    store.putPlain("tls_port", Integer.toString(tlsPort));
                    return socket;
                } catch (Exception error) {
                    lastError = error;
                    android.util.Log.e("PelmeniTLS",
                            "TLS port " + tlsPort + " failed: "
                                    + error.getClass().getSimpleName());
                }
            }
            throw new IOException("TLS protection failed on all configured ports",
                    lastError);
        }

        private Socket connect(String host, int tlsPort) throws IOException {
            Socket raw = network == null
                    ? new Socket() : network.getSocketFactory().createSocket();
            try {
                raw.setTcpNoDelay(true);
                raw.setKeepAlive(true);
                try {
                    raw.setTrafficClass(0x10); // IPTOS_LOWDELAY
                } catch (Exception ignored) {
                }
                raw.setReceiveBufferSize(socketBufferBytes);
                raw.setSendBufferSize(Math.max(4 * 1024 * 1024, socketBufferBytes));
                java.net.InetAddress ip = null;
                DnsEntry cached = DNS_CACHE.get(host);
                if (cached != null && cached.isValid()) {
                    ip = cached.address;
                }
                if (ip == null) {
                    try {
                        ip = network == null
                                ? java.net.InetAddress.getByName(host) : network.getByName(host);
                        DNS_CACHE.put(host, new DnsEntry(ip));
                    } catch (IOException dnsError) {
                        if (cached != null) {
                            ip = cached.address;
                        } else {
                            try {
                                ip = java.net.InetAddress.getByName(host);
                                DNS_CACHE.put(host, new DnsEntry(ip));
                            } catch (IOException fallbackError) {
                                throw dnsError;
                            }
                        }
                    }
                }
                InetSocketAddress address = new InetSocketAddress(ip, tlsPort);
                raw.connect(address, tlsPorts.length > 1 ? 4_000 : 15_000);

                SSLSocket tls = (SSLSocket) context.getSocketFactory()
                        .createSocket(raw, host, tlsPort, true);
                tls.setUseClientMode(true);
                tls.setTcpNoDelay(true);
                tls.setKeepAlive(true);
                tls.setReceiveBufferSize(socketBufferBytes);
                tls.setSendBufferSize(socketBufferBytes);
                List<String> protocols = new ArrayList<>();
                List<String> supported = Arrays.asList(tls.getSupportedProtocols());
                if (supported.contains("TLSv1.3")) protocols.add("TLSv1.3");
                if (supported.contains("TLSv1.2")) protocols.add("TLSv1.2");
                tls.setEnabledProtocols(protocols.toArray(new String[0]));
                tls.startHandshake();
                tls.setSoTimeout(0);
                return tls;
            } catch (Exception error) {
                try {
                    raw.close();
                } catch (Exception ignored) {
                }
                throw new IOException("TLS protection failed: " + error.getMessage(), error);
            }
        }

        @Override public InputStream getInputStream(Socket socket) throws IOException {
            return socket.getInputStream();
        }

        @Override public OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }
    }
}
