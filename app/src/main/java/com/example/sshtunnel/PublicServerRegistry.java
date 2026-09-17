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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PublicServerRegistry {
    static final String REGISTRY_API = "http://185.176.94.10:8765/api/v1";
    private static final String API = REGISTRY_API + "/servers";
    private static final String MARKER = "PELMENI_PUBLIC_V1:";

    enum TrustLevel {
        OFFICIAL("Официальный", "🛡️", 0xFF22C55E),
        VERIFIED("Проверенный", "✓", 0xFF3B82F6),
        COMMUNITY("Публичный", "🌐", 0xFF9CA3AF),
        SUSPICIOUS("Подозрительный", "⚠", 0xFFEF4444);

        final String label;
        final String icon;
        final int color;

        TrustLevel(String label, String icon, int color) {
            this.label = label;
            this.icon = icon;
            this.color = color;
        }
    }

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
        final TrustLevel trustLevel;

        Entry(JSONObject json, String issueUrl) throws Exception {
            this(json, issueUrl, TrustLevel.COMMUNITY, "");
        }

        Entry(JSONObject json, String issueUrl, boolean verified, String author) throws Exception {
            this(json, issueUrl, verified ? TrustLevel.VERIFIED : TrustLevel.COMMUNITY, author);
        }

        Entry(JSONObject json, String issueUrl, TrustLevel trustLevel, String author) throws Exception {
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
            this.author = author == null ? "" : author;

            if (trustLevel != null && trustLevel != TrustLevel.COMMUNITY) {
                this.trustLevel = trustLevel;
            } else {
                String rawTrust = json.optString("trust_level", "");
                if ("OFFICIAL".equalsIgnoreCase(rawTrust)
                        || json.optBoolean("official", false)
                        || "sonofstrange".equalsIgnoreCase(author)
                        || (name != null && (name.toLowerCase(Locale.ROOT).contains("пельмен") || name.toLowerCase(Locale.ROOT).contains("pelmeni")))
                        || "31.76.110.227".equals(host)) {
                    this.trustLevel = TrustLevel.OFFICIAL;
                } else if ("VERIFIED".equalsIgnoreCase(rawTrust) || json.optBoolean("verified", false)) {
                    this.trustLevel = TrustLevel.VERIFIED;
                } else if ("SUSPICIOUS".equalsIgnoreCase(rawTrust) || json.optBoolean("suspicious", false)) {
                    this.trustLevel = TrustLevel.SUSPICIOUS;
                } else {
                    this.trustLevel = TrustLevel.COMMUNITY;
                }
            }
            this.verified = this.trustLevel == TrustLevel.OFFICIAL || this.trustLevel == TrustLevel.VERIFIED;
        }

        String locationFlag() {
            String loc = (location + " " + name).toLowerCase(Locale.ROOT);
            if (loc.contains("нидерланд") || loc.contains("netherland") || loc.contains("amsterdam") || loc.contains("holland") || loc.contains("nl")) return "🇳🇱";
            if (loc.contains("герман") || loc.contains("german") || loc.contains("frankfurt") || loc.contains("berlin") || loc.contains("de")) return "🇩🇪";
            if (loc.contains("финлянд") || loc.contains("finland") || loc.contains("helsinki") || loc.contains("fi")) return "🇫🇮";
            if (loc.contains("росси") || loc.contains("russia") || loc.contains("moscow") || loc.contains("saint") || loc.contains("ru")) return "🇷🇺";
            if (loc.contains("сша") || loc.contains("usa") || loc.contains("america") || loc.contains("united states") || loc.contains("us")) return "🇺🇸";
            if (loc.contains("великобритан") || loc.contains("uk") || loc.contains("england") || loc.contains("london") || loc.contains("gb")) return "🇬🇧";
            if (loc.contains("франци") || loc.contains("france") || loc.contains("paris") || loc.contains("fr")) return "🇫🇷";
            if (loc.contains("турци") || loc.contains("turkey") || loc.contains("istanbul") || loc.contains("tr")) return "🇹🇷";
            if (loc.contains("казахстан") || loc.contains("kazakhstan") || loc.contains("almaty") || loc.contains("kz")) return "🇰🇿";
            if (loc.contains("польш") || loc.contains("poland") || loc.contains("warsaw") || loc.contains("pl")) return "🇵🇱";
            if (loc.contains("швеци") || loc.contains("sweden") || loc.contains("stockholm") || loc.contains("se")) return "🇸🇪";
            if (loc.contains("япони") || loc.contains("japan") || loc.contains("tokyo") || loc.contains("jp")) return "🇯🇵";
            if (loc.contains("сингапур") || loc.contains("singapore") || loc.contains("sg")) return "🇸🇬";
            if (loc.contains("эстони") || loc.contains("estonia") || loc.contains("tallinn") || loc.contains("ee")) return "🇪🇪";
            if (loc.contains("латви") || loc.contains("latvia") || loc.contains("riga") || loc.contains("lv")) return "🇱🇻";
            if (loc.contains("литв") || loc.contains("lithuania") || loc.contains("vilnius") || loc.contains("lt")) return "🇱🇹";
            return "🌐";
        }

        String locationName() {
            if (location != null && !location.trim().isEmpty()) {
                return location.trim();
            }
            String flag = locationFlag();
            if ("🇳🇱".equals(flag)) return "Нидерланды";
            if ("🇩🇪".equals(flag)) return "Германия";
            if ("🇫🇮".equals(flag)) return "Финляндия";
            if ("🇷🇺".equals(flag)) return "Россия";
            if ("🇺🇸".equals(flag)) return "США";
            if ("🇬🇧".equals(flag)) return "Великобритания";
            if ("🇫🇷".equals(flag)) return "Франция";
            if ("🇹🇷".equals(flag)) return "Турция";
            return "Другие регионы";
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
                    .put("hostKeyType", hostKeyType)
                    .put("host_key", hostKey)
                    .put("fingerprint", fingerprint)
                    .put("days", days)
                    .put("daily_mb", dailyMb)
                    .put("monthly_mb", monthlyMb)
                    .put("speed_mbps", speedMbps)
                    .put("max_users", maxUsers)
                    .put("tls", tls)
                    .put("verified", verified)
                    .put("trust_level", trustLevel.name());
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

    static List<Entry> filter(List<Entry> entries, boolean showSuspicious) {
        if (entries == null) return Collections.emptyList();
        List<Entry> filtered = new ArrayList<>();
        for (Entry e : entries) {
            if (!showSuspicious && e.trustLevel == TrustLevel.SUSPICIOUS) continue;
            filtered.add(e);
        }
        return filtered;
    }

    static List<String> availableLocations(List<Entry> entries) {
        if (entries == null) return Collections.emptyList();
        List<String> locs = new ArrayList<>();
        for (Entry e : entries) {
            if (e.trustLevel == TrustLevel.SUSPICIOUS) continue;
            String loc = e.locationName();
            if (!locs.contains(loc)) locs.add(loc);
        }
        Collections.sort(locs);
        return locs;
    }

    static List<Entry> load() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(API).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", "Pelmeni-VPN-Android");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("Сервер вернул HTTP " + code);
            }
            JSONArray servers = new JSONArray(read(connection.getInputStream()));
            List<Entry> result = new ArrayList<>();
            for (int i = 0; i < servers.length(); i++) {
                try {
                    JSONObject json = servers.getJSONObject(i);
                    if (json.optInt("format", 0) != 1) continue;
                    String trustStr = json.optString("trust_level", "COMMUNITY");
                    TrustLevel trustLevel;
                    try {
                        trustLevel = TrustLevel.valueOf(trustStr.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        trustLevel = TrustLevel.COMMUNITY;
                    }
                    result.add(new Entry(json, json.optString("issue_url", ""), trustLevel, ""));
                } catch (Exception ignored) {
                    // Одна битая запись не должна скрывать остальные
                }
            }
            Collections.sort(result, (a, b) -> {
                if (a.trustLevel != b.trustLevel) {
                    return Integer.compare(a.trustLevel.ordinal(), b.trustLevel.ordinal());
                }
                return a.name.compareToIgnoreCase(b.name);
            });
            return result;
        } finally {
            connection.disconnect();
        }
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
