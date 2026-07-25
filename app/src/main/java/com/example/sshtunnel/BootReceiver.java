package com.example.sshtunnel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SecureStore store = new SecureStore(context);
        if (!store.getBoolean("start_on_boot", false) || !store.getBoolean("enabled", false)) return;

        Intent service = new Intent(context, TunnelService.class).setAction(TunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
        if (store.getBoolean("vpn_mode", false)) {
            Intent vpn = new Intent(context, VpnTunnelService.class).setAction(VpnTunnelService.START);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(vpn);
            else context.startService(vpn);
        }
    }
}
