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
    public static final String RECONFIGURE = "reconfigure";
    public static final String ACTION_STATUS = "com.example.sshtunnel.STATUS";

    public static final String EXTRA_RUNNING = "tunnel_running";
    private static final String CHANNEL = "tunnel";
    private static final String LIMIT_CHANNEL = "user_limits_v2";
    private static final int ID = 42;
    private static final int LIMIT_ID = 43;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsWorker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean loopRunning = new AtomicBoolean(false);
    private volatile boolean wanted = false;
    private volatile boolean forceReconnect = false;
    private volatile Session session;
    private volatile Session vpnSession;
    private volatile SocksProxy telegramSocksProxy;
    private volatile SocksProxy vpnSocksProxy;
    private volatile UserTrafficLimiter trafficLimiter;
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
    private long lastPolicyBytes;
    private long lastSampleTime;
    private int statsTicks;
    private int policySyncTicks;
    private long lastUsageResetAt;
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
            stopAfterFailure("Отключено");
            return START_NOT_STICKY;
        }
        if (intent != null && RECONFIGURE.equals(intent.getAction()) && wanted) {
            forceReconnect = true;
            send("Переключаемся на "
                    + TunnelMode.label(new SecureStore(this)) + "…");
            disconnect();
            return START_STICKY;
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
            lastPolicyBytes = 0;
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
                        if (!shouldRetryAfterFailure()) {
                            stopAfterFailure(message);
                            break;
                        }
                        send(message + " Повтор через " + delaySeconds + " сек…");
                    } catch (Exception e) {
                        if (!wanted) break;
                        connected = false;
                        String message = "SSH-соединение потеряно.";
                        if (!shouldRetryAfterFailure()) {
                            stopAfterFailure(message);
                            break;
                        }
                        send(message + " Повтор через " + delaySeconds + " сек…");
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
        UserTrafficLimiter newTrafficLimiter = null;
        try {
            if (telegramEnabled && vpnEnabled) {
                newVpnSession = connectSession(
                        store, host, user, password, sshPort, tlsProtected,
                        windowSize, underlyingNetwork);
            }
            syncUserPolicy(newSession);
            newTrafficLimiter = createTrafficLimiter(store);
            if (telegramEnabled) {
                newTelegramProxy = new SocksProxy(
                        newSession, telegramPort, windowSize, packetSize,
                        newTrafficLimiter);
                newTelegramProxy.start();
            }
            if (vpnEnabled) {
                newVpnProxy = new SocksProxy(
                        newVpnSession == null ? newSession : newVpnSession,
                        vpnPort, windowSize, packetSize, newTrafficLimiter);
                newVpnProxy.start();
            }
            if (!wanted || forceReconnect) throw new JSchException("reconnect requested");

            session = newSession;
            vpnSession = newVpnSession;
            telegramSocksProxy = newTelegramProxy;
            vpnSocksProxy = newVpnProxy;
            trafficLimiter = newTrafficLimiter;
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
            if (++policySyncTicks >= 30) {
                policySyncTicks = 0;
                syncUserPolicy(current);
            }
            sleepInterruptibly(2_000L);
        }
    }

    private void syncUserPolicy(Session current) {
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        long previousResetAt = profile == null ? 0
                : UserAccessPolicy.usage(store, profile.id).resetAt;
        try {
            if (profile != null) {
                UserAccessPolicy.syncFromServer(store, profile.id, current);
            }
        } catch (Exception ignored) {
            // Administrator profiles and older servers do not expose a user policy.
        }
        UserTrafficLimiter limiter = trafficLimiter;
        if (limiter != null && profile != null) {
            UserAccessPolicy.Usage usage =
                    UserAccessPolicy.usage(store, profile.id);
            if (usage.resetAt > previousResetAt
                    && usage.resetAt > lastUsageResetAt) {
                long[] traffic = trafficSnapshot();
                lastPolicyBytes = traffic[0] + traffic[1];
                lastUsageResetAt = usage.resetAt;
            }
            limiter.refresh(UserAccessPolicy.load(store, profile.id), usage);
        }
    }

    private UserTrafficLimiter createTrafficLimiter(SecureStore store) {
        UserTrafficLimiter limiter = new UserTrafficLimiter();
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        if (profile != null) {
            limiter.refresh(UserAccessPolicy.load(store, profile.id),
                    UserAccessPolicy.usage(store, profile.id));
        }
        return limiter;
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
        if (statsTicks % 5 == 0) recordPolicyUsage(bytes);

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
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
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
        trafficLimiter = null;
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
        recordPolicyUsage(traffic[0] + traffic[1]);
        SecureStore store = new SecureStore(this);
        store.putLong("total_uploaded", totalUploaded + traffic[0]);
        store.putLong("total_downloaded", totalDownloaded + traffic[1]);
    }

    private void recordPolicyUsage(long currentBytes) {
        long added = Math.max(0, currentBytes - lastPolicyBytes);
        lastPolicyBytes = currentBytes;
        if (added <= 0) return;
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        if (profile == null) return;
        UserAccessPolicy.Alert alert =
                UserAccessPolicy.record(store, profile.id, added);
        if (alert == null) return;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, LIMIT_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, LIMIT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setStyle(new Notification.BigTextStyle().bigText(alert.text))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentIntent(contentIntent)
                .build();
        getSystemService(NotificationManager.class).notify(
                alert.critical ? LIMIT_ID + 1 : LIMIT_ID, notification);
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("limit_warning", alert.title + "\n" + alert.text));
    }

    private boolean shouldRetryAfterFailure() {
        return forceReconnect
                || new SecureStore(this).getBoolean("auto_reconnect", true);
    }

    private void stopAfterFailure(String message) {
        wanted = false;
        connected = false;
        new SecureStore(this).putBoolean("enabled", false);
        startService(new Intent(this, VpnTunnelService.class)
                .setAction(VpnTunnelService.STOP)
                .putExtra(VpnTunnelService.EXTRA_STOP_SSH, false));
        disconnect();
        persistTotals();
        send(message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(ID);
        stopSelf();
    }

    private void send(String text) {
        currentStatus = text;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("status", text)
                .putExtra(EXTRA_RUNNING, wanted));
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
        String limitWarning = "";
        if (activeProfile != null) {
            UserAccessPolicy.Policy policy =
                    UserAccessPolicy.load(store, activeProfile.id);
            if (policy.configured) {
                limitWarning = UserAccessPolicy.warning(
                        policy, UserAccessPolicy.usage(
                                store, activeProfile.id));
            }
        }
        String title = Branding.appName(this)
                + (activeProfile == null ? "" : " · " + activeProfile.name)
                + " · " + TunnelMode.label(store);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(QuickSettingsTileService.iconResource(this))
                .setContentTitle(title)
                .setContentText(metrics)
                .setSubText(text)
                .setStyle(new Notification.BigTextStyle().bigText(
                        text + "\n" + metrics
                                + (limitWarning.isEmpty()
                                ? "" : "\n⚠ " + limitWarning)))
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
            NotificationChannel limits = new NotificationChannel(
                    LIMIT_CHANNEL, "Предупреждения о лимитах",
                    NotificationManager.IMPORTANCE_HIGH);
            limits.enableVibration(true);
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(limits);
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
