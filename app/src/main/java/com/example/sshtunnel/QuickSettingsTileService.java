package com.example.sshtunnel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class QuickSettingsTileService extends TileService {
    static int iconResource(Context context) {
        return Branding.isDeveloperMode(context)
                ? R.drawable.ic_huyna_tile
                : R.drawable.ic_pelmeni_logo_tile_from_svg;
    }

    private android.content.BroadcastReceiver statusReceiver;

    public static void requestUpdate(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 24) return;
        try {
            TileService.requestListeningState(context,
                    new android.content.ComponentName(context, QuickSettingsTileService.class));
        } catch (Exception ignored) {
        }
    }

    @Override public void onStartListening() {
        super.onStartListening();
        if (statusReceiver == null) {
            statusReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    updateTile();
                }
            };
            IntentFilter filter = new IntentFilter(TunnelService.ACTION_STATUS);
            filter.addAction(Branding.ACTION_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
        }
        updateTile();
    }

    @Override public void onStopListening() {
        if (statusReceiver != null) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Exception ignored) {
            }
            statusReceiver = null;
        }
        super.onStopListening();
    }

    @Override public void onClick() {
        super.onClick();
        SecureStore store = new SecureStore(this);
        boolean running = store.getBoolean("enabled", false) && TunnelService.isActive();
        if (running) {
            // Persist the user's intent before STOP is handled so the tile and
            // an already visible activity become inactive immediately.
            store.putBoolean("enabled", false);
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
            updateTile();
            requestUpdate(this);
            return;
        }
        if (store.getBoolean("enabled", false)) store.putBoolean("enabled", false);
        QuickSettingsModeHistory.Mode mode =
                QuickSettingsModeHistory.restore(store);
        boolean vpnMode = mode.vpn;
        boolean proxyMode = mode.proxy;
        boolean setupRequired = ServerProfiles.active(store) == null
                || (!vpnMode && !proxyMode)
                || SshHostKeys.trustedFingerprint(store).isEmpty();
        if (setupRequired) {
            openAppForVpnPermission();
            return;
        }

        if (vpnMode && VpnService.prepare(this) != null) {
            openAppForVpnPermission();
            return;
        }

        Intent ssh = new Intent(this, TunnelService.class).setAction(TunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(ssh);
        else startService(ssh);
        if (vpnMode) {
            Intent vpn = VpnTunnelService.includeRoutingSnapshot(
                    new Intent(this, VpnTunnelService.class)
                            .setAction(VpnTunnelService.START), store);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn);
            else startService(vpn);
        }
        updateTile();
        requestUpdate(this);
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openAppForVpnPermission() {
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_START_FROM_TILE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pending = PendingIntent.getActivity(this, 2, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pending);
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        SecureStore store = new SecureStore(this);
        boolean active = TunnelService.isActive();
        boolean enabled = store.getBoolean("enabled", false);
        boolean connected = active && TunnelService.isConnected();
        boolean connecting = enabled && !connected;
        boolean disconnecting = !enabled && active;

        int state;
        String subtitle;
        if (connected) {
            state = Tile.STATE_ACTIVE;
            subtitle = TunnelMode.label(store);
        } else if (connecting) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = "Подключение…";
        } else if (disconnecting) {
            state = Tile.STATE_UNAVAILABLE;
            subtitle = "Отключение…";
        } else {
            state = Tile.STATE_INACTIVE;
            subtitle = "Выключено";
        }

        tile.setState(state);
        tile.setIcon(Icon.createWithResource(this, iconResource(this)));
        String name = Branding.appName(this);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setLabel(name);
            tile.setSubtitle(subtitle);
        } else {
            tile.setLabel(connected ? name + ": вкл."
                    : connecting ? name + ": подключение…"
                    : disconnecting ? name + ": отключение…" : name);
        }
        tile.updateTile();
    }
}
