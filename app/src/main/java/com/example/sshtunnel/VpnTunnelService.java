package com.example.sshtunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Routes the device TUN interface into the SSH SOCKS5 listener. */
public class VpnTunnelService extends VpnService {
    public static final String START = "vpn_start";
    public static final String STOP = "vpn_stop";
    public static final String RELOAD_ROUTES = "vpn_reload_routes";
    public static final String RESTART_TRANSPORT = "vpn_restart_transport";
    public static final String UPDATE_UNDERLYING_NETWORK =
            "vpn_update_underlying_network";
    public static final String EXTRA_STOP_SSH = "stop_ssh";
    private static final String EXTRA_SPLIT_SNAPSHOT = "split_snapshot";
    private static final String EXTRA_SPLIT_ENABLED = "split_enabled";
    private static final String EXTRA_SPLIT_MODE = "split_mode";
    private static final String EXTRA_SPLIT_NAME = "split_name";
    private static final String EXTRA_SPLIT_ENTRIES = "split_entries";
    private static final String EXTRA_UNDERLYING_NETWORK = "underlying_network";
    private static final String EXTRA_UNDERLYING_AVAILABLE =
            "underlying_network_available";
    private static final String CHANNEL = "tunnel";
    private static final int ID = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile boolean nativeStarted;
    private volatile Network underlyingNetwork;
    private volatile boolean underlyingUnavailable;
    private ParcelFileDescriptor tun;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopVpn(intent.getBooleanExtra(EXTRA_STOP_SSH, true));
            return START_NOT_STICKY;
        }
        if (intent != null && RELOAD_ROUTES.equals(intent.getAction())) {
            reloadRoutes(readRoutingSnapshot(intent));
            return START_STICKY;
        }
        if (intent != null
                && UPDATE_UNDERLYING_NETWORK.equals(intent.getAction())) {
            applyUnderlyingNetwork(intent);
            if (tun == null && !starting) startVpn(null);
            return START_STICKY;
        }
        if (intent != null && RESTART_TRANSPORT.equals(intent.getAction())) {
            applyUnderlyingNetwork(intent);
            restartTransport(readRoutingSnapshot(intent));
            return START_STICKY;
        }
        if (tun == null && !starting) startVpn(readRoutingSnapshot(intent));
        return START_STICKY;
    }

    public static Intent includeRoutingSnapshot(Intent intent, SecureStore store) {
        boolean enabled = SplitTunnel.enabled(store);
        SplitTunnel.Profile profile = SplitTunnel.combined(store);
        intent.putExtra(EXTRA_SPLIT_SNAPSHOT, true)
                .putExtra(EXTRA_SPLIT_ENABLED, enabled);
        if (profile != null) {
            intent.putExtra(EXTRA_SPLIT_MODE, profile.mode)
                    .putExtra(EXTRA_SPLIT_NAME, profile.name)
                    .putStringArrayListExtra(EXTRA_SPLIT_ENTRIES,
                            new ArrayList<>(profile.entries));
        }
        return intent;
    }

    static Intent includeUnderlyingNetwork(Intent intent, Network network) {
        intent.putExtra(EXTRA_UNDERLYING_AVAILABLE, network != null);
        if (network != null) intent.putExtra(EXTRA_UNDERLYING_NETWORK, network);
        return intent;
    }

    @SuppressWarnings("deprecation")
    private void applyUnderlyingNetwork(Intent intent) {
        boolean available =
                intent.getBooleanExtra(EXTRA_UNDERLYING_AVAILABLE, false);
        Network network = null;
        if (available) {
            network = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(
                            EXTRA_UNDERLYING_NETWORK, Network.class)
                    : intent.getParcelableExtra(EXTRA_UNDERLYING_NETWORK);
        }
        underlyingNetwork = network;
        underlyingUnavailable = network == null;
        if (tun != null) {
            setUnderlyingNetworks(network == null
                    ? new Network[0] : new Network[] {network});
        }
    }

    private RoutingSnapshot readRoutingSnapshot(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SPLIT_SNAPSHOT, false)) {
            return null;
        }
        ArrayList<String> entries = intent.getStringArrayListExtra(EXTRA_SPLIT_ENTRIES);
        SplitTunnel.Profile profile = entries == null ? null : SplitTunnel.create(
                intent.getStringExtra(EXTRA_SPLIT_NAME),
                intent.getStringExtra(EXTRA_SPLIT_MODE), entries);
        return new RoutingSnapshot(
                intent.getBooleanExtra(EXTRA_SPLIT_ENABLED, false), profile);
    }

    private void reloadRoutes(RoutingSnapshot snapshot) {
        starting = true;
        startAsForeground("VPN: применяем маршруты…");
        worker.execute(() -> {
            if (stopping) return;
            cleanupNative();
            int socksPort = TunnelMode.vpnSocksPort(new SecureStore(this));
            if (!waitForSocks(socksPort)) {
                send("SOCKS5 не запустился — VPN остановлен");
                stopVpn(true);
                return;
            }
            establishVpn(socksPort, snapshot);
        });
    }

    private synchronized void restartTransport(RoutingSnapshot snapshot) {
        if (starting || stopping) return;
        starting = true;
        startAsForeground("VPN: обновляем транспорт после смены сети…");
        worker.execute(() -> {
            if (stopping) return;
            cleanupNative();
            int socksPort = TunnelMode.vpnSocksPort(new SecureStore(this));
            if (!waitForSocks(socksPort)) {
                send("SOCKS5 не запустился после смены сети — VPN остановлен");
                stopVpn(true);
                return;
            }
            establishVpn(socksPort, snapshot);
        });
    }

    private void startVpn(RoutingSnapshot snapshot) {
        starting = true;
        startAsForeground("VPN: ожидание SSH-туннеля…");
        worker.execute(() -> {
            int socksPort = TunnelMode.vpnSocksPort(new SecureStore(this));
            if (!waitForSocks(socksPort)) {
                send("SOCKS5 не запустился — VPN остановлен");
                stopVpn(true);
                return;
            }
            establishVpn(socksPort, snapshot);
        });
    }

    private void establishVpn(int socksPort, RoutingSnapshot snapshot) {
        try {
            SecureStore store = new SecureStore(this);
            int mtu = NetworkTuning.vpnMtu(store);
            SplitTunnel.Routing routing = snapshot == null
                    ? SplitTunnel.resolve(store)
                    : SplitTunnel.resolve(snapshot.enabled, snapshot.profile);
            VpnService.Builder builder = new VpnService.Builder()
                    .setSession(Branding.appName(this))
                    .setMtu(mtu)
                    .setBlocking(false)
                    .addAddress("198.18.0.1", 32);
            Network currentUnderlying = underlyingNetwork;
            if (currentUnderlying != null) {
                builder.setUnderlyingNetworks(
                        new Network[] {currentUnderlying});
            } else if (underlyingUnavailable) {
                builder.setUnderlyingNetworks(new Network[0]);
            }
            routing.apply(builder);
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
            }
            tun = builder.establish();
            if (tun == null) {
                send("Android не разрешил создать VPN");
                stopVpn(true);
                return;
            }

            File config = new File(getCacheDir(), "vpn-tun2socks.yml");
            String text = "misc:\n  task-stack-size: 86016\n"
                    + "tunnel:\n  mtu: " + mtu + "\n  icmp: 'reply'\n"
                    + "socks5:\n  address: '127.0.0.1'\n  port: " + socksPort + "\n  pipeline: true\n"
                    + "mapdns:\n  address: 198.18.0.2\n  port: 53\n"
                    + "  network: 240.0.0.0\n  netmask: 240.0.0.0\n"
                    + "  cache-size: 10000\n";
            try (FileOutputStream output = new FileOutputStream(config, false)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            hev.sockstun.TProxyService.TProxyStartService(config.getAbsolutePath(), tun.getFd());
            nativeStarted = true;
            starting = false;
            send("VPN подключён: " + routing.label());
        } catch (Throwable error) {
            send("Ошибка запуска VPN: " + error.getClass().getSimpleName());
            stopVpn(true);
        }
    }

    private boolean waitForSocks(int port) {
        for (int attempt = 0; attempt < 120 && !stopping; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private synchronized void stopVpn(boolean stopSsh) {
        if (stopping) {
            stopSelf();
            return;
        }
        stopping = true;
        cleanupNative();
        if (stopSsh) stopSshService();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanupNative() {
        if (nativeStarted) {
            try {
                hev.sockstun.TProxyService.TProxyStopService();
            } catch (Throwable ignored) {
            }
            nativeStarted = false;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        File config = new File(getCacheDir(), "vpn-tun2socks.yml");
        if (config.exists()) config.delete();
    }

    private void stopSshService() {
        try {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
        } catch (Exception ignored) {
        }
    }

    @Override public void onRevoke() {
        stopVpn(true);
        super.onRevoke();
    }

    @Override public void onDestroy() {
        if (!stopping) {
            stopping = true;
            cleanupNative();
            stopSshService();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void startAsForeground(String text) {
        Notification notification = notification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, notification);
        }
    }

    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Пельмени VPN", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TunnelService.class).setAction(TunnelService.STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(QuickSettingsTileService.iconResource(this))
                .setContentTitle(Branding.appName(this) + " · "
                        + TunnelMode.label(new SecureStore(this)))
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Отключить", stopIntent).build())
                .build();
    }

    private void send(String text) {
        sendBroadcast(new Intent(TunnelService.ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra("status", text));
        getSystemService(NotificationManager.class).notify(ID, notification(text));
    }

    private static final class RoutingSnapshot {
        final boolean enabled;
        final SplitTunnel.Profile profile;

        RoutingSnapshot(boolean enabled, SplitTunnel.Profile profile) {
            this.enabled = enabled;
            this.profile = profile;
        }
    }
}
