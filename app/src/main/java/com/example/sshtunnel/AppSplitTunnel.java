package com.example.sshtunnel;

import android.content.pm.PackageManager;
import android.net.VpnService;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Per-server application allow/bypass rules for Android's VPN interface. */
final class AppSplitTunnel {
    static final String MODE_ONLY = "only";
    static final String MODE_BYPASS = "bypass";

    private static final String ENABLED_PREFIX = "app_split_enabled_";
    private static final String MODE_PREFIX = "app_split_mode_";
    private static final String PACKAGES_PREFIX = "app_split_packages_";

    private AppSplitTunnel() {
    }

    static final class Config {
        final boolean enabled;
        final String mode;
        final List<String> packages;

        Config(boolean enabled, String mode, List<String> packages) {
            this.enabled = enabled;
            this.mode = MODE_ONLY.equals(mode) ? MODE_ONLY : MODE_BYPASS;
            this.packages = normalize(packages);
        }
    }

    static Config load(SecureStore store) {
        String suffix = activeSuffix(store);
        return new Config(
                store.getBoolean(ENABLED_PREFIX + suffix, false),
                store.getPlain(MODE_PREFIX + suffix, MODE_BYPASS),
                decodePackages(store.getPlain(PACKAGES_PREFIX + suffix, "[]")));
    }

    static void save(SecureStore store, Config config) {
        String suffix = activeSuffix(store);
        store.putBoolean(ENABLED_PREFIX + suffix, config.enabled);
        store.putPlain(MODE_PREFIX + suffix, config.mode);
        store.putPlain(PACKAGES_PREFIX + suffix, encodePackages(config.packages));
    }

    static String summary(Config config) {
        if (!config.enabled) return "Выключено";
        String prefix = MODE_ONLY.equals(config.mode)
                ? "VPN только для выбранных"
                : "VPN для всех, кроме выбранных";
        return prefix + " · " + config.packages.size();
    }

    static void apply(
            VpnService.Builder builder, Config config, PackageManager packages,
            String ownPackage) throws Exception {
        if (!config.enabled) {
            disallowIfInstalled(builder, packages, ownPackage);
            disallowSystemCaptiveServices(builder, packages);
            return;
        }
        if (MODE_ONLY.equals(config.mode)) {
            int applied = 0;
            for (String packageName : config.packages) {
                if (ownPackage.equals(packageName)) continue;
                try {
                    builder.addAllowedApplication(packageName);
                    applied++;
                } catch (PackageManager.NameNotFoundException ignored) {
                    // An app may have been removed since the list was saved.
                }
            }
            if (applied == 0) {
                throw new IllegalStateException(
                        "В режиме «только выбранные приложения» список пуст");
            }
            return;
        }
        for (String packageName : config.packages) {
            disallowIfInstalled(builder, packages, packageName);
        }
        if (!config.packages.contains(ownPackage)) {
            disallowIfInstalled(builder, packages, ownPackage);
        }
        disallowSystemCaptiveServices(builder, packages);
    }

    private static void disallowSystemCaptiveServices(
            VpnService.Builder builder, PackageManager packages) {
        String[] systemServices = {
                "com.android.captiveportallogin",
                "com.google.android.captiveportallogin",
                "com.android.networkstack",
                "com.google.android.networkstack",
                "com.android.networkstack.process",
                "com.google.android.networkstack.process",
                "com.android.providers.settings"
        };
        for (String pkg : systemServices) {
            try {
                disallowIfInstalled(builder, packages, pkg);
            } catch (Exception ignored) {
            }
        }
    }

    static String encodePackages(List<String> packages) {
        JSONArray array = new JSONArray();
        for (String packageName : normalize(packages)) array.put(packageName);
        return array.toString();
    }

    static List<String> decodePackages(String json) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optString(i, ""));
            }
        } catch (Exception ignored) {
        }
        return normalize(result);
    }

    private static void disallowIfInstalled(
            VpnService.Builder builder, PackageManager packages, String packageName)
            throws Exception {
        if (packageName == null || packageName.trim().isEmpty()) return;
        try {
            packages.getApplicationInfo(packageName, 0);
            builder.addDisallowedApplication(packageName);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Keep saved selections when an app is temporarily uninstalled.
        }
    }

    private static List<String> normalize(List<String> packages) {
        Set<String> unique = new LinkedHashSet<>();
        if (packages != null) {
            for (String packageName : packages) {
                if (packageName == null) continue;
                String clean = packageName.trim();
                if (!clean.isEmpty()) unique.add(clean);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String activeSuffix(SecureStore store) {
        ServerProfiles.Profile active = ServerProfiles.active(store);
        return active == null ? "default" : active.id;
    }
}
