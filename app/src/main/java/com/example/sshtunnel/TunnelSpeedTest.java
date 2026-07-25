package com.example.sshtunnel;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

final class TunnelSpeedTest {
    static final class Result {
        final int latencyMs;
        final long downloadBytesPerSecond;
        final long uploadBytesPerSecond;

        Result(int latencyMs, long downloadBytesPerSecond, long uploadBytesPerSecond) {
            this.latencyMs = latencyMs;
            this.downloadBytesPerSecond = downloadBytesPerSecond;
            this.uploadBytesPerSecond = uploadBytesPerSecond;
        }
    }

    static Result run(SecureStore store, int downloadBytes, int uploadBytes) throws Exception {
        int socksPort = TunnelMode.testSocksPort(store);
        measureLatency(socksPort); // Warm up TCP, TLS and the nearest Cloudflare edge.
        int[] latency = {
                measureLatency(socksPort),
                measureLatency(socksPort),
                measureLatency(socksPort)
        };
        Arrays.sort(latency);
        long download = measureDownload(socksPort, downloadBytes);
        long upload = uploadBytes > 0 ? measureUploadSeries(socksPort, uploadBytes) : -1;
        return new Result(latency[1], download, upload);
    }

    private static int measureLatency(int socksPort) throws Exception {
        HttpsURLConnection connection = open(
                "https://speed.cloudflare.com/__down?bytes=0&r=" + System.nanoTime(),
                socksPort);
        long started = System.nanoTime();
        try (InputStream input = connection.getInputStream()) {
            input.read();
        } finally {
            connection.disconnect();
        }
        return (int) Math.max(1, (System.nanoTime() - started) / 1_000_000L);
    }

    private static long measureDownload(int socksPort, int requestedBytes) throws Exception {
        HttpsURLConnection connection = open(
                "https://speed.cloudflare.com/__down?bytes=" + requestedBytes
                        + "&r=" + System.nanoTime(),
                socksPort);
        byte[] buffer = new byte[64 * 1024];
        long received = 0;
        long started = System.nanoTime();
        try (InputStream input = connection.getInputStream()) {
            for (int count; (count = input.read(buffer)) != -1;) received += count;
        } finally {
            connection.disconnect();
        }
        return rate(received, System.nanoTime() - started);
    }

    private static long measureUploadSeries(int socksPort, int totalBytes) throws Exception {
        int first = Math.max(64 * 1024, totalBytes / 12);
        int second = Math.max(64 * 1024, totalBytes / 4);
        int third = Math.max(64 * 1024, totalBytes - first - second);
        int[] sizes = {first, second, third};
        List<Long> stableRates = new ArrayList<>();
        List<Long> allRates = new ArrayList<>();
        for (int size : sizes) {
            UploadSample sample = measureUpload(socksPort, size);
            allRates.add(sample.bytesPerSecond);
            if (sample.elapsedNanos >= 250_000_000L) {
                stableRates.add(sample.bytesPerSecond);
            }
        }
        List<Long> selected = stableRates.isEmpty() ? allRates : stableRates;
        selected.sort(Long::compareTo);
        return selected.get(selected.size() / 2);
    }

    private static UploadSample measureUpload(int socksPort, int bytes) throws Exception {
        HttpsURLConnection connection = open(
                "https://speed.cloudflare.com/__up?r=" + System.nanoTime(), socksPort);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        byte[] buffer = new byte[64 * 1024];
        new SecureRandom().nextBytes(buffer);
        int remaining = bytes;
        long started = System.nanoTime();
        try (OutputStream output = connection.getOutputStream()) {
            while (remaining > 0) {
                int count = Math.min(remaining, buffer.length);
                output.write(buffer, 0, count);
                remaining -= count;
            }
            output.flush();
        }
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new Exception("Upload server returned HTTP " + responseCode);
        }
        try (InputStream input = connection.getInputStream()) {
            while (input.read(buffer) != -1) {
                // The response proves that the server received the full request body.
            }
        } finally {
            connection.disconnect();
        }
        long elapsed = System.nanoTime() - started;
        return new UploadSample(rate(bytes, elapsed), elapsed);
    }

    private static HttpsURLConnection open(String address, int socksPort) throws Exception {
        Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", socksPort));
        HttpsURLConnection connection =
                (HttpsURLConnection) new URL(address).openConnection(proxy);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "PelmeniVPN-SpeedTest/1");
        return connection;
    }

    private static long rate(long bytes, long nanoseconds) {
        return nanoseconds <= 0 ? 0 : bytes * 1_000_000_000L / nanoseconds;
    }

    private static final class UploadSample {
        final long bytesPerSecond;
        final long elapsedNanos;

        UploadSample(long bytesPerSecond, long elapsedNanos) {
            this.bytesPerSecond = bytesPerSecond;
            this.elapsedNanos = elapsedNanos;
        }
    }

    private TunnelSpeedTest() {
    }
}
