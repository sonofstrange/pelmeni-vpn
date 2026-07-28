package com.example.sshtunnel;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;

import com.jcraft.jsch.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TunnelService extends Service {
    public static final String START = "start";
    public static final String STOP = "stop";
    public static final String ACTION_STATUS = "com.example.sshtunnel.STATUS";

    private static final String CHANNEL = "tunnel";
    private static final int ID = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsWorker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean loopRunning = new AtomicBoolean(false);
    private volatile boolean wanted = false;
    private volatile boolean forceReconnect = false;
    private volatile Session session;
    private volatile Session vpnSession;
    private volatile SocksProxy telegramSocksProxy;
    private volatile SocksProxy vpnSocksProxy;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network activeUnderlyingNetwork;
    private volatile Network pendingUnderlyingNetwork;
    private ScheduledFuture<?> pendingNetworkSwitch;
    private long networkSwitchGeneration;
    private boolean statsStarted;
    private long sessionUploaded;
    private long sessionDownloaded;
    private long totalUploaded;
    private long totalDownloaded;
    private long lastSampleBytes;
    private long lastSampleTime;
    private int statsTicks;
    private volatile String currentStatus = "Подключение…";
    private volatile long currentSpeed;
    private volatile int currentLatency = -1;
    private static volatile boolean serviceActive;
    private static volatile boolean connected;

    @Override public void onCreate() {
        super.onCreate();
        serviceActive = true;
        createChannel();
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        SecureStore store = new SecureStore(this);
        totalUploaded = store.getLong("total_uploaded", 0);
        totalDownloaded = store.getLong("total_downloaded", 0);
    }

    static boolean isActive() {
        return serviceActive;
    }

    static boolean isConnected() {
        return serviceActive && connected;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            wanted = false;
            connected = false;
            new SecureStore(this).putBoolean("enabled", false);
            startService(new Intent(this, VpnTunnelService.class)
                    .setAction(VpnTunnelService.STOP)
                    .putExtra(VpnTunnelService.EXTRA_STOP_SSH, false));
            disconnect();
            persistTotals();
            send("Отключено");
            stopForeground(STOP_FOREGROUND_REMOVE);
            getSystemService(NotificationManager.class).cancel(ID);
            stopSelf();
            return START_NOT_STICKY;
        }

        SecureStore store = new SecureStore(this);
        if (intent == null && !store.getBoolean("enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!wanted) {
            sessionUploaded = 0;
            sessionDownloaded = 0;
            lastSampleBytes = 0;
            lastSampleTime = SystemClock.elapsedRealtime();
            statsTicks = 0;
            startStats();
        }
        wanted = true;
        connected = false;
        store.putBoolean("enabled", true);
        startForeground(ID, note("Подключение…"));
        watchNetworks();
        startConnectionLoop();
        return START_STICKY;
    }

    private synchronized void watchNetworks() {
        if (networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                // Android delivers the ordered capabilities immediately afterwards.
                // Selecting here would race with validation and link setup.
            }

            @Override public void onCapabilitiesChanged(
                    Network network, NetworkCapabilities capabilities) {
                boolean notSuspended = Build.VERSION.SDK_INT < 28
                        || capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
                boolean usable = UnderlyingNetworkPolicy.usable(
                        capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        capabilities.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                        notSuspended);
                if (usable) {
                    stageUnderlyingNetwork(network);
                } else {
                    discardUnderlyingNetwork(network);
                }
            }

            @Override public void onLost(Network network) {
                discardUnderlyingNetwork(network);
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    private void stageUnderlyingNetwork(Network network) {
        long generation;
        long delay;
        synchronized (this) {
            if (network.equals(activeUnderlyingNetwork)
                    || network.equals(pendingUnderlyingNetwork)) {
                return;
            }
            pendingUnderlyingNetwork = network;
            generation = ++networkSwitchGeneration;
            if (pendingNetworkSwitch != null) pendingNetworkSwitch.cancel(false);
            delay = activeUnderlyingNetwork == null
                    ? 0 : UnderlyingNetworkPolicy.SWITCH_STABILIZATION_MS;
            pendingNetworkSwitch = statsWorker.schedule(
                    () -> commitUnderlyingNetwork(network, generation),
                    delay, TimeUnit.MILLISECONDS);
        }
    }

    private void commitUnderlyingNetwork(Network network, long generation) {
        boolean handoff;
        synchronized (this) {
            if (!wanted || generation != networkSwitchGeneration
                    || !network.equals(pendingUnderlyingNetwork)) {
                return;
            }
            Network previous = activeUnderlyingNetwork;
            handoff = previous != null && !network.equals(previous);
            activeUnderlyingNetwork = network;
            pendingUnderlyingNetwork = null;
            pendingNetworkSwitch = null;
            notifyAll();
        }
        // Initial network discovery wakes connectOnce(), which is waiting for
        // this exact network. Reconnecting here used to cancel the first SSH
        // attempt and made startup succeed only on the retry.
        boolean reconnecting = UnderlyingNetworkPolicy.reconnectAfterCommit(handoff)
                && requestReconnect(
                "Сеть стабилизировалась, обновляем VPN-соединение…");
        if (reconnecting) {
            restartVpnTransport(network);
        } else {
            updateVpnUnderlyingNetwork(network);
        }
    }

    private void discardUnderlyingNetwork(Network network) {
        boolean activeLost = false;
        synchronized (this) {
            if (network.equals(pendingUnderlyingNetwork)) {
                pendingUnderlyingNetwork = null;
                networkSwitchGeneration++;
                if (pendingNetworkSwitch != null) {
                    pendingNetworkSwitch.cancel(false);
                    pendingNetworkSwitch = null;
                }
            }
            if (network.equals(activeUnderlyingNetwork)) {
                activeUnderlyingNetwork = null;
                activeLost = true;
                notifyAll();
            }
        }
        if (activeLost) {
            boolean reconnecting =
                    requestReconnect("Сеть потеряна, ожидаем новый маршрут…");
            if (reconnecting) {
                restartVpnTransport(null);
            } else {
                updateVpnUnderlyingNetwork(null);
            }
        }
    }

    private Network awaitUnderlyingNetwork(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (this) {
            while (wanted && activeUnderlyingNetwork == null) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                wait(remaining);
            }
            return activeUnderlyingNetwork;
        }
    }

    private synchronized boolean requestReconnect(String message) {
        if (!wanted) return false;
        if (!new SecureStore(this).getBoolean("auto_reconnect", true)) return false;
        if (forceReconnect) return false;
        forceReconnect = true;
        connected = false;
        send(message);
        disconnect();
        startConnectionLoop();
        return true;
    }

    private void restartVpnTransport(Network network) {
        SecureStore store = new SecureStore(this);
        if (!store.getBoolean("vpn_mode", false)) return;
        Intent intent = VpnTunnelService.includeRoutingSnapshot(
                new Intent(this, VpnTunnelService.class)
                        .setAction(VpnTunnelService.RESTART_TRANSPORT), store);
        VpnTunnelService.includeUnderlyingNetwork(intent, network);
        startForegroundService(intent);
    }

    private void updateVpnUnderlyingNetwork(Network network) {
        SecureStore store = new SecureStore(this);
        if (!store.getBoolean("vpn_mode", false)) return;
        Intent intent = new Intent(this, VpnTunnelService.class)
                .setAction(VpnTunnelService.UPDATE_UNDERLYING_NETWORK);
        VpnTunnelService.includeUnderlyingNetwork(intent, network);
        startForegroundService(intent);
    }

    private void startConnectionLoop() {
        if (!loopRunning.compareAndSet(false, true)) return;
        worker.submit(() -> {
            int delaySeconds = 2;
            try {
                while (wanted) {
                    forceReconnect = false;
                    try {
                        connectOnce();
                        delaySeconds = 2;
                        monitorConnection();
                    } catch (JSchException e) {
                        if (!wanted) break;
                        connected = false;
                        String message = classifyError(e);
                        send(message + " Повтор через " + delaySeconds + " сек…");
                    } catch (Exception e) {
                        if (!wanted) break;
                        connected = false;
                        send("SSH-соединение потеряно. Повтор через " + delaySeconds + " сек…");
                    } finally {
                        disconnect();
                    }

                    if (!wanted) break;
                    sleepInterruptibly(delaySeconds * 1000L);
                    delaySeconds = Math.min(delaySeconds * 2, 30);
                }
            } finally {
                loopRunning.set(false);
                if (wanted) startConnectionLoop();
            }
        });
    }

    private void connectOnce() throws Exception {
        SecureStore store = new SecureStore(this);
        ServerProfiles.migratePerformanceDefaults(store);
        Network underlyingNetwork = awaitUnderlyingNetwork(5_000);
        if (underlyingNetwork == null) {
            throw new JSchException("no validated physical network");
        }
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        int sshPort = parsePort(store.getPlain("port", "22"), 22);
        boolean telegramEnabled = store.getBoolean("telegram_proxy", true);
        boolean vpnEnabled = store.getBoolean("vpn_mode", false);
        int telegramPort = TunnelMode.telegramSocksPort(store);
        int vpnPort = TunnelMode.vpnSocksPort(store);
        int windowSize = NetworkTuning.windowKiB(store) * 1024;
        int packetSize = NetworkTuning.packetKiB(store) * 1024;
        TlsTransport.enableAutomatically(store, host);
        boolean tlsProtected = TlsTransport.isEnabledFor(store, host);

        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            throw new JSchException("missing settings");
        }

        send("Подключение к " + host
                + (tlsProtected ? " через защищённый TLS…" : "…"));
        Session newSession = connectSession(
                store, host, user, password, sshPort, tlsProtected,
                windowSize, underlyingNetwork);
        Session newVpnSession = null;
        SocksProxy newTelegramProxy = null;
        SocksProxy newVpnProxy = null;
        try {
            if (telegramEnabled && vpnEnabled) {
                newVpnSession = connectSession(
                        store, host, user, password, sshPort, tlsProtected,
                        windowSize, underlyingNetwork);
            }
            if (telegramEnabled) {
                newTelegramProxy = new SocksProxy(
                        newSession, telegramPort, windowSize, packetSize);
                newTelegramProxy.start();
            }
            if (vpnEnabled) {
                newVpnProxy = new SocksProxy(
                        newVpnSession == null ? newSession : newVpnSession,
                        vpnPort, windowSize, packetSize);
                newVpnProxy.start();
            }
            if (!wanted || forceReconnect) throw new JSchException("reconnect requested");

            session = newSession;
            vpnSession = newVpnSession;
            telegramSocksProxy = newTelegramProxy;
            vpnSocksProxy = newVpnProxy;
            connected = true;
        } catch (Exception error) {
            if (newTelegramProxy != null) newTelegramProxy.close();
            if (newVpnProxy != null) newVpnProxy.close();
            newSession.disconnect();
            if (newVpnSession != null) newVpnSession.disconnect();
            throw error;
        }

        send("Подключено · " + TunnelMode.portsLabel(store));
    }

    private Session connectSession(
            SecureStore store, String host, String user, String password,
            int sshPort, boolean tlsProtected, int windowSize,
            Network underlyingNetwork) throws Exception {
        Session result = SshHostKeys.newPinnedSession(
                store, user, host, sshPort);
        result.setPassword(password);
        result.setSocketFactory(tlsProtected
                ? TlsTransport.socketFactory(
                        store, underlyingNetwork, windowSize)
                : new LowLatencySocketFactory(
                        underlyingNetwork, windowSize));
        result.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        result.setConfig("cipher.c2s",
                "aes128-gcm@openssh.com,aes128-ctr,aes256-gcm@openssh.com,aes256-ctr");
        result.setConfig("cipher.s2c",
                "aes128-gcm@openssh.com,aes128-ctr,aes256-gcm@openssh.com,aes256-ctr");
        result.setConfig("max_input_buffer_size", Integer.toString(windowSize));
        result.setServerAliveInterval(15_000);
        result.setServerAliveCountMax(3);
        result.connect(20_000);
        return result;
    }

    private void monitorConnection() throws Exception {
        while (wanted && !forceReconnect) {
            Session current = session;
            Session currentVpn = vpnSession;
            if (current == null || !current.isConnected()) {
                throw new Exception("session disconnected");
            }
            if (currentVpn != null && !currentVpn.isConnected()) {
                throw new Exception("VPN session disconnected");
            }
            SocksProxy telegramProxy = telegramSocksProxy;
            SocksProxy vpnProxy = vpnSocksProxy;
            if ((telegramProxy == null && vpnProxy == null)
                    || (telegramProxy != null && !telegramProxy.isRunning())
                    || (vpnProxy != null && !vpnProxy.isRunning())) {
                throw new Exception("local forwarding stopped");
            }
            sleepInterruptibly(2_000L);
        }
    }

    private synchronized void startStats() {
        if (statsStarted) return;
        statsStarted = true;
        statsWorker.scheduleWithFixedDelay(this::sampleStats, 1, 1, TimeUnit.SECONDS);
    }

    private void sampleStats() {
        if (!wanted) return;
        long[] traffic = trafficSnapshot();
        long now = SystemClock.elapsedRealtime();
        long bytes = traffic[0] + traffic[1];
        long elapsed = Math.max(1, now - lastSampleTime);
        long speed = Math.max(0, (bytes - lastSampleBytes) * 1000L / elapsed);
        SocksProxy vpnProxy = vpnSocksProxy;
        SocksProxy telegramProxy = telegramSocksProxy;
        int latency = vpnProxy == null ? -1 : vpnProxy.getLastLatencyMs();
        if (latency < 0 && telegramProxy != null) latency = telegramProxy.getLastLatencyMs();
        currentSpeed = speed;
        currentLatency = latency;
        lastSampleBytes = bytes;
        lastSampleTime = now;

        statsTicks++;
        if (statsTicks % 30 == 0) persistTotals();

        Intent statsIntent = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("speed_bps", speed)
                .putExtra("ping_ms", latency)
                .putExtra("session_uploaded", traffic[0])
                .putExtra("session_downloaded", traffic[1])
                .putExtra("total_uploaded", totalUploaded + traffic[0])
                .putExtra("total_downloaded", totalDownloaded + traffic[1]);
        sendBroadcast(statsIntent);
        if (statsTicks % 5 == 0) {
            getSystemService(NotificationManager.class).notify(ID, note(currentStatus));
        }
    }

    private int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String classifyError(JSchException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (m.contains("auth fail")) return "Неверный логин или пароль.";
        if (m.contains("timeout")) return "Сервер не отвечает.";
        if (m.contains("connection refused")) return "SSH-порт недоступен.";
        if (m.contains("missing settings")) return "Заполни настройки подключения.";
        if (m.contains("host key is not trusted")) {
            return "Ключ SSH-сервера ещё не подтверждён. Открой приложение.";
        }
        if (m.contains("hostkey has been changed")
                || m.contains("host key has been changed")) {
            return "Ключ SSH-сервера изменился. Подключение заблокировано.";
        }
        if (m.contains("address already in use")) return "Локальный SOCKS-порт уже занят.";
        if (m.contains("tls protection")) {
            return "Не удалось установить защищённое TLS-соединение.";
        }
        return "Не удалось подключиться.";
    }

    private void sleepInterruptibly(long millis) {
        long end = SystemClock.elapsedRealtime() + millis;
        while (wanted && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(Math.min(500, end - SystemClock.elapsedRealtime()));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void disconnect() {
        connected = false;
        SocksProxy telegramProxy = telegramSocksProxy;
        SocksProxy vpnProxy = vpnSocksProxy;
        telegramSocksProxy = null;
        vpnSocksProxy = null;
        collectAndClose(telegramProxy);
        collectAndClose(vpnProxy);
        Session current = session;
        Session currentVpn = vpnSession;
        session = null;
        vpnSession = null;
        if (current != null) {
            try { current.disconnect(); } catch (Exception ignored) {}
        }
        if (currentVpn != null) {
            try { currentVpn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private synchronized long[] trafficSnapshot() {
        long uploaded = sessionUploaded;
        long downloaded = sessionDownloaded;
        SocksProxy telegramProxy = telegramSocksProxy;
        SocksProxy vpnProxy = vpnSocksProxy;
        if (telegramProxy != null) {
            uploaded += telegramProxy.getUploadedBytes();
            downloaded += telegramProxy.getDownloadedBytes();
        }
        if (vpnProxy != null) {
            uploaded += vpnProxy.getUploadedBytes();
            downloaded += vpnProxy.getDownloadedBytes();
        }
        return new long[] {uploaded, downloaded};
    }

    private void collectAndClose(SocksProxy proxy) {
        if (proxy == null) return;
        sessionUploaded += proxy.getUploadedBytes();
        sessionDownloaded += proxy.getDownloadedBytes();
        proxy.close();
    }

    private void persistTotals() {
        long[] traffic = trafficSnapshot();
        SecureStore store = new SecureStore(this);
        store.putLong("total_uploaded", totalUploaded + traffic[0]);
        store.putLong("total_downloaded", totalDownloaded + traffic[1]);
    }

    private void send(String text) {
        currentStatus = text;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("status", text));
        android.service.quicksettings.TileService.requestListeningState(
                this, new ComponentName(this, QuickSettingsTileService.class));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID, note(text));
    }

    private Notification note(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, TunnelService.class).setAction(STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String latency = currentLatency >= 0 ? currentLatency + " мс" : "—";
        String metrics = "Скорость: " + formatRate(currentSpeed) + " · Пинг: " + latency;
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile activeProfile = ServerProfiles.active(store);
        String title = Branding.appName(this)
                + (activeProfile == null ? "" : " · " + activeProfile.name)
                + " · " + TunnelMode.label(store);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(QuickSettingsTileService.iconResource(this))
                .setContentTitle(title)
                .setContentText(metrics)
                .setSubText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + metrics))
                .setOngoing(wanted)
                .setContentIntent(contentIntent)
                .addAction(new Notification.Action.Builder(null, "Отключить", stopIntent).build())
                .build();
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " Б/с";
        double value = bytesPerSecond / 1024.0;
        if (value < 1024) return String.format(java.util.Locale.getDefault(), "%.1f КБ/с", value);
        value /= 1024.0;
        return String.format(java.util.Locale.getDefault(), "%.1f МБ/с", value);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Пельмени VPN", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onDestroy() {
        serviceActive = false;
        connected = false;
        wanted = false;
        disconnect();
        persistTotals();
        if (networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        synchronized (this) {
            networkSwitchGeneration++;
            if (pendingNetworkSwitch != null) pendingNetworkSwitch.cancel(false);
            pendingNetworkSwitch = null;
            pendingUnderlyingNetwork = null;
            activeUnderlyingNetwork = null;
            notifyAll();
        }
        worker.shutdownNow();
        statsWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
