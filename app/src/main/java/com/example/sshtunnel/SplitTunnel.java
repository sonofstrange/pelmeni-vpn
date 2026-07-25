package com.example.sshtunnel;

import android.net.IpPrefix;
import android.net.VpnService;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Named destination lists and route calculation for split tunnelling. */
public final class SplitTunnel {
    public static final String MODE_BYPASS = "bypass";
    public static final String MODE_ONLY = "only";
    private static final String KEY_PROFILES = "split_profiles";
    private static final String KEY_ACTIVE = "split_active";
    private static final String KEY_ENABLED = "split_enabled";
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_ROUTES = 1024;

    private static final String[] RUSSIAN_SERVICES = {
            "yandex.ru", "ya.ru", "yandex.net", "yastatic.net", "yandexcloud.net",
            "kinopoisk.ru", "dzen.ru",
            "vk.com", "vk.ru", "userapi.com", "vkuseraudio.net", "vk-cdn.net",
            "mail.ru", "imgsmail.ru", "mycdn.me", "ok.ru",
            "gosuslugi.ru", "esia.gosuslugi.ru",
            "ozon.ru", "ozonusercontent.com", "ozoncdn.com",
            "wildberries.ru", "wb.ru", "wbbasket.ru",
            "sber.ru", "sberbank.ru", "sberdevices.ru",
            "tbank.ru", "tinkoff.ru", "tinkoffcdn.ru",
            "rutube.ru", "rutube.video", "rutube-cdn.ru",
            "avito.ru", "avito.st", "avcdn.net",
            "2gis.ru", "2gis.com", "rambler.ru"
    };

    private SplitTunnel() {
    }

    public static final class Profile {
        public final String id;
        public final String name;
        public final String mode;
        public final List<String> entries;
        public final boolean builtIn;

        Profile(String id, String name, String mode, List<String> entries, boolean builtIn) {
            this.id = id;
            this.name = name;
            this.mode = MODE_ONLY.equals(mode) ? MODE_ONLY : MODE_BYPASS;
            this.entries = new ArrayList<>(entries);
            this.builtIn = builtIn;
        }
    }

    public static final class Routing {
        public final boolean enabled;
        public final String mode;
        public final String profileName;
        public final List<Cidr> routes;

        Routing(boolean enabled, String mode, String profileName, List<Cidr> routes) {
            this.enabled = enabled;
            this.mode = mode;
            this.profileName = profileName;
            this.routes = routes;
        }

        public void apply(VpnService.Builder builder) throws Exception {
            if (!enabled) {
                builder.addRoute("0.0.0.0", 0).addDnsServer("198.18.0.2");
                return;
            }
            if (MODE_ONLY.equals(mode)) {
                if (routes.isEmpty()) {
                    throw new IllegalStateException("В активном списке нет доступных IP");
                }
                for (Cidr route : routes) builder.addRoute(route.address(), route.prefix);
                return;
            }
            builder.addRoute("0.0.0.0", 0);
            if (Build.VERSION.SDK_INT >= 33) {
                for (Cidr route : routes) {
                    builder.excludeRoute(new IpPrefix(
                            InetAddress.getByName(route.address()), route.prefix));
                }
                return;
            }
            List<Cidr> included = new ArrayList<>();
            included.add(new Cidr(0, 0));
            for (Cidr excluded : routes) {
                List<Cidr> next = new ArrayList<>();
                for (Cidr route : included) subtract(route, excluded, next);
                included = next;
                if (included.size() > MAX_ROUTES) {
                    throw new IllegalStateException("Слишком много маршрутов для этой версии Android");
                }
            }
            for (Cidr route : included) builder.addRoute(route.address(), route.prefix);
        }

        public String label() {
            if (!enabled) return "весь трафик";
            return (MODE_ONLY.equals(mode) ? "только список · " : "кроме списка · ")
                    + profileName + " · " + routes.size() + " IP";
        }
    }

    public static void ensureDefaults(SecureStore store) {
        if (store.contains(KEY_PROFILES)) return;
        ArrayList<String> entries = new ArrayList<>();
        Collections.addAll(entries, RUSSIAN_SERVICES);
        Profile russian = new Profile(
                "builtin_ru_bypass", "Искл. российские сервисы",
                MODE_BYPASS, entries, true);
        saveAll(store, Collections.singletonList(russian));
        store.putPlain(KEY_ACTIVE, russian.id);
        store.putBoolean(KEY_ENABLED, false);
    }

    public static boolean enabled(SecureStore store) {
        ensureDefaults(store);
        return store.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(SecureStore store, boolean enabled) {
        ensureDefaults(store);
        store.putBoolean(KEY_ENABLED, enabled);
    }

    public static List<Profile> list(SecureStore store) {
        ensureDefaults(store);
        ArrayList<Profile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store.getPlain(KEY_PROFILES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONArray values = item.optJSONArray("entries");
                ArrayList<String> entries = new ArrayList<>();
                if (values != null) {
                    for (int j = 0; j < values.length() && entries.size() < MAX_ENTRIES; j++) {
                        String value = normalizeEntry(values.optString(j));
                        if (!value.isEmpty() && !entries.contains(value)) entries.add(value);
                    }
                }
                profiles.add(new Profile(
                        item.optString("id", UUID.randomUUID().toString()),
                        item.optString("name", "Без названия"),
                        item.optString("mode", MODE_BYPASS),
                        entries,
                        item.optBoolean("built_in", false)));
            }
        } catch (Exception ignored) {
        }
        return profiles;
    }

    public static Profile active(SecureStore store) {
        String activeId = store.getPlain(KEY_ACTIVE, "");
        List<Profile> profiles = list(store);
        for (Profile profile : profiles) {
            if (profile.id.equals(activeId)) return profile;
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public static Profile create(String name, String mode, List<String> entries) {
        return new Profile(UUID.randomUUID().toString(), cleanName(name), mode,
                normalizeEntries(entries), false);
    }

    public static void saveAndActivate(SecureStore store, Profile profile) {
        List<Profile> profiles = list(store);
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) profiles.add(profile);
        saveAll(store, profiles);
        store.putPlain(KEY_ACTIVE, profile.id);
    }

    public static void activate(SecureStore store, String id) {
        for (Profile profile : list(store)) {
            if (profile.id.equals(id)) {
                store.putPlain(KEY_ACTIVE, id);
                return;
            }
        }
    }

    public static boolean delete(SecureStore store, String id) {
        List<Profile> profiles = list(store);
        if (profiles.size() <= 1) return false;
        boolean removed = false;
        for (int i = profiles.size() - 1; i >= 0; i--) {
            if (profiles.get(i).id.equals(id)) {
                profiles.remove(i);
                removed = true;
            }
        }
        if (!removed) return false;
        saveAll(store, profiles);
        store.putPlain(KEY_ACTIVE, profiles.get(0).id);
        return true;
    }

    public static String exportJson(Profile profile) throws Exception {
        return new JSONObject()
                .put("format", 1)
                .put("type", "pelmeni_split_tunnel")
                .put("name", profile.name)
                .put("mode", profile.mode)
                .put("entries", new JSONArray(profile.entries))
                .toString(2);
    }

    public static Profile importJson(String text) throws Exception {
        JSONObject json = new JSONObject(text);
        if (json.optInt("format", -1) != 1
                || !"pelmeni_split_tunnel".equals(json.optString("type"))) {
            throw new IllegalArgumentException("Неподдерживаемый формат");
        }
        JSONArray array = json.getJSONArray("entries");
        ArrayList<String> entries = new ArrayList<>();
        for (int i = 0; i < array.length() && entries.size() < MAX_ENTRIES; i++) {
            entries.add(array.getString(i));
        }
        return create(json.optString("name", "Импортированный список"),
                json.optString("mode", MODE_BYPASS), entries);
    }

    public static Routing resolve(SecureStore store) throws Exception {
        ensureDefaults(store);
        if (!enabled(store)) {
            return new Routing(false, MODE_BYPASS, "", Collections.emptyList());
        }
        Profile profile = active(store);
        if (profile == null) throw new IllegalStateException("Не выбран список маршрутов");
        LinkedHashMap<String, Cidr> resolved = new LinkedHashMap<>();
        for (String entry : profile.entries) {
            try {
                for (Cidr cidr : resolveEntry(entry)) {
                    resolved.put(cidr.address() + "/" + cidr.prefix, cidr);
                    if (resolved.size() >= MAX_ROUTES) break;
                }
            } catch (Exception ignored) {
            }
            if (resolved.size() >= MAX_ROUTES) break;
        }
        ArrayList<Cidr> routes = new ArrayList<>(resolved.values());
        routes.sort(Comparator.comparingInt((Cidr value) -> value.prefix)
                .thenComparingLong(value -> value.network & 0xffffffffL));
        return new Routing(true, profile.mode, profile.name, routes);
    }

    private static List<Cidr> resolveEntry(String raw) throws Exception {
        String entry = normalizeEntry(raw);
        if (entry.isEmpty()) return Collections.emptyList();
        int slash = entry.indexOf('/');
        if (slash > 0) {
            int prefix = Integer.parseInt(entry.substring(slash + 1));
            if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Неверный CIDR");
            InetAddress address = InetAddress.getByName(entry.substring(0, slash));
            if (!(address instanceof Inet4Address)) return Collections.emptyList();
            return Collections.singletonList(new Cidr(toInt(address), prefix));
        }
        if (entry.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            InetAddress address = InetAddress.getByName(entry);
            return Collections.singletonList(new Cidr(toInt(address), 32));
        }
        ArrayList<Cidr> routes = new ArrayList<>();
        for (InetAddress address : InetAddress.getAllByName(entry)) {
            if (address instanceof Inet4Address) routes.add(new Cidr(toInt(address), 32));
        }
        return routes;
    }

    private static List<String> normalizeEntries(List<String> values) {
        ArrayList<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = normalizeEntry(value);
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
            if (result.size() >= MAX_ENTRIES) break;
        }
        return result;
    }

    public static List<String> parseLines(String text) {
        ArrayList<String> values = new ArrayList<>();
        for (String line : text.split("[\\r\\n,;]+")) values.add(line);
        return normalizeEntries(values);
    }

    private static String normalizeEntry(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.startsWith("http://")) value = value.substring(7);
        if (value.startsWith("https://")) value = value.substring(8);
        int path = value.indexOf('/');
        if (path >= 0 && !value.substring(path + 1).matches("\\d{1,2}")) {
            value = value.substring(0, path);
        }
        if (value.startsWith("*.")) value = value.substring(2);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? "Без названия" : value;
    }

    private static void saveAll(SecureStore store, List<Profile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (Profile profile : profiles) {
                array.put(new JSONObject()
                        .put("id", profile.id)
                        .put("name", cleanName(profile.name))
                        .put("mode", profile.mode)
                        .put("entries", new JSONArray(normalizeEntries(profile.entries)))
                        .put("built_in", profile.builtIn));
            }
        } catch (Exception ignored) {
        }
        store.putPlain(KEY_PROFILES, array.toString());
    }

    private static int toInt(InetAddress address) {
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16)
                | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
    }

    private static void subtract(Cidr included, Cidr excluded, List<Cidr> output) {
        if (!included.overlaps(excluded)) {
            output.add(included);
            return;
        }
        if (excluded.contains(included)) return;
        if (included.prefix >= 32) return;
        int childPrefix = included.prefix + 1;
        int bit = 1 << (32 - childPrefix);
        subtract(new Cidr(included.network, childPrefix), excluded, output);
        subtract(new Cidr(included.network | bit, childPrefix), excluded, output);
    }

    public static final class Cidr {
        final int network;
        final int prefix;

        Cidr(int address, int prefix) {
            this.prefix = prefix;
            this.network = address & mask(prefix);
        }

        String address() {
            return ((network >>> 24) & 255) + "." + ((network >>> 16) & 255)
                    + "." + ((network >>> 8) & 255) + "." + (network & 255);
        }

        boolean contains(Cidr other) {
            return prefix <= other.prefix && (other.network & mask(prefix)) == network;
        }

        boolean overlaps(Cidr other) {
            return contains(other) || other.contains(this);
        }

        private static int mask(int prefix) {
            return prefix == 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
        }
    }
}
