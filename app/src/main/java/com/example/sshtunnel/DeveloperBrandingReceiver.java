package com.example.sshtunnel;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * Developer branding control protected by a signature-level manifest permission.
 * No reusable password or activation value is present in the user APK.
 */
public final class DeveloperBrandingReceiver extends BroadcastReceiver {
    static final String ACTION_ENABLE =
            "com.example.sshtunnel.branding.ENABLE";
    static final String ACTION_DISABLE =
            "com.example.sshtunnel.branding.DISABLE";
    static final String ACTION_TOGGLE =
            "com.example.sshtunnel.branding.TOGGLE";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_ENABLE.equals(action)) {
            Branding.setDeveloperMode(context, true);
        } else if (ACTION_DISABLE.equals(action)) {
            Branding.setDeveloperMode(context, false);
        } else if (ACTION_TOGGLE.equals(action)) {
            Branding.toggleDeveloperMode(context);
        } else {
            return;
        }
        context.sendBroadcast(new Intent(Branding.ACTION_CHANGED)
                .setPackage(context.getPackageName()));
        if (Build.VERSION.SDK_INT >= 24) {
            TileService.requestListeningState(context,
                    new ComponentName(context, QuickSettingsTileService.class));
        }
    }
}
