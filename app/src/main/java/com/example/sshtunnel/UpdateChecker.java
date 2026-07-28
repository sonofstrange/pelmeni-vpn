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
    private static final String LATEST_STABLE =
            "https://api.github.com/repos/sonofstrange/pelmeni-vpn/releases/latest";
    private static final String ALL_RELEASES =
            "https://api.github.com/repos/sonofstrange/pelmeni-vpn/releases?per_page=20";

    static final class Result {
        final String version;
        final String notes;
        final String pageUrl;
        final String downloadUrl;
        final String sha256;
        final long size;
        final boolean prerelease;

        Result(String version, String notes, String pageUrl,
               String downloadUrl, String sha256, long size,
               boolean prerelease) {
            this.version = version;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.downloadUrl = downloadUrl;
            this.sha256 = sha256;
            this.size = size;
            this.prerelease = prerelease;
        }

        boolean canInstallSecurely() {
            return ApkUpdateInstaller.isTrustedDownloadUrl(downloadUrl)
                    && sha256.matches("[0-9a-fA-F]{64}");
        }
    }

    static Result check(SecureStore store) throws Exception {
        return check(store, false);
    }

    static Result check(SecureStore store, boolean includePrereleases) throws Exception {
        String endpoint = includePrereleases ? ALL_RELEASES : LATEST_STABLE;
        java.net.URLConnection rawConnection;
        if (TunnelService.isConnected()) {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress("127.0.0.1",
                            TunnelMode.testSocksPort(store)));
            rawConnection = new URL(endpoint).openConnection(proxy);
        } else {
            rawConnection = new URL(endpoint).openConnection();
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

        String response = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        JSONObject release;
        if (includePrereleases) {
            JSONArray releases = new JSONArray(response);
            release = newestRelease(releases);
            if (release == null) return null;
        } else {
            release = new JSONObject(response);
        }
        return resultFrom(release);
    }

    private static JSONObject newestRelease(JSONArray releases) {
        JSONObject newest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject candidate = releases.optJSONObject(i);
            if (candidate == null || candidate.optBoolean("draft", false)) continue;
            String version = candidate.optString("tag_name", "").trim();
            if (version.isEmpty()) continue;
            if (newest == null || isNewer(version,
                    newest.optString("tag_name", ""))) {
                newest = candidate;
            }
        }
        return newest;
    }

    static Result resultFrom(JSONObject release) {
        String version = release.optString("tag_name", "").trim();
        if (version.isEmpty() || !isNewer(version, BuildConfig.VERSION_NAME)) {
            return null;
        }
        String page = release.optString("html_url",
                "https://github.com/sonofstrange/pelmeni-vpn/releases/latest");
        String download = page;
        String sha256 = "";
        long size = 0;
        JSONArray assets = release.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    download = asset.optString("browser_download_url", page);
                    String digest = asset.optString("digest", "").trim();
                    if (digest.regionMatches(true, 0, "sha256:", 0, 7)) {
                        sha256 = digest.substring(7).trim();
                    }
                    size = asset.optLong("size", 0);
                    break;
                }
            }
        }
        return new Result(version, release.optString("body", "").trim(),
                page, download, sha256, size,
                release.optBoolean("prerelease", false));
    }

    static boolean isNewer(String candidate, String current) {
        Version left = Version.parse(candidate);
        Version right = Version.parse(current);
        for (int i = 0; i < 3; i++) {
            int a = left.parts[i];
            int b = right.parts[i];
            if (a != b) return a > b;
        }
        if (left.prerelease != right.prerelease) {
            return !left.prerelease;
        }
        return left.prerelease && left.prereleaseNumber > right.prereleaseNumber;
    }

    private static final class Version {
        final int[] parts;
        final boolean prerelease;
        final int prereleaseNumber;

        Version(int[] parts, boolean prerelease, int prereleaseNumber) {
            this.parts = parts;
            this.prerelease = prerelease;
            this.prereleaseNumber = prereleaseNumber;
        }

        static Version parse(String value) {
            String clean = value.trim().replaceFirst("^[^0-9]*", "");
            String[] halves = clean.split("-", 2);
            String[] rawParts = halves[0].split("\\.");
            int[] parts = new int[3];
            for (int i = 0; i < parts.length && i < rawParts.length; i++) {
                try {
                    parts[i] = Integer.parseInt(
                            rawParts[i].replaceAll("[^0-9].*$", ""));
                } catch (Exception ignored) {
                    parts[i] = 0;
                }
            }
            boolean prerelease = halves.length > 1;
            int prereleaseNumber = 0;
            if (prerelease) {
                String[] numbers = halves[1].split("[^0-9]+");
                for (String number : numbers) {
                    if (number.isEmpty()) continue;
                    try {
                        prereleaseNumber = Integer.parseInt(number);
                    } catch (Exception ignored) {
                    }
                }
            }
            return new Version(parts, prerelease, prereleaseNumber);
        }
    }

    private UpdateChecker() {
    }
}
