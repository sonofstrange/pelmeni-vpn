package com.example.sshtunnel;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PublicServerRegistry {
    private static final String API =
            "https://api.github.com/repos/sonofstrange/pelmeni-vpn/issues"
                    + "?state=open&labels=public-server&per_page=100";
    private static final String ISSUE =
            "https://github.com/sonofstrange/pelmeni-vpn/issues/new";
    private static final String MARKER = "PELMENI_PUBLIC_V1:";

    static final class Entry {
        final String poolId;
        final String name;
        final String location;
        final String host;
        final int sshPort;
        final String registrarUser;
        final String registrarPassword;
        final String hostKeyType;
        final String hostKey;
        final String fingerprint;
        final int days;
        final long dailyMb;
        final long monthlyMb;
        final long speedMbps;
        final int maxUsers;
        final boolean tls;
        final String issueUrl;
        final boolean verified;
        final String author;

        Entry(JSONObject json, String issueUrl) throws Exception {
            this(json, issueUrl, false, "");
        }

        Entry(JSONObject json, String issueUrl, boolean verified, String author) throws Exception {
            poolId = json.getString("pool_id");
            name = json.getString("name");
            location = json.optString("location", "");
            host = json.getString("host");
            sshPort = json.optInt("ssh_port", 22);
            registrarUser = json.getString("registrar_user");
            registrarPassword = json.getString("registrar_password");
            hostKeyType = json.getString("host_key_type");
            hostKey = json.getString("host_key");
            fingerprint = json.getString("fingerprint");
            days = json.optInt("days", 30);
            dailyMb = json.optLong("daily_mb", 0);
            monthlyMb = json.optLong("monthly_mb", 0);
            speedMbps = json.optLong("speed_mbps", 0);
            maxUsers = json.optInt("max_users", 50);
            tls = json.optBoolean("tls", false);
            this.issueUrl = issueUrl == null ? "" : issueUrl;
            this.verified = verified
                    || json.optBoolean("verified", false)
                    || json.optBoolean("official", false);
            this.author = author == null ? "" : author;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("format", 1)
                    .put("pool_id", poolId)
                    .put("name", name)
                    .put("location", location)
                    .put("host", host)
                    .put("ssh_port", sshPort)
                    .put("registrar_user", registrarUser)
                    .put("registrar_password", registrarPassword)
                    .put("host_key_type", hostKeyType)
                    .put("host_key", hostKey)
                    .put("fingerprint", fingerprint)
                    .put("days", days)
                    .put("daily_mb", dailyMb)
                    .put("monthly_mb", monthlyMb)
                    .put("speed_mbps", speedMbps)
                    .put("max_users", maxUsers)
                    .put("tls", tls)
                    .put("verified", verified);
        }

        String limitsLabel() {
            return "день: " + limit(dailyMb, "МБ")
                    + " · месяц: " + limit(monthlyMb, "МБ")
                    + " · скорость: " + limit(speedMbps, "Мбит/с")
                    + "\nСрок каждого доступа: "
                    + (days <= 0 ? "без срока" : days + " дн.")
                    + " · мест: до " + maxUsers;
        }

        private static String limit(long value, String unit) {
            return value <= 0 ? "∞" : value + " " + unit;
        }
    }

    static List<Entry> load() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Pelmeni-VPN-Android");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub вернул HTTP " + code);
            }
            JSONArray issues = new JSONArray(read(connection.getInputStream()));
            List<Entry> result = new ArrayList<>();
            for (int i = 0; i < issues.length(); i++) {
                JSONObject issue = issues.getJSONObject(i);
                String body = issue.optString("body", "");
                int marker = body.indexOf(MARKER);
                if (marker < 0) continue;
                int start = marker + MARKER.length();
                int end = body.indexOf("-->", start);
                String encoded = (end < 0 ? body.substring(start)
                        : body.substring(start, end)).trim();
                try {
                    byte[] decoded = Base64.decode(encoded,
                            Base64.URL_SAFE | Base64.NO_WRAP
                                    | Base64.NO_PADDING);
                    JSONObject json = new JSONObject(
                            new String(decoded, StandardCharsets.UTF_8));
                    if (json.optInt("format", 0) == 1) {
                        boolean verified = false;
                        String authorLogin = "";
                        JSONObject user = issue.optJSONObject("user");
                        if (user != null) {
                            authorLogin = user.optString("login", "");
                        }
                        String assoc = issue.optString("author_association", "");
                        if ("OWNER".equalsIgnoreCase(assoc)
                                || "MEMBER".equalsIgnoreCase(assoc)
                                || "COLLABORATOR".equalsIgnoreCase(assoc)
                                || "sonofstrange".equalsIgnoreCase(authorLogin)) {
                            verified = true;
                        }
                        JSONArray labels = issue.optJSONArray("labels");
                        if (labels != null) {
                            for (int l = 0; l < labels.length(); l++) {
                                JSONObject lbl = labels.optJSONObject(l);
                                if (lbl != null) {
                                    String lname = lbl.optString("name", "");
                                    if ("verified".equalsIgnoreCase(lname)
                                            || "official".equalsIgnoreCase(lname)
                                            || "verified-server".equalsIgnoreCase(lname)
                                            || "admin".equalsIgnoreCase(lname)) {
                                        verified = true;
                                        break;
                                    }
                                }
                            }
                        }
                        result.add(new Entry(
                                json, issue.optString("html_url", ""), verified, authorLogin));
                    }
                } catch (Exception ignored) {
                    // One malformed community entry must not hide valid servers.
                }
            }
            java.util.Collections.sort(result, (a, b) -> {
                if (a.verified != b.verified) {
                    return a.verified ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            });
            return result;
        } finally {
            connection.disconnect();
        }
    }

    static Uri publishUri(Entry entry) throws Exception {
        String encoded = Base64.encodeToString(
                entry.toJson().toString().getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String body = "<!-- " + MARKER + encoded + " -->\n\n"
                + "### Бесплатный сервер Пельмени VPN\n\n"
                + "- Название: " + entry.name + "\n"
                + "- Регион: " + (entry.location.isEmpty()
                ? "не указан" : entry.location) + "\n"
                + "- Лимиты: " + entry.limitsLabel() + "\n"
                + "- TLS: " + (entry.tls ? "да" : "нет") + "\n\n"
                + "Не редактируйте скрытый служебный маркер выше. "
                + "Чтобы убрать сервер из каталога, закройте Issue.";
        return Uri.parse(ISSUE).buildUpon()
                .appendQueryParameter("template", "public-server.md")
                .appendQueryParameter("title", "[PUBLIC] " + entry.name)
                .appendQueryParameter("body", body)
                .build();
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private PublicServerRegistry() {
    }
}
