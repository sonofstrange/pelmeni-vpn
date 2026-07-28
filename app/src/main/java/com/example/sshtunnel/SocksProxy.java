package com.example.sshtunnel;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.ChannelTuning;
import com.jcraft.jsch.Session;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import android.os.SystemClock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A local SOCKS5 CONNECT proxy backed by SSH direct-tcpip channels. */
final class SocksProxy implements AutoCloseable {
    private static final int MAX_CLIENTS = Math.max(64, Math.min(
            128, Runtime.getRuntime().availableProcessors() * 16));
    private final Session session;
    private final int port;
    private final int windowSize;
    private final int packetSize;
    private final UserTrafficLimiter trafficLimiter;
    private final ExecutorService clients = new ThreadPoolExecutor(
            0, MAX_CLIENTS * 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private final Semaphore clientSlots = new Semaphore(MAX_CLIENTS);
    private final AtomicLong uploadedBytes = new AtomicLong();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private volatile int lastLatencyMs = -1;
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;

    SocksProxy(Session session, int port, int windowSize, int packetSize) {
        this(session, port, windowSize, packetSize, null);
    }

    SocksProxy(Session session, int port, int windowSize, int packetSize,
               UserTrafficLimiter trafficLimiter) {
        this.session = session;
        this.port = port;
        this.windowSize = windowSize;
        this.packetSize = packetSize;
        this.trafficLimiter = trafficLimiter;
    }

    synchronized void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = server.accept();
                    if (!clientSlots.tryAcquire()) {
                        client.close();
                        continue;
                    }
                    try {
                        clients.execute(() -> {
                            try {
                                handle(client);
                            } finally {
                                clientSlots.release();
                            }
                        });
                    } catch (RejectedExecutionException rejected) {
                        clientSlots.release();
                        client.close();
                    }
                } catch (IOException ignored) {
                    if (!running) return;
                    running = false;
                    return;
                }
            }
        }, "pelmeni-socks-accept-" + port);
        acceptThread.start();
    }

    private void handle(Socket client) {
        ChannelDirectTCPIP channel = null;
        try (Socket ignored = client) {
            client.setTcpNoDelay(true);
            client.setSoTimeout(15_000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            require(input.read() == 5, "Only SOCKS5 is supported");
            int methods = readByte(input);
            skip(input, methods);
            output.write(new byte[] {5, 0});
            output.flush();

            require(readByte(input) == 5 && readByte(input) == 1, "Only CONNECT is supported");
            readByte(input); // Reserved byte.
            int addressType = readByte(input);
            String host;
            if (addressType == 1) {
                host = readByte(input) + "." + readByte(input) + "." + readByte(input) + "." + readByte(input);
            } else if (addressType == 3) {
                byte[] name = new byte[readByte(input)];
                readFully(input, name);
                host = new String(name, StandardCharsets.UTF_8);
            } else if (addressType == 4) {
                byte[] address = new byte[16];
                readFully(input, address);
                host = java.net.InetAddress.getByAddress(address).getHostAddress();
            } else {
                throw new IOException("Unsupported address type");
            }
            int targetPort = (readByte(input) << 8) | readByte(input);

            channel = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            channel.setHost(host);
            channel.setPort(targetPort);
            channel.setOrgIPAddress("127.0.0.1");
            channel.setOrgPort(client.getLocalPort());
            ChannelTuning.optimizeDirectTcpIp(channel, windowSize, packetSize);
            OutputStream channelOutput = channel.getOutputStream();
            InputStream channelInput = channel.getInputStream();
            long connectStarted = SystemClock.elapsedRealtime();
            channel.connect(15_000);
            lastLatencyMs = (int) Math.min(
                    Integer.MAX_VALUE, SystemClock.elapsedRealtime() - connectStarted);
            client.setSoTimeout(0);
            output.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            output.flush();

            clients.execute(() -> copy(input, channelOutput, uploadedBytes));
            copy(channelInput, output, downloadedBytes);
        } catch (Exception ignored) {
            // The client receives a closed socket when SSH or the target is unavailable.
        } finally {
            if (channel != null) channel.disconnect();
        }
    }

    private void copy(InputStream input, OutputStream output, AtomicLong counter) {
        try {
            byte[] buffer = new byte[NetworkTuning.STREAM_BUFFER_BYTES];
            for (int count; (count = readAvailable(input, buffer)) != -1;) {
                int allowed = trafficLimiter == null
                        ? count : trafficLimiter.acquire(count);
                if (allowed <= 0) return;
                output.write(buffer, 0, allowed);
                output.flush();
                counter.addAndGet(allowed);
                if (allowed < count) return;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }

    /**
     * Blocks for the first bytes, then coalesces everything already queued. This keeps
     * interactive requests responsive while avoiding a flush for every small TCP read
     * during sustained transfers.
     */
    static int readAvailable(InputStream input, byte[] buffer) throws IOException {
        int total = input.read(buffer);
        if (total < 0) return -1;
        while (total < buffer.length) {
            int queued = input.available();
            if (queued <= 0) break;
            int count = input.read(buffer, total,
                    Math.min(queued, buffer.length - total));
            if (count <= 0) break;
            total += count;
        }
        return total;
    }

    long getUploadedBytes() {
        return uploadedBytes.get();
    }

    long getDownloadedBytes() {
        return downloadedBytes.get();
    }

    int getLastLatencyMs() {
        return lastLatencyMs;
    }

    boolean isRunning() {
        ServerSocket current = server;
        return running && current != null && !current.isClosed();
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        for (int offset = 0; offset < buffer.length;) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
    }

    private static void skip(InputStream input, int length) throws IOException {
        byte[] ignored = new byte[length];
        readFully(input, ignored);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    @Override public synchronized void close() {
        running = false;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
            server = null;
        }
        clients.shutdownNow();
        Thread currentAcceptThread = acceptThread;
        acceptThread = null;
        if (currentAcceptThread != null) currentAcceptThread.interrupt();
    }
}
