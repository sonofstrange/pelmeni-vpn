package com.example.sshtunnel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

final class UpdateChecker {
    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/sonofstrange/pelmeni-vpn/releases/latest";

    static final class Result {
        final String version;
        final String notes;
        final String pageUrl;
        final String downloadUrl;
        final long size;

        Result(String version, String notes, String pageUrl,
               String downloadUrl, long size) {
            this.version = version;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    static Result check(SecureStore store) throws Exception {
        java.net.URLConnection rawConnection;
        if (TunnelService.isConnected()) {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1",
                            TunnelMode.testSocksPort(store)));
            rawConnection = new URL(LATEST_RELEASE).openConnection(proxy);
        } else {
            rawConnection = new URL(LATEST_RELEASE).openConnection();
        }
        HttpURLConnection connection = (HttpURLConnection) rawConnection;
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "PelmeniVPN-Updater/1");
        int status = connection.getResponseCode();
        if (status == 404) return null;
        if (status != 200) throw new Exception("GitHub HTTP " + status);

        byte[] bytes;
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > 256 * 1024) {
                    throw new Exception("Release response is too large");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } finally {
            connection.disconnect();
        }

        JSONObject release = new JSONObject(
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        String version = release.optString("tag_name", "").trim();
        if (version.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) {
            return null;
        }
        String page = release.optString("html_url",
                "https://github.com/sonofstrange/pelmeni-vpn/releases/latest");
        String download = page;
        long size = 0;
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    download = asset.optString("browser_download_url", page);
                    size = asset.optLong("size", 0);
                    break;
                }
            }
        }
        return new Result(version, release.optString("body", "").trim(),
                page, download, size);
    }

    static boolean isNewer(String candidate, String current) {
        int[] left = versionParts(candidate);
        int[] right = versionParts(current);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] versionParts(String value) {
        String clean = value.replaceFirst("^[^0-9]*", "");
        String[] raw = clean.split("[^0-9]+");
        int[] parts = new int[Math.min(raw.length, 4)];
        for (int i = 0; i < parts.length; i++) {
            try {
                parts[i] = Integer.parseInt(raw[i]);
            } catch (Exception ignored) {
                parts[i] = 0;
            }
        }
        return parts;
    }

    private UpdateChecker() {
    }
}
