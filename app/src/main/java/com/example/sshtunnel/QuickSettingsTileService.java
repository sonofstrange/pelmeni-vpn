package com.example.sshtunnel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
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

    @Override public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        SecureStore store = new SecureStore(this);
        boolean running = store.getBoolean("enabled", false) && TunnelService.isActive();
        if (running) {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
            updateTile();
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
        boolean enabled = store.getBoolean("enabled", false) && TunnelService.isActive();
        boolean connected = enabled && TunnelService.isConnected();
        tile.setState(connected ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this, iconResource(this)));
        String name = Branding.appName(this);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setLabel(name);
            tile.setSubtitle(connected
                    ? TunnelMode.label(store)
                    : enabled ? "Подключение…" : "Выключено");
        } else {
            tile.setLabel(connected ? name + ": вкл."
                    : enabled ? name + ": подключение…" : name);
        }
        tile.updateTile();
    }
}
