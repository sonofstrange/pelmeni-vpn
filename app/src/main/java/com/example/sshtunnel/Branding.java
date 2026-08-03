package com.example.sshtunnel;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

final class Branding {
    static final String ACTION_CHANGED =
            "com.example.sshtunnel.branding.CHANGED";
    private static final String STATE_KEY = "developer_branding";

    private Branding() {}

    static String appName(Context context) {
        return context.getString(isDeveloperMode(context)
                ? R.string.debug_app_name : R.string.app_name);
    }

    static boolean isDeveloperMode(Context context) {
        return new SecureStore(context).getBoolean(STATE_KEY, false);
    }

    static boolean setDeveloperMode(Context context, boolean enabled) {
        SecureStore store = new SecureStore(context);
        store.putBoolean(STATE_KEY, enabled);
        if (!enabled) {
            store.putBoolean("beta_updates", false);
        }
        applyLauncherState(context, enabled);
        return enabled;
    }

    static boolean toggleDeveloperMode(Context context) {
        return setDeveloperMode(context, !isDeveloperMode(context));
    }

    static void restoreLauncherState(Context context) {
        applyLauncherState(context, isDeveloperMode(context));
    }

    private static void applyLauncherState(Context context, boolean developerMode) {
        PackageManager manager = context.getPackageManager();
        ComponentName pelmeni = new ComponentName(
                context, context.getPackageName() + ".PelmeniLauncher");
        ComponentName huyna = new ComponentName(
                context, context.getPackageName() + ".HuynaLauncher");
        manager.setComponentEnabledSetting(
                pelmeni,
                developerMode
                        ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(
                huyna,
                developerMode
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
