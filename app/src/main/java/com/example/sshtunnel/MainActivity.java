package com.example.sshtunnel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.net.VpnService;
import android.graphics.drawable.Icon;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String EXTRA_START_FROM_TILE = "start_from_tile";
    private static final int REQUEST_VPN = 9;
    private static final int REQUEST_EXPORT = 20;
    private static final int REQUEST_IMPORT = 21;
    private static final long UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private EditText host, sshPort, user, password, socksPort;
    private TextView appTitle, status, speedPing, debugInfo, serverName, serverAddress;
    private TextView sessionDown, sessionUp, totalDown, totalUp;
    private Button toggle, save, serverSelect, serverEdit;
    private CheckBox showPassword, autoReconnect, startOnBoot, enableVpn, enableTelegram;
    private boolean running;
    private boolean receiverRegistered;
    private volatile boolean speedTestRunning;
    private volatile boolean serverSetupRunning;
    private volatile boolean updateCheckRunning;
    private final ExecutorService speedWorker = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("speed_bps")) updateStats(intent);
            update(intent.getStringExtra("status"));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        host = findViewById(R.id.host);
        sshPort = findViewById(R.id.port);
        user = findViewById(R.id.user);
        password = findViewById(R.id.password);
        socksPort = findViewById(R.id.socksPort);
        appTitle = findViewById(R.id.appTitle);
        status = findViewById(R.id.status);
        speedPing = findViewById(R.id.speedPing);
        debugInfo = findViewById(R.id.debugInfo);
        sessionDown = findViewById(R.id.sessionDown);
        sessionUp = findViewById(R.id.sessionUp);
        totalDown = findViewById(R.id.totalDown);
        totalUp = findViewById(R.id.totalUp);
        serverName = findViewById(R.id.serverName);
        serverAddress = findViewById(R.id.serverAddress);
        toggle = findViewById(R.id.toggle);
        save = findViewById(R.id.save);
        serverSelect = findViewById(R.id.serverSelect);
        serverEdit = findViewById(R.id.serverEdit);
        showPassword = findViewById(R.id.showPassword);
        autoReconnect = findViewById(R.id.autoReconnect);
        startOnBoot = findViewById(R.id.startOnBoot);
        enableVpn = findViewById(R.id.enableVpn);
        enableTelegram = findViewById(R.id.enableTelegram);

        ServerProfiles.migrateLegacy(new SecureStore(this));
        Branding.restoreLauncherState(this);
        appTitle.setText(Branding.appName(this));
        updateDebugPanel();
        loadSettings();
        running = new SecureStore(this).getBoolean("enabled", false)
                && TunnelService.isActive();
        update(running ? "Сервис запущен, проверяем соединение…" : "Отключено");

        showStoredTotals();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
            requestQuickSettingsTile();
        }

        showPassword.setOnCheckedChangeListener((button, checked) -> {
            int position = password.getSelectionStart();
            password.setInputType(checked
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setSelection(Math.max(0, Math.min(position, password.length())));
        });
        autoReconnect.setOnCheckedChangeListener((button, checked) ->
                new SecureStore(this).putBoolean("auto_reconnect", checked));
        startOnBoot.setOnCheckedChangeListener((button, checked) ->
                new SecureStore(this).putBoolean("start_on_boot", checked));
        enableVpn.setOnCheckedChangeListener((button, checked) ->
                new SecureStore(this).putBoolean("vpn_mode", checked));
        enableTelegram.setOnCheckedChangeListener((button, checked) ->
                new SecureStore(this).putBoolean("telegram_proxy", checked));

        save.setOnClickListener(v -> {
            if (saveSettings()) {
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
                maybeOfferTlsForCurrentServer(null);
            }
        });
        toggle.setOnClickListener(v -> {
            if (running) stopTunnel();
            else startTunnel();
        });
        serverSelect.setOnClickListener(v -> showServerList());
        serverEdit.setOnClickListener(v -> {
            ServerProfiles.Profile active =
                    ServerProfiles.active(new SecureStore(this));
            showServerEditor(active);
        });
        findViewById(R.id.moreActions).setOnClickListener(this::showMoreActions);
        if (getIntent().getBooleanExtra(EXTRA_START_FROM_TILE, false)) {
            getIntent().removeExtra(EXTRA_START_FROM_TILE);
            toggle.post(() -> {
                if (!running) startTunnel();
            });
        }
        maybeCheckForUpdate(false);
    }

    private void showMoreActions(android.view.View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_actions, popup.getMenu());
        popup.getMenu().findItem(R.id.actionImport).setEnabled(!running);
        popup.getMenu().findItem(R.id.actionExport).setEnabled(!running);
        popup.getMenu().findItem(R.id.actionSpeedTest).setEnabled(TunnelService.isConnected());
        popup.getMenu().findItem(R.id.actionProtection).setEnabled(!running);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.actionImport) {
                beginImport();
                return true;
            }
            if (item.getItemId() == R.id.actionExport) {
                beginExport();
                return true;
            }
            if (item.getItemId() == R.id.actionTelegram) {
                openTelegramProxy();
                return true;
            }
            if (item.getItemId() == R.id.actionSpeedTest) {
                confirmSpeedTest();
                return true;
            }
            if (item.getItemId() == R.id.actionProtection) {
                showTlsProtection();
                return true;
            }
            if (item.getItemId() == R.id.actionUpdate) {
                if (Branding.isSecret(this)) showUpdateChannelChoice();
                else maybeCheckForUpdate(true, false);
                return true;
            }
            if (item.getItemId() == R.id.actionAdvanced) {
                showAdvancedSettings();
                return true;
            }
            if (item.getItemId() == R.id.actionResetRuntime) {
                resetRuntimeState();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showAdvancedSettings() {
        SecureStore store = new SecureStore(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);

        EditText window = addTuningField(fields,
                "Окно SSH · 128–16384 КиБ",
                "Сколько данных может находиться в одном SSH-канале. Больше — выше "
                        + "скорость на большом пинге, но больше расход памяти и задержка под нагрузкой.",
                NetworkTuning.windowKiB(store));
        EditText packet = addTuningField(fields,
                "Пакет SSH · 16–64 КиБ",
                "32 КиБ безопасно для большинства серверов. 64 КиБ экспериментально: "
                        + "меньше накладных расходов, но сервер может ограничить размер.",
                NetworkTuning.packetKiB(store));
        EditText mtu = addTuningField(fields,
                "MTU VPN · 1280–16000",
                "Размер пакета виртуального TUN. 8500 быстрее для tun2socks; 1500 полезно, "
                        + "если отдельные сайты или тесты зависают.",
                NetworkTuning.vpnMtu(store));
        Button autoTune = new Button(this);
        autoTune.setText("АВТОПОДБОР · ЭКСПЕРИМЕНТ");
        fields.addView(autoTune);
        scroll.addView(fields);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Тонкая настройка · для опытных")
                .setMessage("Рекомендуется: окно 1024 КиБ, пакет 32 КиБ, MTU 8500. "
                        + "Все изменения применяются при следующем подключении.")
                .setView(scroll)
                .setPositiveButton("Сохранить", null)
                .setNeutralButton("Сбросить", (ignored, which) -> {
                    saveAdvancedValues(store,
                            NetworkTuning.DEFAULT_WINDOW_KIB,
                            NetworkTuning.DEFAULT_PACKET_KIB,
                            NetworkTuning.DEFAULT_MTU);
                    Toast.makeText(this,
                            "Возвращены рекомендуемые значения. Применятся при следующем подключении.",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Отмена", null)
                .create();
        autoTune.setOnClickListener(v -> runTunnelSpeedTest(true, window, packet));
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    int windowValue = parseInt(window);
                    int packetValue = parseInt(packet);
                    int mtuValue = parseInt(mtu);
                    if (!NetworkTuning.valid(windowValue,
                            NetworkTuning.MIN_WINDOW_KIB, NetworkTuning.MAX_WINDOW_KIB)
                            || !NetworkTuning.valid(packetValue,
                            NetworkTuning.MIN_PACKET_KIB, NetworkTuning.MAX_PACKET_KIB)
                            || !NetworkTuning.valid(mtuValue,
                            NetworkTuning.MIN_MTU, NetworkTuning.MAX_MTU)) {
                        Toast.makeText(this, "Проверь допустимые диапазоны значений",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveAdvancedValues(store, windowValue, packetValue, mtuValue);
                    Toast.makeText(this,
                            "Тонкая настройка сохранена. Применится при следующем подключении.",
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private EditText addTuningField(
            LinearLayout parent, String title, String explanation, int value) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF17211B);
        titleView.setPadding(0, 14, 0, 0);
        parent.addView(titleView);
        TextView explanationView = new TextView(this);
        explanationView.setText(explanation);
        explanationView.setTextSize(13);
        explanationView.setTextColor(0xFF65736A);
        parent.addView(explanationView);
        EditText field = new EditText(this);
        field.setText(Integer.toString(value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSelectAllOnFocus(true);
        parent.addView(field);
        return field;
    }

    private void showTlsProtection() {
        SecureStore store = new SecureStore(this);
        String currentHost = host.getText().toString().trim();
        boolean configured = TlsTransport.isConfigured(store)
                && currentHost.equalsIgnoreCase(store.getPlain("tls_host", ""));
        boolean enabled = configured && store.getBoolean("tls_enabled", false);
        String message = "Обычный SSH уже шифрует данные. Этот режим дополнительно "
                + "оборачивает SSH в TLS на порту 443 и требует клиентский сертификат, "
                + "чтобы скрыть SSH от простого распознавания и сканирования."
                + "\n\nЭто не защищает от прямой блокировки IP сервера."
                + "\n\nСостояние: "
                + (configured
                ? (enabled ? "включено" : "настроено, но выключено")
                : "не настроено для текущего сервера");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Защита соединения · эксперимент")
                .setMessage(message)
                .setNegativeButton("Закрыть", null);
        if (configured) {
            builder.setNeutralButton("Удалить TLS с сервера",
                    (ignored, which) -> confirmServerTlsRemoval());
            builder.setPositiveButton(enabled ? "Выключить" : "Включить",
                    (ignored, which) -> {
                        TlsTransport.setEnabledByUser(store, !enabled);
                        Toast.makeText(this,
                                !enabled
                                        ? "TLS-защита включена. Применится при подключении."
                                        : "TLS-защита выключена. Серверная настройка сохранена.",
                                Toast.LENGTH_LONG).show();
                    });
        } else {
            builder.setPositiveButton("Настроить сервер",
                    (ignored, which) -> confirmServerTlsSetup());
        }
        builder.show();
    }

    private void confirmServerTlsRemoval() {
        if (running) {
            Toast.makeText(this, "Сначала отключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        if (serverSetupRunning) {
            Toast.makeText(this, "Операция с сервером уже выполняется",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!saveSettings()) return;
        new AlertDialog.Builder(this)
                .setTitle("Полностью удалить TLS?")
                .setMessage("С сервера будут удалены только компоненты Пельмени VPN: "
                        + "служба pelmeni-stunnel, её конфигурация, сертификаты, "
                        + "системный пользователь и правила UFW для 443/8443.\n\n"
                        + "SSH-сервер и обычный VPN останутся. Правила внешнего firewall "
                        + "в панели хостинга приложение изменить не может.")
                .setPositiveButton("Удалить", (ignored, which) -> runServerTlsRemoval())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runServerTlsRemoval() {
        serverSetupRunning = true;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Удаление TLS")
                .setMessage("Останавливаем и удаляем TLS-компоненты с сервера…")
                .setCancelable(false)
                .create();
        progress.show();
        speedWorker.execute(() -> {
            try {
                SecureStore store = new SecureStore(this);
                ServerTlsSetup.remove(store);
                TlsTransport.clear(store);
                runOnUiThread(() -> {
                    serverSetupRunning = false;
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("TLS удалён")
                            .setMessage("Служба, конфигурация и сертификаты Пельмени VPN "
                                    + "удалены с сервера. Локальные TLS-ключи также удалены.\n\n"
                                    + "Теперь подключения будут идти напрямую по SSH.")
                            .setPositiveButton("Готово", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    serverSetupRunning = false;
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    String message = error.getMessage();
                    new AlertDialog.Builder(this)
                            .setTitle("TLS не удалён")
                            .setMessage(message == null || message.trim().isEmpty()
                                    ? "Не удалось удалить TLS с сервера."
                                    : message)
                            .setPositiveButton("Понятно", null)
                            .show();
                });
            }
        });
    }

    private void confirmServerTlsSetup() {
        if (running) {
            Toast.makeText(this, "Сначала отключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        if (serverSetupRunning) {
            Toast.makeText(this, "Настройка сервера уже выполняется", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!saveSettings()) return;
        new AlertDialog.Builder(this)
                .setTitle("Автоматически настроить сервер?")
                .setMessage("Приложение подключится по обычному SSH, проверит Debian/Ubuntu "
                        + "и свободный порт 443, установит stunnel, создаст отдельный systemd-сервис "
                        + "и взаимные TLS-сертификаты.\n\n"
                        + "Нужен root либо sudo с тем же паролем. Если порт 443 занят, "
                        + "приложение остановится и ничего не перезапишет.")
                .setPositiveButton("Настроить", (ignored, which) -> runServerTlsSetup())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runServerTlsSetup() {
        serverSetupRunning = true;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Настройка TLS-защиты")
                .setMessage("Проверяем сервер, устанавливаем stunnel и создаём сертификаты. "
                        + "Обычно это занимает 20–90 секунд…")
                .setCancelable(false)
                .create();
        progress.show();
        speedWorker.execute(() -> {
            try {
                SecureStore store = new SecureStore(this);
                String configuredHost = store.getPlain("host", "").trim();
                boolean existingCredentials = TlsTransport.isConfigured(store)
                        && configuredHost.equalsIgnoreCase(
                        store.getPlain("tls_host", ""));
                if (existingCredentials) {
                    TlsTransport.setEnabledByUser(store, true);
                } else {
                    ServerTlsSetup.Result result = ServerTlsSetup.install(store);
                    TlsTransport.save(store, configuredHost, result.port,
                            result.pkcs12, result.password);
                }

                List<Integer> configuredPorts = new ArrayList<>();
                configuredPorts.add(TlsTransport.DEFAULT_PORT);
                for (int fallbackPort : ServerTlsSetup.FALLBACK_PORTS) {
                    configuredPorts.add(fallbackPort);
                    try {
                        ServerTlsSetup.addFallbackPort(store, fallbackPort);
                    } catch (Exception fallbackError) {
                        android.util.Log.e("PelmeniTLS",
                                "Could not configure TLS fallback port "
                                        + fallbackPort + ": "
                                        + fallbackError.getClass().getSimpleName());
                    }
                }
                TlsTransport.setAvailablePorts(store, configuredPorts);
                try {
                    ServerTlsSetup.verify(store);
                } catch (Exception verifyError) {
                    TlsTransport.setEnabled(store, false);
                    throw new Exception("Сервер настроен, но текущая сеть телефона не смогла "
                            + "подключиться ни к 443, ни к резервным HTTPS-портам. "
                            + "Вероятно, порты закрыты во внешнем firewall хостинга "
                            + "или фильтруются оператором.", verifyError);
                }
                final int verifiedPort = TlsTransport.port(store);
                runOnUiThread(() -> {
                    serverSetupRunning = false;
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("TLS-защита готова")
                            .setMessage("Защищённый порт " + verifiedPort
                                    + " доступен с текущей сети телефона, клиентский сертификат сохранён "
                                    + "в зашифрованном хранилище Android, защищённое SSH-подключение "
                                    + "успешно проверено.\n\nРежим включён и применится при подключении.")
                            .setPositiveButton("Готово", null)
                            .show();
                });
            } catch (Exception error) {
                android.util.Log.e("PelmeniTLS",
                        "Server setup failed: "
                                + error.getClass().getSimpleName());
                runOnUiThread(() -> {
                    serverSetupRunning = false;
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    String rawMessage = error.getMessage();
                    final String message =
                            rawMessage == null || rawMessage.trim().isEmpty()
                                    ? "Не удалось настроить TLS-защиту."
                                    : rawMessage;
                    new AlertDialog.Builder(this)
                            .setTitle("Настройка не завершена")
                            .setMessage(message)
                            .setPositiveButton("Понятно", null)
                            .show();
                });
            }
        });
    }

    private void showUpdateChannelChoice() {
        new AlertDialog.Builder(this)
                .setTitle("Канал обновлений · debug")
                .setItems(new String[]{"Стабильный релиз", "Бета-версия"},
                        (ignored, which) -> maybeCheckForUpdate(true, which == 1))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void maybeCheckForUpdate(boolean manual) {
        maybeCheckForUpdate(manual, false);
    }

    private void maybeCheckForUpdate(boolean manual, boolean includePrereleases) {
        if (updateCheckRunning) {
            if (manual) {
                Toast.makeText(this, "Проверка обновления уже выполняется",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        SecureStore store = new SecureStore(this);
        long now = System.currentTimeMillis();
        if (!manual && now - store.getLong("last_update_check", 0)
                < UPDATE_INTERVAL_MS) {
            return;
        }
        store.putLong("last_update_check", now);
        updateCheckRunning = true;
        if (manual) {
            Toast.makeText(this, includePrereleases
                            ? "Проверяем бета-релизы…"
                            : "Проверяем стабильные GitHub Releases…",
                    Toast.LENGTH_SHORT).show();
        }
        speedWorker.execute(() -> {
            try {
                UpdateChecker.Result result =
                        UpdateChecker.check(new SecureStore(this), includePrereleases);
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (result == null) {
                        if (manual) {
                            Toast.makeText(this,
                                    "Установлена последняя версия " + BuildConfig.VERSION_NAME,
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }
                    showUpdate(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (!manual || isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Не удалось проверить обновление")
                            .setMessage("Проверь доступ к GitHub и повтори позже.")
                            .setPositiveButton("Готово", null)
                            .show();
                });
            }
        });
    }

    private void showUpdate(UpdateChecker.Result result) {
        String notes = result.notes.isEmpty()
                ? "Описание изменений не добавлено."
                : result.notes.substring(0, Math.min(result.notes.length(), 2000));
        String size = result.size > 0 ? "\nРазмер APK: " + formatBytes(result.size) : "";
        new AlertDialog.Builder(this)
                .setTitle((result.prerelease ? "Доступна бета " : "Доступно обновление ")
                        + result.version)
                .setMessage("Установлена версия " + BuildConfig.VERSION_NAME + size
                        + "\n\n" + notes
                        + "\n\nAndroid попросит подтвердить установку обновления.")
                .setPositiveButton("Скачать APK", (ignored, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(result.downloadUrl)));
                    } catch (Exception error) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(result.pageUrl)));
                    }
                })
                .setNeutralButton("Страница релиза", (ignored, which) ->
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(result.pageUrl))))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void confirmSpeedTest() {
        if (!TunnelService.isConnected()) {
            Toast.makeText(this, "Сначала подключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Проверка скорости через туннель")
                .setMessage("Тест использует примерно 18 МБ трафика и на время теста "
                        + "может увеличить пинг.")
                .setPositiveButton("Запустить",
                        (ignored, which) -> runTunnelSpeedTest(false, null, null))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runTunnelSpeedTest(boolean autoTune, EditText window, EditText packet) {
        if (!TunnelService.isConnected()) {
            Toast.makeText(this, "Автоподбор доступен только при подключённом туннеле",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (speedTestRunning) {
            Toast.makeText(this, "Проверка уже выполняется", Toast.LENGTH_SHORT).show();
            return;
        }
        speedTestRunning = true;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(autoTune ? "Экспериментальный автоподбор" : "Проверка скорости")
                .setMessage(autoTune
                        ? "Измеряем задержку и загрузку через SSH · около 4 МБ…"
                        : "Измеряем пинг, загрузку и выгрузку через SSH…")
                .setCancelable(false)
                .create();
        progress.show();
        speedWorker.execute(() -> {
            try {
                TunnelSpeedTest.Result result = TunnelSpeedTest.run(
                        new SecureStore(this), autoTune ? 4_000_000 : 10_000_000,
                        autoTune ? 0 : 8_000_000);
                runOnUiThread(() -> {
                    speedTestRunning = false;
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    if (autoTune) {
                        int suggestedWindow = suggestWindowKiB(result);
                        window.setText(Integer.toString(suggestedWindow));
                        packet.setText("32");
                        new AlertDialog.Builder(this)
                                .setTitle("Параметры подобраны")
                                .setMessage(speedResult(result)
                                        + "\n\nПредложено: окно " + suggestedWindow
                                        + " КиБ, пакет 32 КиБ. MTU оставлен без изменения — "
                                        + "его нельзя надёжно определить обычным speedtest."
                                        + "\n\nНажми «Сохранить» в окне тонкой настройки.")
                                .setPositiveButton("Понятно", null)
                                .show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Результат через SSH-туннель")
                                .setMessage(speedResult(result))
                                .setPositiveButton("Готово", null)
                                .show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    speedTestRunning = false;
                    progress.dismiss();
                    if (!isFinishing() && !isDestroyed()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Тест не завершён")
                                .setMessage("Проверь подключение туннеля и повтори. "
                                        + "Тестовый сервер Cloudflare мог быть недоступен.")
                                .setPositiveButton("Готово", null)
                                .show();
                    }
                });
            }
        });
    }

    private int suggestWindowKiB(TunnelSpeedTest.Result result) {
        long bdpBytes = result.downloadBytesPerSecond * result.latencyMs / 1000L;
        long suggestedKiB = Math.max(NetworkTuning.MIN_WINDOW_KIB,
                Math.min(NetworkTuning.MAX_WINDOW_KIB, bdpBytes * 2 / 1024L));
        return (int) Math.min(NetworkTuning.MAX_WINDOW_KIB,
                ((suggestedKiB + 127) / 128) * 128);
    }

    private String speedResult(TunnelSpeedTest.Result result) {
        String upload = result.uploadBytesPerSecond >= 0
                ? formatMbps(result.uploadBytesPerSecond) : "не измерялась";
        return "Пинг до тестового сервера: " + result.latencyMs + " мс"
                + "\nЗагрузка: " + formatMbps(result.downloadBytesPerSecond)
                + "\nВыгрузка: " + upload;
    }

    private String formatMbps(long bytesPerSecond) {
        return String.format(Locale.getDefault(), "%.1f Мбит/с",
                bytesPerSecond * 8.0 / 1_000_000.0);
    }

    private int parseInt(EditText field) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void saveAdvancedValues(SecureStore store, int window, int packet, int mtu) {
        store.putPlain("ssh_window_kib", Integer.toString(window));
        store.putPlain("ssh_packet_kib", Integer.toString(packet));
        store.putPlain("vpn_mtu", Integer.toString(mtu));
        ServerProfiles.updateActiveTuning(store, window, packet, mtu);
    }

    private void resetRuntimeState() {
        try {
            startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
        } catch (Exception ignored) {
        }
        SecureStore store = new SecureStore(this);
        store.putBoolean("enabled", false);
        File config = new File(getCacheDir(), "vpn-tun2socks.yml");
        if (config.exists()) config.delete();
        update("Отключено");
        showStoredTotals();
        Toast.makeText(this,
                "Временное состояние сброшено. Настройки и статистика сохранены.",
                Toast.LENGTH_LONG).show();
    }

    private void requestQuickSettingsTile() {
        SecureStore store = new SecureStore(this);
        if (store.getBoolean("tile_requested", false)) return;
        store.putBoolean("tile_requested", true);
        try {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            manager.requestAddTileService(
                    new ComponentName(this, QuickSettingsTileService.class),
                    Branding.appName(this),
                    Icon.createWithResource(this, R.drawable.ic_pelmeni_tile),
                    getMainExecutor(),
                    result -> { });
        } catch (Exception ignored) {
        }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TunnelService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        speedWorker.shutdownNow();
        super.onDestroy();
    }

    private void loadSettings() {
        SecureStore store = new SecureStore(this);
        ServerProfiles.migrateLegacy(store);
        ServerProfiles.Profile active = ServerProfiles.active(store);
        if (active != null) {
            try {
                ServerProfiles.activate(store, active.id);
            } catch (Exception ignored) {
            }
        }
        TlsTransport.enableAutomatically(store, store.getPlain("host", "").trim());
        host.setText(store.getPlain("host", ""));
        sshPort.setText(store.getPlain("port", "22"));
        user.setText(store.getPlain("user", "root"));
        password.setText(store.getSecret());
        socksPort.setText(store.getPlain("socks_port", "1080"));
        autoReconnect.setChecked(store.getBoolean("auto_reconnect", true));
        startOnBoot.setChecked(store.getBoolean("start_on_boot", false));
        boolean vpnEnabled = store.getBoolean("vpn_mode", false);
        enableVpn.setChecked(vpnEnabled);
        enableTelegram.setChecked(store.getBoolean("telegram_proxy", !vpnEnabled));
        updateServerCard();
    }

    private boolean saveSettings() {
        String h = host.getText().toString().trim();
        String p = sshPort.getText().toString().trim();
        String u = user.getText().toString().trim();
        String pw = password.getText().toString();
        String sp = socksPort.getText().toString().trim();

        if (!enableVpn.isChecked() && !enableTelegram.isChecked()) {
            Toast.makeText(this, "Включи VPN, прокси Telegram или оба режима", Toast.LENGTH_LONG).show();
            return false;
        }
        if (h.isEmpty() || p.isEmpty() || u.isEmpty() || pw.isEmpty() || sp.isEmpty()) {
            Toast.makeText(this, "Заполни IP/домен, порт, пользователя, пароль и SOCKS-порт", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!validHost(h)) {
            Toast.makeText(this, "В адресе сервера есть недопустимые символы", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!validPort(p) || !validPort(sp)) {
            Toast.makeText(this, "Порт должен быть от 1 до 65535", Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            SecureStore store = new SecureStore(this);
            store.putPlain("host", h);
            store.putPlain("port", p);
            store.putPlain("user", u);
            store.putPlain("socks_port", sp);
            store.putBoolean("auto_reconnect", autoReconnect.isChecked());
            store.putBoolean("start_on_boot", startOnBoot.isChecked());
            store.putBoolean("vpn_mode", enableVpn.isChecked());
            store.putBoolean("telegram_proxy", enableTelegram.isChecked());
            store.putSecret(pw);
            ServerProfiles.updateActiveConnection(store, h, p, u, pw, sp);
            updateServerCard();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось безопасно сохранить пароль", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean validHost(String value) {
        return value.matches("[A-Za-z0-9._:-]+") && !value.contains(" ");
    }

    private boolean validPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateServerCard() {
        ServerProfiles.Profile active =
                ServerProfiles.active(new SecureStore(this));
        if (active == null) {
            serverName.setText("Сервер не добавлен");
            serverAddress.setText("Добавь первый сервер");
            serverSelect.setText("ДОБАВИТЬ");
            serverEdit.setText("ДОБАВИТЬ");
            return;
        }
        serverName.setText(active.name);
        serverAddress.setText(active.host + ":" + active.sshPort
                + " · " + active.user);
        serverSelect.setText("СМЕНИТЬ");
        serverEdit.setText("ПАРАМЕТРЫ");
    }

    private void showServerList() {
        SecureStore store = new SecureStore(this);
        List<ServerProfiles.Profile> profiles = ServerProfiles.list(store);
        if (profiles.isEmpty()) {
            showServerEditor(null);
            return;
        }
        String[] labels = new String[profiles.size()];
        ServerProfiles.Profile active = ServerProfiles.active(store);
        for (int i = 0; i < profiles.size(); i++) {
            ServerProfiles.Profile profile = profiles.get(i);
            labels[i] = (active != null && active.id.equals(profile.id) ? "✓ " : "")
                    + profile.name + "\n" + profile.host + ":" + profile.sshPort;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Выбери сервер")
                .setItems(labels, (ignored, which) ->
                        switchToServer(profiles.get(which)))
                .setNegativeButton("Закрыть", null);
        if (!running) {
            builder.setNeutralButton("Добавить сервер",
                    (ignored, which) -> showServerEditor(null));
        }
        builder.show();
    }

    private void switchToServer(ServerProfiles.Profile profile) {
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile active = ServerProfiles.active(store);
        if (active != null && active.id.equals(profile.id)) return;
        boolean reconnect = running;
        if (reconnect) stopTunnel();
        try {
            ServerProfiles.activate(store, profile.id);
            loadSettings();
            Toast.makeText(this, "Выбран сервер «" + profile.name + "»",
                    Toast.LENGTH_SHORT).show();
            if (reconnect) toggle.postDelayed(this::startTunnel, 900);
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось переключить сервер",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showServerEditor(ServerProfiles.Profile profile) {
        if (running) {
            Toast.makeText(this, "Сначала отключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        SecureStore store = new SecureStore(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 4, padding, 4);

        EditText profileName = addServerField(fields, "Название сервера",
                profile == null ? "" : profile.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText profileHost = addServerField(fields, "IP-адрес или домен",
                profile == null ? "" : profile.host,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText profileSshPort = addServerField(fields, "SSH-порт",
                profile == null ? "22" : profile.sshPort,
                InputType.TYPE_CLASS_NUMBER);
        EditText profileSocksPort = addServerField(fields, "Локальный SOCKS5-порт",
                profile == null ? "1080" : profile.socksPort,
                InputType.TYPE_CLASS_NUMBER);
        EditText profileUser = addServerField(fields, "Пользователь SSH",
                profile == null ? "root" : profile.user,
                InputType.TYPE_CLASS_TEXT);
        EditText profilePassword = addServerField(fields, "Пароль SSH",
                profile == null ? "" : ServerProfiles.password(store, profile.id),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText profileWindow = addTuningField(fields,
                "Окно SSH · КиБ", "Буфер передачи. Рекомендуется 1024.",
                profile == null ? NetworkTuning.DEFAULT_WINDOW_KIB : profile.windowKiB);
        EditText profilePacket = addTuningField(fields,
                "Пакет SSH · КиБ", "Размер блока. Рекомендуется 32.",
                profile == null ? NetworkTuning.DEFAULT_PACKET_KIB : profile.packetKiB);
        EditText profileMtu = addTuningField(fields,
                "MTU VPN", "8500 быстрее; 1500 полезно при зависаниях.",
                profile == null ? NetworkTuning.DEFAULT_MTU : profile.mtu);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(profile == null ? "Новый сервер" : "Параметры сервера")
                .setView(scroll)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null);
        if (profile != null && ServerProfiles.list(store).size() > 1) {
            builder.setNeutralButton("Удалить", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String h = profileHost.getText().toString().trim();
                String ssh = profileSshPort.getText().toString().trim();
                String socks = profileSocksPort.getText().toString().trim();
                String u = profileUser.getText().toString().trim();
                String pw = profilePassword.getText().toString();
                int window = parseInt(profileWindow);
                int packet = parseInt(profilePacket);
                int mtu = parseInt(profileMtu);
                if (!validHost(h) || !validPort(ssh) || !validPort(socks)
                        || u.isEmpty() || pw.isEmpty()
                        || !NetworkTuning.valid(window, NetworkTuning.MIN_WINDOW_KIB,
                        NetworkTuning.MAX_WINDOW_KIB)
                        || !NetworkTuning.valid(packet, NetworkTuning.MIN_PACKET_KIB,
                        NetworkTuning.MAX_PACKET_KIB)
                        || !NetworkTuning.valid(mtu, NetworkTuning.MIN_MTU,
                        NetworkTuning.MAX_MTU)) {
                    Toast.makeText(this, "Проверь адрес, порты, пароль и диапазоны тюнинга",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String name = profileName.getText().toString().trim();
                if (name.isEmpty()) name = h;
                ServerProfiles.Profile updated = new ServerProfiles.Profile(
                        profile == null
                                ? ServerProfiles.create(name, h, ssh, u, socks,
                                window, packet, mtu).id
                                : profile.id,
                        name, h, ssh, u, socks, window, packet, mtu);
                try {
                    ServerProfiles.saveAndActivate(store, updated, pw);
                    loadSettings();
                    dialog.dismiss();
                    Toast.makeText(this, "Сервер сохранён", Toast.LENGTH_SHORT).show();
                    maybeOfferTlsForCurrentServer(null);
                } catch (Exception error) {
                    Toast.makeText(this, "Не удалось сохранить сервер",
                            Toast.LENGTH_LONG).show();
                }
            });
            if (profile != null && dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                        confirmDeleteServer(dialog, profile));
            }
        });
        dialog.show();
    }

    private EditText addServerField(
            LinearLayout parent, String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(inputType);
        field.setSingleLine(true);
        parent.addView(field);
        return field;
    }

    private void confirmDeleteServer(
            AlertDialog editor, ServerProfiles.Profile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить сервер «" + profile.name + "»?")
                .setMessage("Будут удалены сохранённый пароль, TLS-ключи и параметры "
                        + "этого профиля. Сам сервер изменён не будет.")
                .setPositiveButton("Удалить", (ignored, which) -> {
                    try {
                        if (ServerProfiles.delete(new SecureStore(this), profile.id)) {
                            editor.dismiss();
                            loadSettings();
                            Toast.makeText(this, "Профиль сервера удалён",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception error) {
                        Toast.makeText(this, "Не удалось удалить профиль",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void beginExport() {
        if (!saveSettings()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "ssh-tunnel-config.sshtunnel.json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void beginImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void writeConfig(Uri uri) {
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new Exception("No output stream");
            SecureStore store = new SecureStore(this);
            ServerProfiles.Profile active = ServerProfiles.active(store);
            JSONObject config = new JSONObject()
                    .put("format", 1)
                    .put("name", active == null ? host.getText().toString().trim()
                            : active.name)
                    .put("host", host.getText().toString().trim())
                    .put("ssh_port", Integer.parseInt(sshPort.getText().toString().trim()))
                    .put("username", user.getText().toString().trim())
                    .put("password", password.getText().toString())
                    .put("socks_port", Integer.parseInt(socksPort.getText().toString().trim()))
                    .put("vpn_mode", enableVpn.isChecked())
                    .put("telegram_proxy", enableTelegram.isChecked())
                    .put("auto_reconnect", autoReconnect.isChecked())
                    .put("start_on_boot", startOnBoot.isChecked())
                    .put("ssh_window_kib", NetworkTuning.windowKiB(store))
                    .put("ssh_packet_kib", NetworkTuning.packetKiB(store))
                    .put("vpn_mtu", NetworkTuning.vpnMtu(store))
                    .put("contains_plaintext_password", true);
            output.write(config.toString(2).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this,
                    "Конфигурация сохранена. В файле находится пароль — передавай его только доверенному человеку.",
                    Toast.LENGTH_LONG).show();
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newUri(getContentResolver(), "SSH Tunnel config", uri));
            startActivity(Intent.createChooser(share, "Поделиться конфигурацией"));
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось экспортировать конфигурацию", Toast.LENGTH_LONG).show();
        }
    }

    private void readConfig(Uri uri) {
        if (uri == null || running) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("No input stream");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 64 * 1024) throw new Exception("Config is too large");
                bytes.write(buffer, 0, count);
            }
            JSONObject config = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (config.optInt("format", -1) != 1) throw new Exception("Unsupported format");

            String importedHost = config.getString("host").trim();
            String importedPort = Integer.toString(config.getInt("ssh_port"));
            String importedUser = config.getString("username").trim();
            String importedPassword = config.getString("password");
            String importedSocksPort = Integer.toString(config.getInt("socks_port"));
            int importedWindow = config.optInt(
                    "ssh_window_kib", NetworkTuning.DEFAULT_WINDOW_KIB);
            int importedPacket = config.optInt(
                    "ssh_packet_kib", NetworkTuning.DEFAULT_PACKET_KIB);
            int importedMtu = config.optInt("vpn_mtu", NetworkTuning.DEFAULT_MTU);
            if (!validHost(importedHost) || !validPort(importedPort)
                    || importedUser.isEmpty() || importedPassword.isEmpty()
                    || !validPort(importedSocksPort)
                    || !NetworkTuning.valid(importedWindow,
                    NetworkTuning.MIN_WINDOW_KIB, NetworkTuning.MAX_WINDOW_KIB)
                    || !NetworkTuning.valid(importedPacket,
                    NetworkTuning.MIN_PACKET_KIB, NetworkTuning.MAX_PACKET_KIB)
                    || !NetworkTuning.valid(importedMtu,
                    NetworkTuning.MIN_MTU, NetworkTuning.MAX_MTU)) {
                throw new Exception("Invalid config");
            }

            SecureStore store = new SecureStore(this);
            String importedName = config.optString("name", importedHost).trim();
            if (importedName.isEmpty()) importedName = importedHost;
            ServerProfiles.Profile importedProfile = ServerProfiles.create(
                    importedName, importedHost, importedPort, importedUser,
                    importedSocksPort, importedWindow, importedPacket, importedMtu);
            ServerProfiles.saveAndActivate(store, importedProfile, importedPassword);
            loadSettings();
            autoReconnect.setChecked(config.optBoolean("auto_reconnect", true));
            startOnBoot.setChecked(config.optBoolean("start_on_boot", false));
            boolean importedVpn = config.optBoolean("vpn_mode", false);
            enableVpn.setChecked(importedVpn);
            enableTelegram.setChecked(config.optBoolean("telegram_proxy", !importedVpn));
            if (!saveSettings()) throw new Exception("Could not save config");
            loadSettings();
            Toast.makeText(this, "Конфигурация импортирована", Toast.LENGTH_SHORT).show();
            maybeOfferTlsForCurrentServer(null);
        } catch (Exception error) {
            Toast.makeText(this, "Файл конфигурации повреждён или несовместим", Toast.LENGTH_LONG).show();
        }
    }

    private void openTelegramProxy() {
        String port = socksPort.getText().toString().trim();
        if (!validPort(port)) {
            Toast.makeText(this, "Сначала укажи правильный SOCKS5-порт", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri telegram = new Uri.Builder()
                .scheme("tg")
                .authority("socks")
                .appendQueryParameter("server", "127.0.0.1")
                .appendQueryParameter("port", port)
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, telegram));
        } catch (ActivityNotFoundException ignored) {
            Uri web = Uri.parse("https://t.me/socks?server=127.0.0.1&port=" + Uri.encode(port));
            startActivity(new Intent(Intent.ACTION_VIEW, web));
        }
    }

    private void showStoredTotals() {
        SecureStore store = new SecureStore(this);
        updateStatsValues(0, -1, 0, 0,
                store.getLong("total_uploaded", 0),
                store.getLong("total_downloaded", 0));
    }

    private void updateStats(Intent intent) {
        updateStatsValues(
                intent.getLongExtra("speed_bps", 0),
                intent.getIntExtra("ping_ms", -1),
                intent.getLongExtra("session_uploaded", 0),
                intent.getLongExtra("session_downloaded", 0),
                intent.getLongExtra("total_uploaded", 0),
                intent.getLongExtra("total_downloaded", 0));
        if (Branding.isSecret(this) && intent.getBooleanExtra("debug_enabled", false)) {
            debugInfo.setText("Версия: " + intent.getStringExtra("debug_version")
                    + "\nПрофиль: " + intent.getStringExtra("debug_profile")
                    + "\nСтатус: " + intent.getStringExtra("debug_status")
                    + "\nSSH: "
                    + (intent.getBooleanExtra("debug_ssh_connected", false)
                    ? "соединение установлено" : "нет соединения")
                    + "\nАдрес: " + intent.getStringExtra("debug_ssh_endpoint")
                    + "\nТранспорт: " + intent.getStringExtra("debug_transport")
                    + "\nРежим: " + intent.getStringExtra("debug_mode")
                    + "\nСеть: " + intent.getStringExtra("debug_network")
                    + "\nРабочие SOCKS: "
                    + intent.getStringExtra("debug_socks_ports")
                    + "\nTG / VPN: "
                    + yesNo(intent.getBooleanExtra("debug_tg_running", false))
                    + " / " + yesNo(intent.getBooleanExtra("debug_vpn_running", false))
                    + "\nАвтопереподключение: "
                    + yesNo(intent.getBooleanExtra("debug_auto_reconnect", true))
                    + "\nОкно/пакет/MTU: "
                    + intent.getStringExtra("debug_tuning")
                    + "\nВремя работы: "
                    + formatDuration(intent.getLongExtra("debug_uptime_ms", 0))
                    + "\nПопытки подключения: "
                    + intent.getIntExtra("debug_connect_attempts", 0));
        }
    }

    private void updateStatsValues(long speed, int ping, long sessionUp, long sessionDown,
                                   long totalUp, long totalDown) {
        speedPing.setText("Скорость: " + formatRate(speed)
                + " · Пинг: " + (ping >= 0 ? ping + " мс" : "—"));
        this.sessionDown.setText(formatBytes(sessionDown));
        this.sessionUp.setText(formatBytes(sessionUp));
        this.totalDown.setText(formatBytes(totalDown));
        this.totalUp.setText(formatBytes(totalUp));
    }

    private String formatRate(long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/с";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double value = bytes;
        String[] units = {"КБ", "МБ", "ГБ", "ТБ"};
        for (String unit : units) {
            value /= 1024.0;
            if (value < 1024.0 || unit.equals("ТБ")) {
                return String.format(Locale.getDefault(), "%.1f %s", value, unit);
            }
        }
        return bytes + " Б";
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    private void updateDebugPanel() {
        boolean secret = Branding.isSecret(this);
        findViewById(R.id.debugPanel).setVisibility(secret ? View.VISIBLE : View.GONE);
        if (secret && !running) {
            SecureStore store = new SecureStore(this);
            String configuredHost = store.getPlain("host", "").trim();
            debugInfo.setText("Версия: " + BuildConfig.VERSION_NAME
                    + "\nСтатус: сервис отключён"
                    + "\nРежим: " + TunnelMode.label(store)
                    + "\nTLS: " + yesNo(TlsTransport.isEnabledFor(store, configuredHost))
                    + "\nАвтопереподключение: "
                    + yesNo(store.getBoolean("auto_reconnect", true))
                    + "\nОкно/пакет/MTU: " + NetworkTuning.windowKiB(store) + "/"
                    + NetworkTuning.packetKiB(store) + "/"
                    + NetworkTuning.vpnMtu(store));
        }
    }

    private String yesNo(boolean value) {
        return value ? "да" : "нет";
    }

    private void startTunnel() {
        if (ServerProfiles.active(new SecureStore(this)) == null) {
            showServerEditor(null);
            return;
        }
        if (Branding.isSecretInput(
                host.getText().toString(), password.getText().toString())) {
            boolean enabled = Branding.toggleSecret(this);
            appTitle.setText(Branding.appName(this));
            updateDebugPanel();
            Toast.makeText(this, enabled
                    ? "Huyna VPN debug mode activated"
                    : "Возвращено название «Пельмени VPN»", Toast.LENGTH_LONG).show();
            return;
        }
        if (!saveSettings()) return;
        if (maybeOfferTlsForCurrentServer(this::continueStartTunnel)) return;
        continueStartTunnel();
    }

    private void continueStartTunnel() {
        if (enableVpn.isChecked()) {
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                startActivityForResult(permission, REQUEST_VPN);
            } else {
                startVpnTunnel();
            }
            return;
        }
        Intent intent = new Intent(this, TunnelService.class).setAction(TunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        update("Подключение…");
    }

    private boolean maybeOfferTlsForCurrentServer(Runnable continueWithoutTls) {
        SecureStore store = new SecureStore(this);
        String currentHost = host.getText().toString().trim();
        TlsTransport.enableAutomatically(store, currentHost);
        boolean configured = TlsTransport.isConfigured(store)
                && currentHost.equalsIgnoreCase(store.getPlain("tls_host", ""));
        if (configured || currentHost.isEmpty()
                || currentHost.equalsIgnoreCase(
                store.getPlain("tls_offer_shown_host", ""))) {
            return false;
        }

        store.putPlain("tls_offer_shown_host", currentHost);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Защитить новый сервер?")
                .setMessage("Для этого сервера в приложении ещё нет пригодной TLS-настройки. "
                        + "TLS маскирует SSH под защищённое HTTPS-соединение и может снизить "
                        + "вероятность автоматического обнаружения и блокировки IP.\n\n"
                        + "Приложение переиспользует Pelmeni TLS, если он уже установлен, "
                        + "либо автоматически настроит Debian/Ubuntu. "
                        + "Это не гарантирует защиту от прямой блокировки IP.")
                .setPositiveButton("Включить TLS",
                        (ignored, which) -> confirmServerTlsSetup());
        if (continueWithoutTls == null) {
            builder.setNegativeButton("Позже", null);
        } else {
            builder.setNegativeButton("Подключиться без TLS",
                    (ignored, which) -> continueWithoutTls.run());
        }
        builder.show();
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpnTunnel();
        } else if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && data != null) {
            writeConfig(data.getData());
        } else if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            readConfig(data.getData());
        }
    }

    private void startVpnTunnel() {
        Intent sshIntent = new Intent(this, TunnelService.class).setAction(TunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(sshIntent);
        else startService(sshIntent);
        Intent vpnIntent = new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpnIntent);
        else startService(vpnIntent);
        update("Starting VPN...");
    }

    private void stopTunnel() {
        startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
        update("Отключено");
    }

    private void update(String text) {
        if (text == null) return;
        status.setText(text);
        running = !text.equals("Отключено");
        toggle.setText(running ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ");
        setSettingsEnabled(!running);
        updateDebugPanel();
    }

    private void setSettingsEnabled(boolean enabled) {
        host.setEnabled(enabled);
        sshPort.setEnabled(enabled);
        user.setEnabled(enabled);
        password.setEnabled(enabled);
        socksPort.setEnabled(enabled);
        save.setEnabled(enabled);
        showPassword.setEnabled(enabled);
        autoReconnect.setEnabled(enabled);
        startOnBoot.setEnabled(enabled);
        enableVpn.setEnabled(enabled);
        enableTelegram.setEnabled(enabled);
        serverSelect.setEnabled(true);
        serverEdit.setEnabled(enabled);
    }
}
