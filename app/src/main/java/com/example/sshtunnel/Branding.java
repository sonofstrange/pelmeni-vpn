package com.example.sshtunnel;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

final class Branding {
    private static final String SECRET_HOST = "6767@matrus";
    private static final String SECRET_PASSWORD = "хуйняVPN";

    private Branding() {}

    static boolean isSecretInput(String host, String password) {
        return SECRET_HOST.equals(host.trim()) && SECRET_PASSWORD.equals(password);
    }

    static boolean toggleSecret(Context context) {
        boolean enabled = !isSecret(context);
        new SecureStore(context).putBoolean("debug_brand", enabled);
        applyLauncherState(context, enabled);
        return enabled;
    }

    static void restoreLauncherState(Context context) {
        applyLauncherState(context, new SecureStore(context).getBoolean("debug_brand", false));
    }

    static boolean isSecret(Context context) {
        return new SecureStore(context).getBoolean("debug_brand", false);
    }

    static String appName(Context context) {
        return isSecret(context) ? "Huyna VPN" : "Пельмени VPN";
    }

    private static void applyLauncherState(Context context, boolean secret) {
        PackageManager manager = context.getPackageManager();
        ComponentName normal = new ComponentName(context, context.getPackageName() + ".PelmeniLauncher");
        ComponentName debug = new ComponentName(context, context.getPackageName() + ".HuynaLauncher");
        manager.setComponentEnabledSetting(normal,
                secret ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(debug,
                secret ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
