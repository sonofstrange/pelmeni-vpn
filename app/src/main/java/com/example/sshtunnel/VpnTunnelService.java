package com.example.sshtunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Routes the device TUN interface into the SSH SOCKS5 listener. */
public class VpnTunnelService extends VpnService {
    public static final String START = "vpn_start";
    public static final String STOP = "vpn_stop";
    public static final String EXTRA_STOP_SSH = "stop_ssh";
    private static final String CHANNEL = "tunnel";
    private static final int ID = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile boolean nativeStarted;
    private ParcelFileDescriptor tun;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopVpn(intent.getBooleanExtra(EXTRA_STOP_SSH, true));
            return START_NOT_STICKY;
        }
        if (tun == null && !starting) startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        starting = true;
        startAsForeground("VPN: ожидание SSH-туннеля…");
        worker.execute(() -> {
            int socksPort = TunnelMode.vpnSocksPort(new SecureStore(this));
            if (!waitForSocks(socksPort)) {
                send("SOCKS5 не запустился — VPN остановлен");
                stopVpn(true);
                return;
            }
            establishVpn(socksPort);
        });
    }

    private void establishVpn(int socksPort) {
        try {
            int mtu = NetworkTuning.vpnMtu(new SecureStore(this));
            VpnService.Builder builder = new VpnService.Builder()
                    .setSession(Branding.appName(this))
                    .setMtu(mtu)
                    .setBlocking(false)
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("198.18.0.2");
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
            send("VPN подключён: весь TCP-трафик через SSH");
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
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
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
}
