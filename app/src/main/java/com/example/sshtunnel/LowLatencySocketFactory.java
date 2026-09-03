package com.example.sshtunnel;

import android.net.Network;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;

/** Creates a low-latency SSH socket with enough TCP buffering for high-RTT links. */
final class LowLatencySocketFactory implements SocketFactory {
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

    static void clearDnsCache() {
        DNS_CACHE.clear();
    }

    private final Network network;
    private final int socketBufferBytes;

    LowLatencySocketFactory(Network network) {
        this(network, NetworkTuning.DEFAULT_WINDOW_KIB * 1024);
    }

    LowLatencySocketFactory(Network network, int sshWindowBytes) {
        this.network = network;
        this.socketBufferBytes = NetworkTuning.socketBufferBytes(sshWindowBytes);
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = network == null
                ? new Socket() : network.getSocketFactory().createSocket();
        configure(socket);
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
        InetSocketAddress address = new InetSocketAddress(ip, port);
        socket.connect(address, 15_000);
        configure(socket);
        return socket;
    }

    private void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        try {
            socket.setPerformancePreferences(0, 2, 1);
        } catch (Exception ignored) {
        }
        try {
            socket.setTrafficClass(0x10); // IPTOS_LOWDELAY
        } catch (SocketException ignored) {
        }
        socket.setReceiveBufferSize(socketBufferBytes);
        socket.setSendBufferSize(Math.max(4 * 1024 * 1024, socketBufferBytes));
    }

    @Override public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
