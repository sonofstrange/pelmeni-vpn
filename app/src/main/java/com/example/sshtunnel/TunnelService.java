package com.example.sshtunnel;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;

import com.jcraft.jsch.*;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TunnelService extends Service {
    public static final String START = "start";
    public static final String STOP = "stop";
    public static final String RECONFIGURE = "reconfigure";
    public static final String ACTION_STATUS = "com.example.sshtunnel.STATUS";

    private static final String CHANNEL = "tunnel";
    private static final String LIMIT_CHANNEL = "user_limits";
    private static final int ID = 42;
    private static final int LIMIT_ID = 43;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService statsWorker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean loopRunning = new AtomicBoolean(false);
    private volatile boolean wanted = false;
    private volatile boolean forceReconnect = false;
    private volatile boolean modesChanged = false;
    private volatile Session session;
    private volatile Session vpnSession;
    private volatile SocksProxy telegramSocksProxy;
    private volatile SocksProxy vpnSocksProxy;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network activeUnderlyingNetwork;
    private final Set<Network> availableUnderlyingNetworks = ConcurrentHashMap.newKeySet();
    private boolean statsStarted;
    private long sessionUploaded;
    private long sessionDownloaded;
    private long totalUploaded;
    private long totalDownloaded;
    private long lastSampleBytes;
    private long lastPolicyBytes;
    private long lastSampleTime;
    private int statsTicks;
    private volatile String currentStatus = "Подключение…";
    private volatile long currentSpeed;
    private volatile int currentLatency = -1;
    private volatile long serviceStartedAt;
    private volatile int connectionAttempts;
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
        if (intent != null && RECONFIGURE.equals(intent.getAction()) && wanted) {
            reconfigureModes();
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
            serviceStartedAt = lastSampleTime;
            connectionAttempts = 0;
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
                boolean betterNetwork = false;
                synchronized (TunnelService.this) {
                    availableUnderlyingNetworks.add(network);
                    if (activeUnderlyingNetwork == null) {
                        activeUnderlyingNetwork = network;
                    } else if (networkScore(network) > networkScore(activeUnderlyingNetwork)) {
                        activeUnderlyingNetwork = network;
                        betterNetwork = true;
                    }
                }
                if (betterNetwork) requestReconnect("Найдена более быстрая сеть, переподключение…");
            }
            @Override public void onLost(Network network) {
                boolean activeLost = false;
                synchronized (TunnelService.this) {
                    availableUnderlyingNetworks.remove(network);
                    if (network.equals(activeUnderlyingNetwork)) {
                        activeUnderlyingNetwork = null;
                        int bestScore = -1;
                        for (Network candidate : availableUnderlyingNetworks) {
                            int score = networkScore(candidate);
                            if (score > bestScore) {
                                activeUnderlyingNetwork = candidate;
                                bestScore = score;
                            }
                        }
                        activeLost = true;
                    }
                }
                if (activeLost) requestReconnect("Сеть изменилась, переподключение…");
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private int networkScore(Network network) {
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null) return 0;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return 3;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 2;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return 1;
        return 0;
    }

    private synchronized void requestReconnect(String message) {
        if (!wanted) return;
        if (!new SecureStore(this).getBoolean("auto_reconnect", true)) return;
        if (forceReconnect) return;
        forceReconnect = true;
        connected = false;
        send(message);
        disconnect();
        startConnectionLoop();
    }

    private synchronized void reconfigureModes() {
        if (!wanted) return;
        modesChanged = true;
        send("Переключаемся на " + TunnelMode.label(new SecureStore(this)) + "…");
    }

    private void startConnectionLoop() {
        if (!loopRunning.compareAndSet(false, true)) return;
        worker.submit(() -> {
            int delaySeconds = 2;
            try {
                while (wanted) {
                    forceReconnect = false;
                    try {
                        connectionAttempts++;
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
                    if (forceReconnect) {
                        delaySeconds = 2;
                        continue;
                    }
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
                store, host, user, password, sshPort, tlsProtected, windowSize);
        Session newVpnSession = null;
        SocksProxy newTelegramProxy = null;
        SocksProxy newVpnProxy = null;
        try {
            if (telegramEnabled && vpnEnabled) {
                newVpnSession = connectSession(
                        store, host, user, password, sshPort, tlsProtected, windowSize);
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
            int sshPort, boolean tlsProtected, int windowSize) throws Exception {
        Session result = new JSch().getSession(user, host, sshPort);
        result.setPassword(password);
        result.setSocketFactory(tlsProtected
                ? TlsTransport.socketFactory(store, activeUnderlyingNetwork)
                : new LowLatencySocketFactory(activeUnderlyingNetwork));
        result.setConfig("StrictHostKeyChecking", "no");
        result.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        result.setConfig("cipher.c2s",
                "aes128-gcm@openssh.com,aes128-ctr,aes256-gcm@openssh.com,aes256-ctr");
        result.setConfig("cipher.s2c",
                "aes128-gcm@openssh.com,aes128-ctr,aes256-gcm@openssh.com,aes256-ctr");
        result.setConfig("max_input_buffer_size", Integer.toString(windowSize));
        result.setServerAliveInterval(10_000);
        result.setServerAliveCountMax(2);
        result.setTimeout(15_000);
        result.connect(15_000);
        return result;
    }

    private void monitorConnection() throws Exception {
        while (wanted && !forceReconnect) {
            if (modesChanged) applyModeChanges();
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

    private void applyModeChanges() throws Exception {
        modesChanged = false;
        SecureStore store = new SecureStore(this);
        boolean telegramEnabled = store.getBoolean("telegram_proxy", true);
        boolean vpnEnabled = store.getBoolean("vpn_mode", false);
        int windowSize = NetworkTuning.windowKiB(store) * 1024;
        int packetSize = NetworkTuning.packetKiB(store) * 1024;
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        int sshPort = parsePort(store.getPlain("port", "22"), 22);
        boolean tlsProtected = TlsTransport.isEnabledFor(store, host);

        if (telegramEnabled && telegramSocksProxy == null) {
            Session newTelegramSession = connectSession(
                    store, host, user, password, sshPort, tlsProtected, windowSize);
            SocksProxy newTelegramProxy = new SocksProxy(
                    newTelegramSession, TunnelMode.telegramSocksPort(store),
                    windowSize, packetSize);
            try {
                newTelegramProxy.start();
            } catch (Exception error) {
                newTelegramSession.disconnect();
                throw error;
            }
            if (vpnSocksProxy != null && vpnSession == null) {
                vpnSession = session;
            }
            session = newTelegramSession;
            telegramSocksProxy = newTelegramProxy;
        }

        if (vpnEnabled && vpnSocksProxy == null) {
            Session newVpnSession = connectSession(
                    store, host, user, password, sshPort, tlsProtected, windowSize);
            SocksProxy newVpnProxy = new SocksProxy(
                    newVpnSession, TunnelMode.vpnSocksPort(store),
                    windowSize, packetSize);
            try {
                newVpnProxy.start();
            } catch (Exception error) {
                newVpnSession.disconnect();
                throw error;
            }
            if (telegramSocksProxy == null) {
                session = newVpnSession;
                vpnSession = null;
            } else {
                vpnSession = newVpnSession;
            }
            vpnSocksProxy = newVpnProxy;
        }

        if (!vpnEnabled && vpnSocksProxy != null) {
            collectAndClose(vpnSocksProxy);
            vpnSocksProxy = null;
            Session oldVpn = vpnSession;
            vpnSession = null;
            if (oldVpn == null && telegramSocksProxy == null) {
                oldVpn = session;
                session = null;
            }
            if (oldVpn != null) oldVpn.disconnect();
        }

        if (!telegramEnabled && telegramSocksProxy != null) {
            collectAndClose(telegramSocksProxy);
            telegramSocksProxy = null;
            Session oldTelegram = session;
            if (vpnSession != null) {
                session = vpnSession;
                vpnSession = null;
            } else {
                session = null;
            }
            if (oldTelegram != null) oldTelegram.disconnect();
        }

        connected = session != null && session.isConnected();
        send("Подключено · " + TunnelMode.portsLabel(store));
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
        if (Branding.isSecret(this)) {
            Session currentSession = session;
            SecureStore store = new SecureStore(this);
            String configuredHost = store.getPlain("host", "").trim();
            boolean tlsEnabled = TlsTransport.isEnabledFor(store, configuredHost);
            ServerProfiles.Profile activeProfile = ServerProfiles.active(store);
            statsIntent.putExtra("debug_enabled", true)
                    .putExtra("debug_version", BuildConfig.VERSION_NAME)
                    .putExtra("debug_profile", activeProfile == null
                            ? "—" : activeProfile.name)
                    .putExtra("debug_status", currentStatus)
                    .putExtra("debug_ssh_connected",
                            currentSession != null && currentSession.isConnected())
                    .putExtra("debug_ssh_sessions", vpnSession == null ? 1 : 2)
                    .putExtra("debug_ssh_endpoint", configuredHost + ":"
                            + parsePort(store.getPlain("port", "22"), 22))
                    .putExtra("debug_transport", tlsEnabled
                            ? "TLS:" + TlsTransport.port(store) : "обычный SSH")
                    .putExtra("debug_uptime_ms",
                            Math.max(0, SystemClock.elapsedRealtime() - serviceStartedAt))
                    .putExtra("debug_connect_attempts", connectionAttempts)
                    .putExtra("debug_socks_ports", TunnelMode.portsLabel(store))
                    .putExtra("debug_tg_running",
                            telegramProxy != null && telegramProxy.isRunning())
                    .putExtra("debug_vpn_running",
                            vpnProxy != null && vpnProxy.isRunning())
                    .putExtra("debug_auto_reconnect",
                            store.getBoolean("auto_reconnect", true))
                    .putExtra("debug_tuning", NetworkTuning.windowKiB(store) + "/"
                            + NetworkTuning.packetKiB(store) + "/"
                            + NetworkTuning.vpnMtu(store))
                    .putExtra("debug_network", networkLabel(activeUnderlyingNetwork))
                    .putExtra("debug_mode", TunnelMode.label(store));
        }
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

    private String networkLabel(Network network) {
        if (network == null) return "автовыбор";
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities == null) return "неизвестно";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi‑Fi";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "мобильная";
        return "другая";
    }

    private String classifyError(JSchException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (m.contains("auth fail")) return "Неверный логин или пароль.";
        if (m.contains("timeout")) return "Сервер не отвечает.";
        if (m.contains("connection refused")) return "SSH-порт недоступен.";
        if (m.contains("missing settings")) return "Заполни настройки подключения.";
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
        PendingIntent contentIntent = PendingIntent.getActivity(this, LIMIT_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, LIMIT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(alert.title)
                .setContentText(alert.text)
                .setStyle(new Notification.BigTextStyle().bigText(alert.text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build();
        getSystemService(NotificationManager.class).notify(LIMIT_ID, notification);
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("limit_warning", alert.title + "\n" + alert.text));
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
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
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
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(LIMIT_CHANNEL,
                            "Лимиты пользователей", NotificationManager.IMPORTANCE_HIGH));
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
        availableUnderlyingNetworks.clear();
        worker.shutdownNow();
        statsWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
