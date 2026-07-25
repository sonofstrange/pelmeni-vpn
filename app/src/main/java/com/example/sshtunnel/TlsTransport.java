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

    static boolean isConfigured(SecureStore store) {
        return store.getEncrypted(BUNDLE_KEY) != null
                && store.getEncrypted(PASSWORD_KEY) != null
                && !store.getPlain("tls_host", "").isEmpty();
    }

    static boolean isEnabledFor(SecureStore store, String host) {
        return store.getBoolean("tls_enabled", false)
                && isConfigured(store)
                && host.equalsIgnoreCase(store.getPlain("tls_host", ""));
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

    static void clear(SecureStore store) {
        store.removeEncrypted(BUNDLE_KEY);
        store.removeEncrypted(PASSWORD_KEY);
        store.remove("tls_host", "tls_port", "tls_ports", "tls_enabled");
    }

    static SocketFactory socketFactory(
            SecureStore store, Network network) throws Exception {
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
        }
        if (!trustStore.aliases().hasMoreElements()) {
            throw new IOException("TLS CA certificate is missing");
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
        return new TlsSocketFactory(store, network, context, ports(store));
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

    private static final class TlsSocketFactory implements SocketFactory {
        private final SecureStore store;
        private final Network network;
        private final SSLContext context;
        private final int[] tlsPorts;

        TlsSocketFactory(
                SecureStore store, Network network, SSLContext context, int[] tlsPorts) {
            this.store = store;
            this.network = network;
            this.context = context;
            this.tlsPorts = tlsPorts;
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
                raw.setSoTimeout(15_000);
                InetSocketAddress address = network == null
                        ? new InetSocketAddress(host, tlsPort)
                        : new InetSocketAddress(network.getByName(host), tlsPort);
                raw.connect(address, tlsPorts.length > 1 ? 4_000 : 15_000);

                SSLSocket tls = (SSLSocket) context.getSocketFactory()
                        .createSocket(raw, host, tlsPort, true);
                tls.setUseClientMode(true);
                List<String> protocols = new ArrayList<>();
                List<String> supported = Arrays.asList(tls.getSupportedProtocols());
                if (supported.contains("TLSv1.3")) protocols.add("TLSv1.3");
                if (supported.contains("TLSv1.2")) protocols.add("TLSv1.2");
                tls.setEnabledProtocols(protocols.toArray(new String[0]));
                List<String> supportedSuites =
                        Arrays.asList(tls.getSupportedCipherSuites());
                List<String> fastSuites = new ArrayList<>();
                if (supportedSuites.contains("TLS_AES_128_GCM_SHA256")) {
                    fastSuites.add("TLS_AES_128_GCM_SHA256");
                }
                if (supportedSuites.contains(
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256")) {
                    fastSuites.add("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
                }
                if (!fastSuites.isEmpty()) {
                    tls.setEnabledCipherSuites(
                            fastSuites.toArray(new String[0]));
                }
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
