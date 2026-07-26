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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Named destination lists and route calculation for split tunnelling. */
public final class SplitTunnel {
    public static final String MODE_BYPASS = "bypass";
    public static final String MODE_ONLY = "only";
    private static final String KEY_PROFILES = "split_profiles";
    private static final String KEY_ACTIVE = "split_active";
    private static final String KEY_ENABLED = "split_enabled";
    private static final String KEY_BUILTIN_VERSION = "split_builtin_version";
    private static final String BUILTIN_RUSSIAN_ID = "builtin_ru_bypass";
    private static final String BUILTIN_BRAWL_TEST_ID = "builtin_brawl_test";
    private static final int BUILTIN_VERSION = 5;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_ROUTES = 8192;

    private static final String[] RUSSIAN_SERVICES = {
            "yandex.ru", "ya.ru", "yandex.com", "yandex.net", "yastatic.net",
            "yandexcloud.net", "yandex-team.ru", "yandex-bank.net", "yango.com",
            "kinopoisk.ru", "dzen.ru", "auto.ru", "beru.ru",
            "vk.com", "vk.ru", "vk.me", "vkontakte.ru", "userapi.com",
            "vkuseraudio.net", "vk-cdn.net", "vk-portal.net", "vkuser.net",
            "mail.ru", "imgsmail.ru", "mycdn.me", "ok.ru", "odnoklassniki.ru",
            "gosuslugi.ru", "lk.gosuslugi.ru", "esia.gosuslugi.ru",
            "static.gosuslugi.ru", "oplata.gosuslugi.ru", "partners.gosuslugi.ru",
            "gu-st.ru", "government.ru", "kremlin.ru",
            "mos.ru", "mosreg.ru", "nalog.ru", "nalog.gov.ru", "sfr.gov.ru",
            "rosreestr.gov.ru", "fssp.gov.ru",
            "ozon.ru", "www.ozon.ru", "api.ozon.ru", "xapi.ozon.ru",
            "m.ozon.ru", "id.ozon.ru", "pay.ozon.ru", "ozon.travel",
            "ozonusercontent.com", "ozoncdn.com",
            "wildberries.ru", "wb.ru", "wbbasket.ru", "avito.ru", "avito.st",
            "avcdn.net", "cian.ru", "vkusvill.ru", "megamarket.ru",
            "dns-shop.ru", "citilink.ru", "mvideo.ru", "eldorado.ru",
            "lamoda.ru", "aliexpress.ru",
            "sber.ru", "sberbank.ru", "online.sberbank.ru", "sberdevices.ru",
            "sbermarket.ru", "sbermegamarket.ru", "domclick.ru",
            "tbank.ru", "tinkoff.ru", "tinkoffcdn.ru", "alfa-bank.ru",
            "vtb.ru", "gpb.ru", "gazprombank.ru", "psbank.ru", "rshb.ru",
            "sovcombank.ru", "mtsbank.ru", "raiffeisen.ru", "mkb.ru",
            "nspk.ru", "mironline.ru",
            "rutube.ru", "rutube.video", "rutube-cdn.ru", "ivi.ru", "okko.tv",
            "premier.one", "start.ru", "wink.ru", "more.tv", "amediateka.ru",
            "litres.ru",
            "2gis.ru", "2gis.com", "rambler.ru", "hh.ru",
            "ria.ru", "rbc.ru", "tass.ru", "rt.com", "lenta.ru", "gazeta.ru",
            "kommersant.ru", "kp.ru", "vesti.ru", "smotrim.ru", "1tv.ru",
            "ntv.ru", "ren.tv", "tnt-online.ru", "ctc.ru",
            "mts.ru", "megafon.ru", "beeline.ru", "t2.ru", "tele2.ru",
            "rt.ru", "rostelecom.ru",
            "rzd.ru", "aeroflot.ru", "s7.ru", "pobeda.aero", "tutu.ru",
            "aviasales.ru", "ostrovok.ru",
            "cdek.ru", "boxberry.ru", "pochta.ru", "delivery-club.ru",
            "kuper.ru", "yandexeda.ru",
            "dnevnik.ru", "uchi.ru", "stepik.org", "skillbox.ru",
            "geekbrains.ru", "netology.ru",
            "apteka.ru", "eapteka.ru", "rigla.ru", "gemotest.ru", "invitro.ru",
            "prodoctorov.ru"
    };

    /** Aggregated IPv4 announcements of major Russian service networks (RIPEstat). */
    private static final String[] RUSSIAN_SERVICE_PREFIXES = {
            "5.45.192.0/18", "5.61.16.0/21", "5.61.232.0/21", "5.101.40.0/22",
            "5.181.60.0/22", "5.188.140.0/22", "5.255.192.0/18",
            "109.207.0.0/20", "185.13.160.0/24", "185.71.64.0/22",
            "185.121.243.0/24", "193.84.78.0/24", "193.84.90.0/24",
            "31.177.104.0/22", "37.9.64.0/18", "37.139.32.0/22",
            "37.139.40.0/22", "37.140.128.0/18", "45.84.128.0/22",
            "45.136.20.0/22", "46.226.122.0/24", "62.217.160.0/20",
            "77.88.0.0/18", "78.155.198.0/24", "79.137.139.0/24",
            "79.137.157.0/24", "79.137.164.0/24", "79.137.174.0/23",
            "79.137.180.0/24", "79.137.183.0/24", "79.137.240.0/21",
            "80.67.40.0/22", "81.161.98.0/23", "83.166.232.0/21",
            "83.166.248.0/21", "83.217.216.0/22", "83.222.28.0/22",
            "84.23.52.0/22", "84.252.144.0/22", "84.252.149.0/24",
            "84.252.150.0/23", "84.252.152.0/24", "84.252.160.0/19",
            "85.142.115.0/24", "85.192.32.0/22", "85.198.76.0/22",
            "87.239.104.0/21", "87.240.128.0/18", "87.242.112.0/22",
            "87.250.224.0/19", "89.208.84.0/22", "89.208.196.0/22",
            "89.208.208.0/22", "89.208.216.0/21", "89.208.228.0/22",
            "89.221.228.0/22", "89.221.232.0/21", "90.156.148.0/22",
            "90.156.212.0/22", "90.156.216.0/22", "90.156.232.0/21",
            "90.156.247.0/24", "91.194.226.0/23", "91.206.127.0/24",
            "91.212.64.0/24", "91.217.194.0/24", "91.218.132.0/22",
            "91.219.224.0/22", "91.221.164.0/23", "91.221.198.0/23",
            "91.223.93.0/24", "91.230.107.0/24", "91.231.132.0/22",
            "91.233.216.0/22", "91.236.48.0/22", "92.38.217.0/24",
            "92.255.112.0/20", "93.158.128.0/18", "93.186.224.0/20",
            "94.100.176.0/20", "94.124.200.0/22", "94.124.206.0/23",
            "94.139.244.0/22", "95.108.128.0/17", "95.142.192.0/20",
            "95.163.32.0/19", "95.163.133.0/24", "95.163.180.0/22",
            "95.163.208.0/21", "95.163.216.0/22", "95.163.248.0/21",
            "95.213.0.0/17", "109.120.180.0/22", "109.120.188.0/22",
            "109.172.74.0/24", "109.238.88.0/24", "109.238.90.0/23",
            "128.140.168.0/21", "130.49.224.0/19", "132.243.176.0/22",
            "138.16.192.0/20", "138.16.240.0/20", "141.8.128.0/18",
            "146.185.208.0/22", "146.185.240.0/22", "155.212.192.0/20",
            "155.212.234.0/23", "161.104.104.0/21", "176.101.88.0/24",
            "176.101.90.0/24", "176.112.168.0/21", "176.114.120.0/21",
            "178.22.88.0/21", "178.130.128.0/23", "178.154.128.0/18",
            "178.237.16.0/20", "178.248.232.0/21", "185.5.136.0/22",
            "185.16.148.0/22", "185.16.244.0/22", "185.32.187.0/24",
            "185.32.248.0/22", "185.35.4.0/23", "185.35.6.0/24",
            "185.62.200.0/23", "185.62.202.0/24", "185.65.148.0/22",
            "185.66.84.0/22", "185.73.192.0/22", "185.86.144.0/22",
            "185.89.12.0/24", "185.89.14.0/23", "185.94.108.0/22",
            "185.100.104.0/22", "185.130.112.0/22", "185.131.68.0/22",
            "185.138.252.0/22", "185.157.96.0/23", "185.157.99.0/24",
            "185.169.152.0/22", "185.180.200.0/22", "185.187.63.0/24",
            "185.226.52.0/22", "185.241.192.0/22", "188.93.56.0/21",
            "193.203.40.0/22", "194.1.214.0/24", "194.54.14.0/23",
            "194.186.63.0/24", "194.190.0.0/24", "194.190.139.0/24",
            "195.34.20.0/23", "195.208.66.0/24", "195.211.20.0/22",
            "195.218.190.0/23", "212.11.151.0/24", "212.67.24.0/22",
            "212.111.84.0/22", "212.233.72.0/21", "212.233.80.0/22",
            "212.233.88.0/21", "212.233.96.0/22", "212.233.120.0/22",
            "213.59.252.0/22", "213.180.192.0/19", "213.184.155.0/24",
            "213.184.156.0/22", "213.219.212.0/22", "217.14.19.0/24",
            "217.16.16.0/20", "217.20.144.0/20", "217.69.128.0/20",
            "217.174.188.0/22"
    };

    /**
     * Diagnostic bypass captured from the installed Brawl Stars build. The EC2
     * range contains the observed game connection on TCP 9339; the domains are
     * official Supercell properties used for login and game content.
     */
    private static final String[] BRAWL_TEST_ENTRIES = {
            "brawlstars.com", "supercell.com", "supercellid.com",
            "id.supercell.com", "accounts.supercell.com",
            "proxy.social.supercell.com", "assets.social.supercell.com",
            "ingame-webviews.supercell.com",
            "44.224.0.0/11", "52.32.0.0/14"
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
        if (!store.contains(KEY_PROFILES)) {
            Profile russian = new Profile(
                    BUILTIN_RUSSIAN_ID, "Искл. российские сервисы",
                    MODE_BYPASS, builtinEntries(), true);
            Profile brawlTest = new Profile(
                    BUILTIN_BRAWL_TEST_ID, "ТЕСТ · Brawl Stars без VPN",
                    MODE_BYPASS, brawlTestEntries(), true);
            ArrayList<Profile> defaults = new ArrayList<>();
            defaults.add(russian);
            defaults.add(brawlTest);
            saveAll(store, defaults);
            store.putPlain(KEY_ACTIVE, russian.id);
            store.putBoolean(KEY_ENABLED, false);
            store.putPlain(KEY_BUILTIN_VERSION, String.valueOf(BUILTIN_VERSION));
            return;
        }

        int installedVersion;
        try {
            installedVersion = Integer.parseInt(store.getPlain(KEY_BUILTIN_VERSION, "0"));
        } catch (NumberFormatException ignored) {
            installedVersion = 0;
        }
        if (installedVersion >= BUILTIN_VERSION) return;

        try {
            JSONArray profiles = new JSONArray(store.getPlain(KEY_PROFILES, "[]"));
            ensureBuiltinProfile(profiles, BUILTIN_RUSSIAN_ID,
                    "Искл. российские сервисы", builtinEntries());
            ensureBuiltinProfile(profiles, BUILTIN_BRAWL_TEST_ID,
                    "ТЕСТ · Brawl Stars без VPN", brawlTestEntries());
            store.putPlain(KEY_PROFILES, profiles.toString());
            store.putPlain(KEY_BUILTIN_VERSION, String.valueOf(BUILTIN_VERSION));
        } catch (Exception ignored) {
            // Keep the user's existing data untouched if it cannot be migrated safely.
        }
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

    public static boolean isBrawlTest(Profile profile) {
        return profile != null && BUILTIN_BRAWL_TEST_ID.equals(profile.id);
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
        return resolve(enabled(store), active(store));
    }

    public static Routing resolve(boolean enabled, Profile profile) throws Exception {
        if (!enabled) {
            return new Routing(false, MODE_BYPASS, "", Collections.emptyList());
        }
        if (profile == null) throw new IllegalStateException("Не выбран список маршрутов");
        LinkedHashMap<String, Cidr> resolved = new LinkedHashMap<>();
        int workers = Math.max(1, Math.min(8, profile.entries.size()));
        ExecutorService resolver = Executors.newFixedThreadPool(workers);
        ArrayList<Future<List<Cidr>>> futures = new ArrayList<>();
        for (String entry : profile.entries) {
            futures.add(resolver.submit(() -> resolveEntry(entry)));
        }
        try {
            for (Future<List<Cidr>> future : futures) {
                try {
                    for (Cidr cidr : future.get()) {
                        resolved.put(cidr.address() + "/" + cidr.prefix, cidr);
                        if (resolved.size() >= MAX_ROUTES) break;
                    }
                } catch (Exception ignored) {
                }
                if (resolved.size() >= MAX_ROUTES) break;
            }
        } finally {
            resolver.shutdownNow();
        }
        ArrayList<Cidr> routes = compactRoutes(new ArrayList<>(resolved.values()));
        return new Routing(true, profile.mode, profile.name, routes);
    }

    private static ArrayList<Cidr> compactRoutes(List<Cidr> values) {
        ArrayList<Cidr> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingInt((Cidr value) -> value.prefix)
                .thenComparingLong(value -> value.network & 0xffffffffL));
        ArrayList<Cidr> compact = new ArrayList<>();
        for (Cidr candidate : sorted) {
            boolean covered = false;
            for (Cidr existing : compact) {
                if (existing.contains(candidate)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) compact.add(candidate);
        }
        return compact;
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
                array.put(profileJson(profile));
            }
        } catch (Exception ignored) {
        }
        store.putPlain(KEY_PROFILES, array.toString());
    }

    private static JSONObject profileJson(Profile profile) throws Exception {
        return new JSONObject()
                .put("id", profile.id)
                .put("name", cleanName(profile.name))
                .put("mode", profile.mode)
                .put("entries", new JSONArray(normalizeEntries(profile.entries)))
                .put("built_in", profile.builtIn);
    }

    private static List<String> builtinEntries() {
        ArrayList<String> entries = new ArrayList<>();
        Collections.addAll(entries, RUSSIAN_SERVICES);
        Collections.addAll(entries, RUSSIAN_SERVICE_PREFIXES);
        return entries;
    }

    private static List<String> brawlTestEntries() {
        ArrayList<String> entries = new ArrayList<>();
        Collections.addAll(entries, BRAWL_TEST_ENTRIES);
        return entries;
    }

    private static void ensureBuiltinProfile(
            JSONArray profiles, String id, String name, List<String> additions) throws Exception {
        JSONObject builtIn = null;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject candidate = profiles.optJSONObject(i);
            if (candidate != null && id.equals(candidate.optString("id"))) {
                builtIn = candidate;
                break;
            }
        }
        if (builtIn == null) {
            profiles.put(profileJson(new Profile(
                    id, name, MODE_BYPASS, additions, true)));
            return;
        }
        ArrayList<String> merged = new ArrayList<>();
        JSONArray current = builtIn.optJSONArray("entries");
        if (current != null) {
            for (int i = 0; i < current.length(); i++) {
                String entry = normalizeEntry(current.optString(i));
                if (!entry.isEmpty() && !merged.contains(entry)) merged.add(entry);
            }
        }
        for (String entry : additions) {
            if (!merged.contains(entry)) merged.add(entry);
        }
        builtIn.put("entries", new JSONArray(normalizeEntries(merged)));
        builtIn.put("built_in", true);
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
