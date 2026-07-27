package com.example.sshtunnel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.VpnService;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String EXTRA_START_FROM_TILE = "start_from_tile";
    private static final int REQUEST_VPN = 9;
    private static final int REQUEST_EXPORT = 20;
    private static final int REQUEST_IMPORT = 21;
    private static final int REQUEST_SPLIT_EXPORT = 22;
    private static final int REQUEST_SPLIT_IMPORT = 23;
    private static final long UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    private EditText host, sshPort, user, password, socksPort;
    private TextView appTitle, status, speedPing, debugInfo, serverName, serverAddress;
    private TextView userLimitSummary;
    private TextView splitTunnelSummary;
    private TextView sessionDown, sessionUp, totalDown, totalUp;
    private Button toggle, toggleTelegram, toggleVpn;
    private ConnectionRingView ringTelegram, ringVpn;
    private Button save, serverSelect, serverEdit, splitTunnelButton;
    private CheckBox showPassword, autoReconnect, startOnBoot, enableVpn, enableTelegram;
    private ScrollView mainScroll;
    private FrameLayout contentContainer;
    private View navHome, navPeople, navSettings, navAdd;
    private View activePage;
    private View splitRouteProgress;
    private String peopleServerId;
    private boolean running;
    private boolean proxyConnecting;
    private boolean vpnConnecting;
    private boolean waitingForVpnReady;
    private boolean routingApplying;
    private boolean receiverRegistered;
    private boolean suppressModeChanges;
    private boolean pendingLiveVpnPermission;
    private volatile boolean speedTestRunning;
    private volatile boolean serverSetupRunning;
    private volatile boolean updateCheckRunning;
    private final ExecutorService speedWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("speed_bps")) updateStats(intent);
            String limitWarning = intent.getStringExtra("limit_warning");
            if (limitWarning != null) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Лимит доступа")
                        .setMessage(limitWarning)
                        .setPositiveButton("Понятно", null)
                        .show();
            }
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
        userLimitSummary = findViewById(R.id.userLimitSummary);
        debugInfo = findViewById(R.id.debugInfo);
        sessionDown = findViewById(R.id.sessionDown);
        sessionUp = findViewById(R.id.sessionUp);
        totalDown = findViewById(R.id.totalDown);
        totalUp = findViewById(R.id.totalUp);
        serverName = findViewById(R.id.serverName);
        serverAddress = findViewById(R.id.serverAddress);
        splitTunnelSummary = findViewById(R.id.splitTunnelSummary);
        toggle = findViewById(R.id.toggle);
        toggleTelegram = findViewById(R.id.toggleTelegram);
        toggleVpn = findViewById(R.id.toggleVpn);
        ringTelegram = findViewById(R.id.ringTelegram);
        ringVpn = findViewById(R.id.ringVpn);
        splitRouteProgress = findViewById(R.id.splitRouteProgress);
        save = findViewById(R.id.save);
        serverSelect = findViewById(R.id.serverSelect);
        serverEdit = findViewById(R.id.serverEdit);
        splitTunnelButton = findViewById(R.id.splitTunnelButton);
        showPassword = findViewById(R.id.showPassword);
        autoReconnect = findViewById(R.id.autoReconnect);
        startOnBoot = findViewById(R.id.startOnBoot);
        enableVpn = findViewById(R.id.enableVpn);
        enableTelegram = findViewById(R.id.enableTelegram);
        mainScroll = findViewById(R.id.mainScroll);
        contentContainer = findViewById(R.id.contentContainer);
        navHome = findViewById(R.id.navHome);
        navPeople = findViewById(R.id.navPeople);
        navSettings = findViewById(R.id.navSettings);
        navAdd = findViewById(R.id.navAdd);

        ServerProfiles.migrateLegacy(new SecureStore(this));
        SplitTunnel.ensureDefaults(new SecureStore(this));
        Branding.restoreLauncherState(this);
        appTitle.setText(Branding.appName(this));
        updateDebugPanel();
        loadSettings();
        running = new SecureStore(this).getBoolean("enabled", false)
                && TunnelService.isActive();
        update(!running ? "Отключено"
                : TunnelService.isConnected()
                ? "Подключено · " + TunnelMode.portsLabel(new SecureStore(this))
                : "Подключение…");

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
        enableVpn.setOnCheckedChangeListener((button, checked) -> {
            if (suppressModeChanges) return;
            new SecureStore(this).putBoolean("vpn_mode", checked);
            updateModeButtons();
            applyLiveModeChange(true, checked);
        });
        enableTelegram.setOnCheckedChangeListener((button, checked) -> {
            if (suppressModeChanges) return;
            new SecureStore(this).putBoolean("telegram_proxy", checked);
            updateModeButtons();
            applyLiveModeChange(false, checked);
        });
        toggleTelegram.setOnClickListener(v -> toggleModeFromButton(false));
        toggleVpn.setOnClickListener(v -> toggleModeFromButton(true));

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
            if (active == null) showAddServerChoice();
            else showServerEditor(active);
        });
        findViewById(R.id.serverProfileCard).setOnClickListener(v -> showServerList());
        splitTunnelButton.setOnClickListener(v -> showSplitTunnelPage());
        navHome.setOnClickListener(v -> showHomePage());
        navPeople.setOnClickListener(v -> showPeoplePage(null, null));
        navSettings.setOnClickListener(v -> showSettingsHub());
        navAdd.setOnClickListener(v -> showAddServerChoice());
        setSelectedNav(navHome);
        if (getIntent().getBooleanExtra(EXTRA_START_FROM_TILE, false)) {
            getIntent().removeExtra(EXTRA_START_FROM_TILE);
            toggle.post(() -> {
                if (!running) startTunnel();
            });
        }
        maybeCheckForUpdate(false);
    }

    @Override public void onBackPressed() {
        if (activePage != null) {
            showHomePage();
            return;
        }
        super.onBackPressed();
    }

    private void showPeoplePage(
            List<ServerAccessManager.ManagedUser> users, String loadError) {
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile active = peopleServer(store);
        LinearLayout page = createPageContent("Люди",
                active == null ? "Доступ к VPN" : "Пользователи сервера «" + active.name + "»");
        addPageAction(page, "Ввести код доступа",
                "Добавить готовый сервер, которым с тобой поделились",
                this::showImportAccessCode);
        if (active == null) {
            addCardSubtitle(page,
                    "Добавь профиль администратора с root/sudo, чтобы управлять пользователями.");
            showScrollablePage(page, navPeople);
            return;
        }
        List<ServerProfiles.Profile> adminServers = adminServers(store);
        if (adminServers.size() > 1) {
            addSectionTitle(page, "Сервер администратора");
            LinearLayout selector = createCard();
            RadioGroup choices = new RadioGroup(this);
            for (ServerProfiles.Profile server : adminServers) {
                RadioButton choice = createRadio(server.name,
                        server.host + ":" + server.sshPort);
                choice.setTag(server.id);
                choice.setChecked(server.id.equals(active.id));
                choices.addView(choice);
            }
            choices.setOnCheckedChangeListener((group, id) -> {
                RadioButton selected = group.findViewById(id);
                if (selected == null) return;
                peopleServerId = String.valueOf(selected.getTag());
                showPeoplePage(null, null);
            });
            selector.addView(choices);
            page.addView(selector, pageCardParams());
        }
        addSectionTitle(page, "Управление сервером");
        addPageAction(page, "Добавить человека",
                "Отдельный логин, срок, лимиты трафика и скорости",
                this::showCreateManagedUser);
        if (users == null && loadError == null) {
            LinearLayout loading = createCard();
            addCardTitle(loading, "Загружаю пользователей…");
            addCardSubtitle(loading,
                    "При первом запуске сервер установит небольшой контроллер лимитов.");
            page.addView(loading, pageCardParams());
            showScrollablePage(page, navPeople);
            loadManagedUsers();
            return;
        }
        if (loadError != null) {
            LinearLayout error = createCard();
            addCardTitle(error, "Не удалось открыть список");
            addCardSubtitle(error, loadError
                    + "\n\nУправлять людьми можно только из профиля администратора с root/sudo.");
            page.addView(error, pageCardParams());
            addPageAction(page, "Повторить", "Снова подключиться к серверу",
                    this::loadManagedUsers);
            showScrollablePage(page, navPeople);
            return;
        }
        addSectionTitle(page, "Пользователи · " + users.size());
        if (users.isEmpty()) {
            LinearLayout empty = createCard();
            addCardTitle(empty, "Пока никого нет");
            addCardSubtitle(empty,
                    "Добавь человека и отправь ему секретный код. Админский пароль в код не попадёт.");
            page.addView(empty, pageCardParams());
        }
        for (ServerAccessManager.ManagedUser managed : users) {
            LinearLayout card = createCard();
            addCardTitle(card, managed.label + "  ·  " + managed.login);
            String expiry = managed.forever() ? "бессрочно" : "до " + managed.expires;
            String daily = managed.dailyMb <= 0 ? "∞" : managed.dailyMb + " МБ/день";
            String monthly = managed.monthlyMb <= 0 ? "∞" : managed.monthlyMb + " МБ/месяц";
            String speed = managed.speedMbps <= 0 ? "без ограничения"
                    : managed.speedMbps + " Мбит/с";
            String state = managedUserState(managed);
            addCardSubtitle(card, state + " · " + expiry
                    + "\nСегодня: " + formatBytes(managed.dayBytes)
                    + (managed.dailyMb > 0 ? " / " + managed.dailyMb + " МБ" : "")
                    + " · месяц: " + formatBytes(managed.monthBytes)
                    + (managed.monthlyMb > 0 ? " / " + managed.monthlyMb + " МБ" : "")
                    + "\nЛимиты: " + daily + " · " + monthly + " · " + speed);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setPadding(0, dp(10), 0, 0);
            LinearLayout primary = createUserActionRow();
            addUserAction(primary, "СТАТУС И ТРАФИК", false,
                    () -> showManagedUserStatus(managed), false);
            addUserAction(primary, "КОД И QR", false,
                    () -> showAccessCode(managed), true);
            actions.addView(primary);
            LinearLayout secondary = createUserActionRow();
            secondary.setPadding(0, dp(8), 0, 0);
            addUserAction(secondary, "ПРОДЛИТЬ", false,
                    () -> showExtendManagedUser(managed), false);
            addUserAction(secondary, "ОТОЗВАТЬ", true,
                    () -> confirmRevokeManagedUser(managed), true);
            actions.addView(secondary);
            LinearLayout limits = createUserActionRow();
            limits.setPadding(0, dp(8), 0, 0);
            addUserAction(limits, "ИЗМЕНИТЬ ЛИМИТЫ", false,
                    () -> showEditManagedUserLimits(managed), false);
            actions.addView(limits);
            card.addView(actions);
            page.addView(card, pageCardParams());
        }
        addPageAction(page, "Обновить список", "Получить актуальные данные с сервера",
                this::loadManagedUsers);
        showScrollablePage(page, navPeople);
    }

    private LinearLayout createUserActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addUserAction(
            LinearLayout row, String label, boolean danger,
            Runnable action, boolean addLeftMargin) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(danger ? 0xFFFF7272 : 0xFFE8EAF0);
        button.setTextSize(13);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setBackgroundResource(R.drawable.settings_action_background);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(52), 1);
        if (addLeftMargin) params.leftMargin = dp(8);
        row.addView(button, params);
    }

    private String managedUserState(ServerAccessManager.ManagedUser managed) {
        if (managed.expired) return "ИСТЁК";
        if (managed.blocked) return "ЛИМИТ ИСЧЕРПАН";
        boolean hasLimits = managed.dailyMb > 0
                || managed.monthlyMb > 0 || managed.speedMbps > 0;
        if (hasLimits && !managed.policyHealthy) return "ОШИБКА ЛИМИТОВ";
        return "АКТИВЕН";
    }

    private void showManagedUserStatus(ServerAccessManager.ManagedUser managed) {
        String checked = managed.statusUpdatedAt <= 0 ? "нет данных"
                : android.text.format.DateFormat.getDateFormat(this).format(
                new java.util.Date(managed.statusUpdatedAt * 1000L))
                + " " + android.text.format.DateFormat.getTimeFormat(this).format(
                new java.util.Date(managed.statusUpdatedAt * 1000L));
        String message = "Статус: " + managedUserState(managed)
                + "\nЛогин: " + managed.login
                + "\nДействует: " + (managed.forever() ? "бессрочно" : "до " + managed.expires)
                + "\n\nСегодня: " + formatBytes(managed.dayBytes)
                + (managed.dailyMb > 0 ? " из " + managed.dailyMb + " МБ" : "")
                + "\nВ этом месяце: " + formatBytes(managed.monthBytes)
                + (managed.monthlyMb > 0 ? " из " + managed.monthlyMb + " МБ" : "")
                + "\nОграничение скорости: "
                + (managed.speedMbps > 0 ? managed.speedMbps + " Мбит/с" : "нет")
                + "\n\nКонтроллер: "
                + (managed.policyHealthy ? "работает" : "ошибка")
                + "\nПоследняя проверка: " + checked;
        if (!managed.policyHealthy && !managed.policyError.isEmpty()) {
            message += "\nОшибка: " + managed.policyError;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(4), dp(22), 0);
        TextView details = new TextView(this);
        details.setText(message);
        details.setTextColor(0xFFE1E3E8);
        details.setTextSize(14);
        content.addView(details);
        if (managed.dailyMb > 0) {
            addLimitProgress(content, "Дневной трафик",
                    managed.dayBytes, managed.dailyMb);
        }
        if (managed.monthlyMb > 0) {
            addLimitProgress(content, "Месячный трафик",
                    managed.monthBytes, managed.monthlyMb);
        }
        new AlertDialog.Builder(this)
                .setTitle(managed.label)
                .setView(content)
                .setPositiveButton("Обновить", (dialog, which) -> loadManagedUsers())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void addLimitProgress(
            LinearLayout parent, String title, long usedBytes, long limitMb) {
        double ratio = usedBytes / (limitMb * 1024.0 * 1024.0);
        TextView label = new TextView(this);
        label.setText(title + " · " + Math.min(100, (int) (ratio * 100)) + "%");
        label.setTextColor(ratio >= 1 ? 0xFFFF7272
                : ratio >= 0.75 ? 0xFFFFAA5B : 0xFFD7DAE0);
        label.setTextSize(13);
        label.setPadding(0, dp(16), 0, dp(5));
        parent.addView(label);
        ProgressBar bar = new ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.min(1000, ratio * 1000));
        bar.setProgressTintList(ColorStateList.valueOf(
                ratio >= 1 ? 0xFFFF7272 : ratio >= 0.75 ? 0xFFFFAA5B : 0xFF77C68A));
        parent.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
    }

    private void loadManagedUsers() {
        ServerProfiles.Profile server = peopleServer(new SecureStore(this));
        speedWorker.execute(() -> {
            try {
                List<ServerAccessManager.ManagedUser> users =
                        ServerAccessManager.list(new SecureStore(this), server);
                mainHandler.post(() -> showPeoplePage(users, null));
            } catch (Exception error) {
                mainHandler.post(() -> showPeoplePage(null,
                        error.getMessage() == null ? "Неизвестная ошибка." : error.getMessage()));
            }
        });
    }

    private List<ServerProfiles.Profile> adminServers(SecureStore store) {
        List<ServerProfiles.Profile> result = new java.util.ArrayList<>();
        for (ServerProfiles.Profile profile : ServerProfiles.list(store)) {
            if (!profile.user.startsWith("pel_")
                    && !ServerProfiles.password(store, profile.id).isEmpty()) {
                result.add(profile);
            }
        }
        return result;
    }

    private ServerProfiles.Profile peopleServer(SecureStore store) {
        List<ServerProfiles.Profile> servers = adminServers(store);
        for (ServerProfiles.Profile profile : servers) {
            if (profile.id.equals(peopleServerId)) return profile;
        }
        ServerProfiles.Profile active = ServerProfiles.active(store);
        for (ServerProfiles.Profile profile : servers) {
            if (active != null && profile.id.equals(active.id)) {
                peopleServerId = profile.id;
                return profile;
            }
        }
        if (servers.isEmpty()) return null;
        peopleServerId = servers.get(0).id;
        return servers.get(0);
    }

    private void showAddServerChoice() {
        LinearLayout page = createPageContent("Добавить сервер",
                "Выбери, как ты хочешь подключиться.");
        addPageAction(page, "Настроить свой сервер",
                "Ввести IP, SSH-пользователя, пароль и параметры сервера",
                () -> showServerEditor(null));
        addPageAction(page, "Добавить по коду",
                "Вставить текстовый код, который создал владелец VPN-сервера",
                this::showImportAccessCode);
        addPageAction(page, "Сканировать QR-код",
                "Навести камеру на QR владельца VPN-сервера",
                this::scanAccessQr);
        showScrollablePage(page, navAdd);
    }

    private void scanAccessQr() {
        IntentIntegrator scanner = new IntentIntegrator(this);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("Наведи камеру на QR-код Пельмени VPN");
        scanner.setBeepEnabled(false);
        scanner.setBarcodeImageEnabled(false);
        scanner.setOrientationLocked(false);
        scanner.initiateScan();
    }

    private void showImportAccessCode() {
        EditText input = new EditText(this);
        input.setHint("PEL1-…");
        input.setMinLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(20), 0, dp(20), 0);
        wrapper.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Код доступа")
                .setMessage("Вставь код от владельца сервера. Он добавится как отдельный профиль.")
                .setView(wrapper)
                .setPositiveButton("Добавить", (dialog, which) ->
                        importAccessCode(input.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void importAccessCode(String code) {
        boolean reconnect = running;
        if (reconnect) stopTunnel();
        try {
            ServerProfiles.Profile profile = ServerAccessCode.importCode(
                    new SecureStore(this), code);
            loadSettings();
            Toast.makeText(this, "Добавлен сервер «" + profile.name + "»",
                    Toast.LENGTH_LONG).show();
            showHomePage();
            if (reconnect) toggle.postDelayed(this::startTunnel, 900);
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            if (reconnect) toggle.postDelayed(this::startTunnel, 900);
        }
    }

    private void showCreateManagedUser() {
        LinearLayout page = createPageContent("Новый пользователь",
                "У него будет отдельный SSH-доступ без пароля администратора.");
        EditText label = addServerField(page, "Имя человека", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText login = addServerField(page, "Логин · латиницей", "",
                InputType.TYPE_CLASS_TEXT);
        addSectionTitle(page, "Срок действия");
        EditText duration = addServerField(page, "Количество дней · 0 навсегда", "0",
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, duration, "0", "1", "7", "30", "365");
        addSectionTitle(page, "Лимиты · 0 означает без ограничений");
        EditText daily = addServerField(page, "Трафик в день · МБ", "0",
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, daily, "0", "1024", "5120", "10240");
        EditText monthly = addServerField(page, "Трафик в месяц · МБ", "0",
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, monthly, "0", "10240", "51200", "102400");
        EditText speed = addServerField(page, "Скорость · Мбит/с", "0",
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, speed, "0", "5", "10", "25");
        Button create = new Button(this);
        create.setText("СОЗДАТЬ И ПОЛУЧИТЬ КОД");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        params.topMargin = dp(22);
        page.addView(create, params);
        create.setOnClickListener(v -> {
            String displayName = label.getText().toString().trim();
            if (displayName.isEmpty()) displayName = login.getText().toString().trim();
            long daysValue = parseLongOrNegative(duration);
            long dailyValue = parseLongOrNegative(daily);
            long monthlyValue = parseLongOrNegative(monthly);
            long speedValue = parseLongOrNegative(speed);
            if (displayName.isEmpty() || daysValue < 0 || daysValue > 36500
                    || dailyValue < 0 || monthlyValue < 0 || speedValue < 0) {
                Toast.makeText(this, "Проверь имя, срок и числовые лимиты", Toast.LENGTH_LONG).show();
                return;
            }
            String finalDisplayName = displayName;
            String finalLogin = login.getText().toString();
            create.setEnabled(false);
            create.setText("НАСТРАИВАЮ СЕРВЕР…");
            speedWorker.execute(() -> {
                try {
                    ServerAccessManager.ManagedUser managed = ServerAccessManager.create(
                            new SecureStore(this), peopleServer(new SecureStore(this)),
                            finalDisplayName, finalLogin, (int) daysValue,
                            dailyValue, monthlyValue, speedValue);
                    mainHandler.post(() -> {
                        showAccessCode(managed);
                        showPeoplePage(null, null);
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        create.setEnabled(true);
                        create.setText("СОЗДАТЬ И ПОЛУЧИТЬ КОД");
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        showScrollablePage(page, navPeople);
    }

    private long parseLongOrNegative(EditText field) {
        try {
            return Long.parseLong(field.getText().toString().trim());
        } catch (Exception error) {
            return -1;
        }
    }

    private void showAccessCode(ServerAccessManager.ManagedUser managed) {
        try {
            String code = managed.accessCode;
            if (code == null || code.isEmpty()) {
                throw new Exception("Сервер не вернул код доступа.");
            }
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(20), dp(8), dp(20), 0);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(createQrBitmap(code, 900));
            qr.setContentDescription("QR-код доступа для " + managed.label);
            qr.setAdjustViewBounds(true);
            content.addView(qr, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));
            TextView warning = new TextView(this);
            warning.setText("Отсканируй QR через «Добавить сервер». QR содержит пароль пользователя — показывай его только тому, кому доверяешь.");
            warning.setTextColor(0xFF9297A2);
            warning.setTextSize(13);
            warning.setPadding(0, dp(10), 0, 0);
            content.addView(warning);
            new AlertDialog.Builder(this)
                    .setTitle("Код для " + managed.label)
                    .setView(content)
                    .setPositiveButton("Копировать", (dialog, which) -> {
                        ClipboardManager clipboard =
                                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "Pelmeni VPN access", code));
                        Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("Поделиться", (dialog, which) -> {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_TEXT, code);
                        startActivity(Intent.createChooser(share, "Отправить код доступа"));
                    })
                    .setNegativeButton("Закрыть", null)
                    .show();
        } catch (Exception error) {
            Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap createQrBitmap(String value, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(
                value, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private void showExtendManagedUser(ServerAccessManager.ManagedUser managed) {
        EditText days = new EditText(this);
        days.setHint("Количество дней");
        days.setText("30");
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(dp(20), 0, dp(20), 0);
        wrapper.addView(days);
        new AlertDialog.Builder(this)
                .setTitle("Продлить " + managed.label)
                .setMessage("Укажи любое количество дней. Они прибавятся к текущему сроку. 0 — сделать доступ бессрочным.")
                .setView(wrapper)
                .setPositiveButton("Применить", (dialog, which) -> {
                    long value = parseLongOrNegative(days);
                    if (value < 0 || value > 36500) {
                        Toast.makeText(this, "Допустимо от 0 до 36500 дней",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    runExtendManagedUser(managed, (int) value);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showEditManagedUserLimits(
            ServerAccessManager.ManagedUser managed) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        EditText daily = addServerField(content, "Трафик в день · МБ",
                Long.toString(managed.dailyMb), InputType.TYPE_CLASS_NUMBER);
        EditText monthly = addServerField(content, "Трафик в месяц · МБ",
                Long.toString(managed.monthlyMb), InputType.TYPE_CLASS_NUMBER);
        EditText speed = addServerField(content, "Скорость · Мбит/с",
                Long.toString(managed.speedMbps), InputType.TYPE_CLASS_NUMBER);
        TextView note = new TextView(this);
        note.setText("0 означает отсутствие ограничения. Код доступа останется прежним: приложение пользователя получит новые лимиты с сервера автоматически.");
        note.setTextColor(0xFF9297A2);
        note.setTextSize(13);
        content.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Лимиты · " + managed.label)
                .setView(content)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    long dayValue = parseLongOrNegative(daily);
                    long monthValue = parseLongOrNegative(monthly);
                    long speedValue = parseLongOrNegative(speed);
                    if (dayValue < 0 || monthValue < 0 || speedValue < 0) {
                        Toast.makeText(this, "Лимиты должны быть целыми числами от 0",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    speedWorker.execute(() -> {
                        try {
                            SecureStore store = new SecureStore(this);
                            ServerAccessManager.updateLimits(
                                    store, peopleServer(store), managed.login,
                                    dayValue, monthValue, speedValue);
                            mainHandler.post(() -> {
                                Toast.makeText(this,
                                        "Лимиты применены · код менять не нужно",
                                        Toast.LENGTH_SHORT).show();
                                showPeoplePage(null, null);
                            });
                        } catch (Exception error) {
                            mainHandler.post(() -> Toast.makeText(
                                    this, error.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                }));
        dialog.show();
    }

    private void runExtendManagedUser(ServerAccessManager.ManagedUser managed, int days) {
        speedWorker.execute(() -> {
            try {
                SecureStore store = new SecureStore(this);
                ServerAccessManager.extend(
                        store, peopleServer(store), managed.login, days);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Срок обновлён", Toast.LENGTH_SHORT).show();
                    showPeoplePage(null, null);
                });
            } catch (Exception error) {
                mainHandler.post(() ->
                        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmRevokeManagedUser(ServerAccessManager.ManagedUser managed) {
        new AlertDialog.Builder(this)
                .setTitle("Отозвать доступ у " + managed.label + "?")
                .setMessage("Linux-учётная запись будет удалена с сервера. Старый код перестанет работать.")
                .setPositiveButton("Отозвать", (dialog, which) -> speedWorker.execute(() -> {
                    try {
                        SecureStore store = new SecureStore(this);
                        ServerAccessManager.revoke(
                                store, peopleServer(store), managed.login);
                        mainHandler.post(() -> showPeoplePage(null, null));
                    } catch (Exception error) {
                        mainHandler.post(() ->
                                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showSettingsHub() {
        LinearLayout page = createPageContent("Настройки",
                "Общие параметры приложения. Настройки конкретного сервера находятся "
                        + "у него под шестерёнкой.");

        addSectionTitle(page, "Подключение");
        addToggleCard(page, "Автопереподключение",
                "Восстанавливать туннель после смены Wi‑Fi или мобильной сети.",
                autoReconnect.isChecked(), checked -> autoReconnect.setChecked(checked));
        addToggleCard(page, "Запуск после перезагрузки",
                "Попытаться вернуть последнее активное подключение после запуска телефона.",
                startOnBoot.isChecked(), checked -> startOnBoot.setChecked(checked));

        addSectionTitle(page, "Обновления");
        LinearLayout channel = createCard();
        addCardTitle(channel, "Канал обновлений");
        addCardSubtitle(channel, Branding.isSecret(this)
                ? "В debug-режиме можно получать тестовые сборки."
                : "Beta-канал доступен только в Huyna debug mode.");
        RadioGroup channels = new RadioGroup(this);
        channels.setOrientation(RadioGroup.VERTICAL);
        RadioButton stable = createRadio("Стабильные версии",
                "Только проверенные полноценные релизы");
        RadioButton beta = createRadio("Beta и стабильные",
                "Новые функции раньше, возможны ошибки");
        beta.setEnabled(Branding.isSecret(this));
        channels.addView(stable);
        channels.addView(beta);
        boolean betaEnabled = Branding.isSecret(this)
                && new SecureStore(this).getBoolean("beta_updates", false);
        (betaEnabled ? beta : stable).setChecked(true);
        channels.setOnCheckedChangeListener((group, checkedId) ->
                new SecureStore(this).putBoolean("beta_updates",
                        checkedId == beta.getId() && beta.isEnabled()));
        channel.addView(channels);
        page.addView(channel, pageCardParams());
        addPageAction(page, "Проверить обновления сейчас",
                "Проверка выбранного выше канала",
                () -> maybeCheckForUpdate(true, Branding.isSecret(this)
                        && new SecureStore(this).getBoolean("beta_updates", false)));

        addSectionTitle(page, "Конфиги и инструменты");
        addPageAction(page, "Импортировать конфиг",
                "Добавить сервер из готового файла", this::beginImport);
        addPageAction(page, "Экспортировать активный сервер",
                "Создать файл и поделиться им", this::beginExport);
        addPageAction(page, "Настроить Telegram",
                "Передать Telegram ссылку на локальный SOCKS5", this::openTelegramProxy);
        addPageAction(page, "Проверить скорость",
                "Загрузка, выгрузка и пинг через активный туннель", this::confirmSpeedTest);

        addSectionTitle(page, "Обслуживание");
        addPageAction(page, "Сбросить временное состояние",
                "Не удаляет серверы, пароли, настройки и статистику",
                this::resetRuntimeState);
        showScrollablePage(page, navSettings);
    }

    private interface ToggleChange {
        void onChange(boolean checked);
    }

    private LinearLayout createPageContent(String title, String subtitle) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(28));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(0xFFF5F6F7);
        titleView.setTextSize(28);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(titleView);
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(0xFF9297A2);
        subtitleView.setTextSize(14);
        subtitleView.setPadding(0, dp(5), 0, dp(10));
        page.addView(subtitleView);
        return page;
    }

    private void showScrollablePage(LinearLayout page, View selectedNav) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page.setFocusableInTouchMode(true);
        scroll.addView(page);
        showPage(scroll, selectedNav);
        scroll.post(() -> {
            page.requestFocus();
            scroll.scrollTo(0, 0);
        });
    }

    private void showPage(View page, View selectedNav) {
        if (activePage != null) contentContainer.removeView(activePage);
        mainScroll.setVisibility(View.GONE);
        activePage = page;
        contentContainer.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setSelectedNav(selectedNav);
    }

    private void showHomePage() {
        if (activePage != null) {
            contentContainer.removeView(activePage);
            activePage = null;
        }
        mainScroll.setVisibility(View.VISIBLE);
        mainScroll.smoothScrollTo(0, 0);
        updateServerCard();
        setSelectedNav(navHome);
    }

    private void setSelectedNav(View selected) {
        tintNav(navHome, selected == navHome);
        tintNav(navPeople, selected == navPeople);
        tintNav(navSettings, selected == navSettings);
        tintNav(navAdd, selected == navAdd);
    }

    private void tintNav(View view, boolean selected) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout group = (LinearLayout) view;
        int color = selected ? 0xFFFFAA5B : 0xFFB8BBC3;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) ((TextView) child).setTextColor(color);
            if (child instanceof ImageView) {
                ((ImageView) child).setColorFilter(color);
            }
        }
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.settings_action_background);
        return card;
    }

    private LinearLayout.LayoutParams pageCardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        return params;
    }

    private void addSectionTitle(LinearLayout page, String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(0xFFF1F2F4);
        heading.setTextSize(19);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(18), 0, dp(2));
        page.addView(heading);
    }

    private void addCardTitle(LinearLayout card, String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextColor(0xFFF3F4F6);
        view.setTextSize(16);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(view);
    }

    private void addCardSubtitle(LinearLayout card, String subtitle) {
        TextView view = new TextView(this);
        view.setText(subtitle);
        view.setTextColor(0xFF9297A2);
        view.setTextSize(13);
        view.setPadding(0, dp(4), 0, dp(4));
        card.addView(view);
    }

    private void addToggleCard(
            LinearLayout page, String title, String subtitle,
            boolean checked, ToggleChange onChange) {
        LinearLayout card = createCard();
        CheckBox toggle = new CheckBox(this);
        toggle.setText(title);
        toggle.setTextColor(0xFFF3F4F6);
        toggle.setTextSize(16);
        toggle.setTypeface(null, android.graphics.Typeface.BOLD);
        toggle.setChecked(checked);
        card.addView(toggle);
        addCardSubtitle(card, subtitle);
        toggle.setOnCheckedChangeListener((button, value) -> onChange.onChange(value));
        page.addView(card, pageCardParams());
    }

    private RadioButton createRadio(String title, String subtitle) {
        RadioButton radio = new RadioButton(this);
        radio.setId(View.generateViewId());
        radio.setText(title + "\n" + subtitle);
        radio.setTextColor(0xFFF1F2F4);
        radio.setTextSize(14);
        radio.setPadding(0, dp(5), 0, dp(5));
        return radio;
    }

    private void addPageAction(
            LinearLayout page, String title, String subtitle, Runnable action) {
        LinearLayout card = createCard();
        addCardTitle(card, title + "  ›");
        addCardSubtitle(card, subtitle);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        page.addView(card, pageCardParams());
    }

    private void showSplitTunnelPage() {
        SecureStore store = new SecureStore(this);
        SplitTunnel.ensureDefaults(store);
        List<SplitTunnel.Profile> selectedProfiles = SplitTunnel.selected(store);
        SplitTunnel.Profile active = SplitTunnel.combined(store);
        LinearLayout page = createPageContent("Раздельное туннелирование",
                "Можно выбрать несколько наборов одного типа. Их адреса объединятся. "
                        + "Набор другого типа начинает новую комбинацию, потому что режимы "
                        + "«только через VPN» и «кроме VPN» нельзя смешивать.");

        addToggleCard(page, "Использовать раздельные маршруты",
                "Если выключено, весь трафик работает как раньше и идёт через VPN.",
                SplitTunnel.enabled(store), checked -> {
                    SplitTunnel.setEnabled(store, checked);
                    applySplitTunnelChanges();
                });

        if (active != null) {
            LinearLayout current = createCard();
            TextView name = new TextView(this);
            name.setText(active.name);
            name.setTextColor(0xFFFFAA5B);
            name.setTextSize(21);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            current.addView(name);
            addCardSubtitle(current,
                    (SplitTunnel.MODE_ONLY.equals(active.mode)
                            ? "Через VPN только адреса из списка"
                            : "Через VPN всё, кроме адресов из списка")
                            + " · наборов: " + selectedProfiles.size()
                            + " · " + active.entries.size() + " записей");
            boolean brawlSelected = false;
            for (SplitTunnel.Profile profile : selectedProfiles) {
                if (SplitTunnel.isBrawlTest(profile)) brawlSelected = true;
            }
            if (brawlSelected) {
                addCardSubtitle(current,
                        "Проверка: остальные сайты должны видеть VPN, а Brawl Stars — "
                                + "подключаться напрямую и не запускаться без VPN.");
            }
            page.addView(current, pageCardParams());
        }

        addSectionTitle(page, "Наборы адресов");
        for (SplitTunnel.Profile profile : SplitTunnel.list(store)) {
            boolean selected = false;
            for (SplitTunnel.Profile selectedProfile : selectedProfiles) {
                if (selectedProfile.id.equals(profile.id)) selected = true;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), 0, dp(10));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                boolean replacingMode = false;
                List<SplitTunnel.Profile> before = SplitTunnel.selected(store);
                if (!before.isEmpty() && !before.get(0).mode.equals(profile.mode)) {
                    replacingMode = true;
                }
                if (!SplitTunnel.toggleSelected(store, profile.id)) {
                    Toast.makeText(this, "Должен остаться хотя бы один набор",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                applySplitTunnelChanges();
                showSplitTunnelPage();
                if (replacingMode) {
                    Toast.makeText(this,
                            "Выбран другой тип — предыдущая комбинация заменена",
                            Toast.LENGTH_LONG).show();
                }
            });

            TextView marker = new TextView(this);
            marker.setText(selected ? "☑" : "☐");
            marker.setTextColor(selected ? 0xFFFFAA5B : 0xFF7D828D);
            marker.setTextSize(27);
            marker.setGravity(android.view.Gravity.CENTER);
            row.addView(marker, new LinearLayout.LayoutParams(dp(52), dp(58)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView title = new TextView(this);
            title.setText(profile.name);
            title.setTextColor(selected ? 0xFFFFAA5B : 0xFFF1F2F4);
            title.setTextSize(17);
            title.setSingleLine(true);
            labels.addView(title);
            TextView subtitle = new TextView(this);
            subtitle.setText((SplitTunnel.MODE_ONLY.equals(profile.mode)
                    ? "Только через VPN" : "Исключить из VPN")
                    + " · " + profile.entries.size() + " записей");
            subtitle.setTextColor(0xFF9297A2);
            subtitle.setTextSize(13);
            labels.addView(subtitle);
            row.addView(labels);

            TextView edit = new TextView(this);
            edit.setText("⚙");
            edit.setTextColor(0xFFF1F2F4);
            edit.setTextSize(23);
            edit.setGravity(android.view.Gravity.CENTER);
            edit.setClickable(true);
            edit.setFocusable(true);
            edit.setOnClickListener(v -> showSplitProfileEditor(profile));
            row.addView(edit, new LinearLayout.LayoutParams(dp(60), dp(58)));
            page.addView(row);

            View divider = new View(this);
            divider.setBackgroundColor(0xFF2A2C31);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.leftMargin = dp(52);
            page.addView(divider, dividerParams);
        }

        addPageAction(page, "Создать новый набор",
                "Свой режим и список доменов, IP или CIDR",
                () -> showSplitProfileEditor(null));
        addSectionTitle(page, "Перенос списков");
        addPageAction(page, "Импортировать набор",
                "Файл Pelmeni Split Tunnel в формате JSON", this::beginSplitImport);
        addPageAction(page, "Экспортировать активный набор",
                "Сохранить список и режим в отдельный файл", this::beginSplitExport);

        TextView note = new TextView(this);
        note.setText("Важно: набор «Искл. российские сервисы» — стартовый и редактируемый. "
                + "Сервисы используют CDN и меняют адреса, поэтому он не может быть "
                + "абсолютно полным. Переподключение обновляет IP доменов.");
        note.setTextColor(0xFF7F8490);
        note.setTextSize(12);
        note.setPadding(0, dp(18), 0, 0);
        page.addView(note);
        showScrollablePage(page, navHome);
    }

    private void showSplitProfileEditor(SplitTunnel.Profile profile) {
        SecureStore store = new SecureStore(this);
        LinearLayout page = createPageContent(
                profile == null ? "Новый набор" : profile.name,
                "Одна строка — домен, IPv4 или сеть CIDR. Примеры: youtube.com, "
                        + "142.250.0.0/16, 1.1.1.1.");
        addSectionTitle(page, "Название");
        EditText name = addServerField(page, "Название набора",
                profile == null ? "" : profile.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        addSectionTitle(page, "Режим");
        LinearLayout modeCard = createCard();
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        RadioButton bypass = createRadio("Не направлять список через VPN",
                "Остальной интернет идёт через VPN");
        RadioButton only = createRadio("Направлять через VPN только список",
                "Остальной интернет идёт напрямую");
        modes.addView(bypass);
        modes.addView(only);
        if (profile != null && SplitTunnel.MODE_ONLY.equals(profile.mode)) {
            only.setChecked(true);
        } else {
            bypass.setChecked(true);
        }
        modeCard.addView(modes);
        page.addView(modeCard, pageCardParams());

        addSectionTitle(page, "Домены и IP");
        LinearLayout entriesCard = createCard();
        EditText entries = new EditText(this);
        entries.setHint("example.com\n1.1.1.1\n10.0.0.0/8");
        entries.setHintTextColor(0xFF676C76);
        entries.setTextColor(0xFFF3F4F6);
        entries.setTextSize(15);
        entries.setGravity(android.view.Gravity.TOP);
        entries.setMinLines(10);
        entries.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        entries.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (profile != null) {
            StringBuilder text = new StringBuilder();
            for (String entry : profile.entries) {
                if (text.length() > 0) text.append('\n');
                text.append(entry);
            }
            entries.setText(text.toString());
        }
        entriesCard.addView(entries);
        page.addView(entriesCard, pageCardParams());

        Button saveProfile = new Button(this);
        saveProfile.setText("СОХРАНИТЬ НАБОР");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        saveParams.topMargin = dp(22);
        page.addView(saveProfile, saveParams);
        saveProfile.setOnClickListener(v -> {
            List<String> values = SplitTunnel.parseLines(entries.getText().toString());
            if (values.isEmpty()) {
                Toast.makeText(this, "Добавь хотя бы один домен или IP",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String selectedMode = modes.getCheckedRadioButtonId() == only.getId()
                    ? SplitTunnel.MODE_ONLY : SplitTunnel.MODE_BYPASS;
            SplitTunnel.Profile updated = profile == null
                    ? SplitTunnel.create(name.getText().toString(), selectedMode, values)
                    : new SplitTunnel.Profile(profile.id, name.getText().toString().trim(),
                    selectedMode, values, profile.builtIn);
            SplitTunnel.saveAndActivate(store, updated);
            applySplitTunnelChanges();
            showSplitTunnelPage();
            Toast.makeText(this, "Набор сохранён", Toast.LENGTH_SHORT).show();
        });

        if (profile != null && SplitTunnel.list(store).size() > 1) {
            Button delete = new Button(this);
            delete.setText("УДАЛИТЬ НАБОР");
            delete.setTextColor(0xFFFF7272);
            page.addView(delete);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Удалить набор «" + profile.name + "»?")
                    .setPositiveButton("Удалить", (ignored, which) -> {
                        SplitTunnel.delete(store, profile.id);
                        applySplitTunnelChanges();
                        showSplitTunnelPage();
                    })
                    .setNegativeButton("Отмена", null)
                    .show());
        }
        showScrollablePage(page, navHome);
    }

    private void updateSplitSummary() {
        SecureStore store = new SecureStore(this);
        SplitTunnel.Profile active = SplitTunnel.combined(store);
        if (!SplitTunnel.enabled(store)) {
            splitTunnelSummary.setText("Выключено · весь трафик через VPN");
            return;
        }
        if (active == null) {
            splitTunnelSummary.setText("Включено · набор не выбран");
            return;
        }
        splitTunnelSummary.setText(
                (SplitTunnel.MODE_ONLY.equals(active.mode)
                        ? "Только через VPN: " : "Не через VPN: ")
                        + active.name + " · наборов: "
                        + SplitTunnel.selected(store).size()
                        + " · " + active.entries.size());
    }

    private void applySplitTunnelChanges() {
        updateSplitSummary();
        SecureStore store = new SecureStore(this);
        if (!store.getBoolean("enabled", false)
                || !store.getBoolean("vpn_mode", false)) {
            Toast.makeText(this, "Настройка сохранена", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent reload = VpnTunnelService.includeRoutingSnapshot(
                new Intent(this, VpnTunnelService.class)
                        .setAction(VpnTunnelService.RELOAD_ROUTES), store);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(reload);
        else startService(reload);
        routingApplying = true;
        updateModeButtons();
        Toast.makeText(this, "Применяем маршруты…",
                Toast.LENGTH_SHORT).show();
    }

    private void beginSplitExport() {
        SplitTunnel.Profile profile = SplitTunnel.combined(new SecureStore(this));
        if (profile == null) {
            Toast.makeText(this, "Сначала создай набор", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, profile.name + ".pelmeni-split.json");
        startActivityForResult(intent, REQUEST_SPLIT_EXPORT);
    }

    private void beginSplitImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, REQUEST_SPLIT_IMPORT);
    }

    private void writeSplitConfig(Uri uri) {
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            SplitTunnel.Profile profile = SplitTunnel.combined(new SecureStore(this));
            if (output == null || profile == null) throw new Exception("No active profile");
            output.write(SplitTunnel.exportJson(profile).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Набор экспортирован", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось экспортировать набор",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void readSplitConfig(Uri uri) {
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("No input stream");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 128 * 1024) throw new Exception("Too large");
                bytes.write(buffer, 0, count);
            }
            String text = bytes.toString(StandardCharsets.UTF_8.name()).trim();
            SplitTunnel.Profile profile;
            if (text.startsWith("{")) {
                profile = SplitTunnel.importJson(text);
            } else {
                List<String> entries = SplitTunnel.parseLines(text);
                if (entries.isEmpty()) throw new Exception("Empty list");
                profile = SplitTunnel.create(
                        "Импортированный список", SplitTunnel.MODE_BYPASS, entries);
            }
            SplitTunnel.saveAndActivate(new SecureStore(this), profile);
            applySplitTunnelChanges();
            showSplitTunnelPage();
            Toast.makeText(this, "Набор импортирован", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Файл набора повреждён или несовместим",
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private void maybeCheckForUpdate(boolean manual) {
        boolean includePrereleases = Branding.isSecret(this)
                && new SecureStore(this).getBoolean("beta_updates", false);
        maybeCheckForUpdate(manual, includePrereleases);
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
        ringTelegram.setConnecting(false);
        ringVpn.setConnecting(false);
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
        suppressModeChanges = true;
        try {
            enableVpn.setChecked(vpnEnabled);
            enableTelegram.setChecked(store.getBoolean("telegram_proxy", !vpnEnabled));
        } finally {
            suppressModeChanges = false;
        }
        updateSplitSummary();
        updateServerCard();
        updateModeButtons();
        updateAccessLimitSummary();
    }

    private void toggleModeFromButton(boolean vpn) {
        if (!running) {
            suppressModeChanges = true;
            try {
                enableVpn.setChecked(vpn);
                enableTelegram.setChecked(!vpn);
            } finally {
                suppressModeChanges = false;
            }
            SecureStore store = new SecureStore(this);
            store.putBoolean("vpn_mode", vpn);
            store.putBoolean("telegram_proxy", !vpn);
            updateModeButtons();
            startTunnel();
            return;
        }
        CheckBox target = vpn ? enableVpn : enableTelegram;
        target.setChecked(!target.isChecked());
    }

    private void updateModeButtons() {
        boolean proxyActive = running && enableTelegram.isChecked();
        boolean vpnActive = running && enableVpn.isChecked();
        toggleTelegram.setText(proxyConnecting
                ? "ПРОКСИ\nПОДКЛЮЧЕНИЕ"
                : proxyActive ? "ПРОКСИ\nВКЛЮЧЕН" : "ПРОКСИ\nВКЛЮЧИТЬ");
        toggleVpn.setText(vpnConnecting
                ? "VPN\nПОДКЛЮЧЕНИЕ"
                : vpnActive ? "VPN\nВКЛЮЧЕН" : "VPN\nВКЛЮЧИТЬ");
        toggleTelegram.setTextColor(proxyActive ? 0xFFFFAA5B : 0xFFB8BBC3);
        toggleVpn.setTextColor(vpnActive ? 0xFFFFAA5B : 0xFFB8BBC3);
        toggleTelegram.setActivated(proxyActive && !proxyConnecting);
        toggleVpn.setActivated(vpnActive && !vpnConnecting);
        ringTelegram.setConnecting(proxyConnecting);
        ringVpn.setConnecting(vpnConnecting);
        splitRouteProgress.setVisibility(routingApplying
                ? View.VISIBLE : View.GONE);
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
            userLimitSummary.setVisibility(View.GONE);
            return;
        }
        serverName.setText(active.name);
        serverAddress.setText(active.host + ":" + active.sshPort
                + " · " + active.user);
        serverSelect.setText("СМЕНИТЬ");
        serverEdit.setText("ПАРАМЕТРЫ");
        updateAccessLimitSummary();
    }

    private void updateAccessLimitSummary() {
        SecureStore store = new SecureStore(this);
        ServerProfiles.Profile profile = ServerProfiles.active(store);
        if (profile == null) {
            userLimitSummary.setVisibility(View.GONE);
            return;
        }
        UserAccessPolicy.Policy policy = UserAccessPolicy.load(store, profile.id);
        if (!policy.configured) {
            userLimitSummary.setVisibility(View.GONE);
            return;
        }
        UserAccessPolicy.Usage usage = UserAccessPolicy.usage(store, profile.id);
        String expiry = policy.expires.isEmpty() ? "бессрочно" : "до " + policy.expires;
        String daily = policy.dailyMb > 0
                ? formatBytes(usage.dayBytes) + " из " + policy.dailyMb + " МБ сегодня"
                : "без дневного лимита";
        String monthly = policy.monthlyMb > 0
                ? formatBytes(usage.monthBytes) + " из " + policy.monthlyMb + " МБ за месяц"
                : "без месячного лимита";
        String speed = policy.speedMbps > 0
                ? policy.speedMbps + " Мбит/с" : "без ограничения скорости";
        String warning = UserAccessPolicy.warning(policy, usage);
        boolean notificationsBlocked = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
        userLimitSummary.setText("Ваш доступ · " + expiry + "\n"
                + daily + " · " + monthly + "\nСкорость: " + speed
                + (warning.isEmpty() ? "" : "\n⚠ " + warning)
                + (notificationsBlocked
                ? "\n⚠ Уведомления запрещены Android — нажми сюда, чтобы включить." : ""));
        userLimitSummary.setOnClickListener(notificationsBlocked ? v -> {
            Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(settings);
        } : null);
        double ratio = Math.max(
                policy.dailyMb > 0
                        ? usage.dayBytes / (policy.dailyMb * 1024.0 * 1024.0) : 0,
                policy.monthlyMb > 0
                        ? usage.monthBytes / (policy.monthlyMb * 1024.0 * 1024.0) : 0);
        userLimitSummary.setTextColor(ratio >= 1.0 ? 0xFFFF7272
                : ratio >= 0.8 ? 0xFFFFAA5B : 0xFFD7DAE0);
        userLimitSummary.setVisibility(View.VISIBLE);
    }

    private void showServerList() {
        SecureStore store = new SecureStore(this);
        List<ServerProfiles.Profile> profiles = ServerProfiles.list(store);
        if (profiles.isEmpty()) {
            showAddServerChoice();
            return;
        }
        ServerProfiles.Profile active = ServerProfiles.active(store);
        LinearLayout page = createPageContent("Серверы",
                running
                        ? "При выборе другого сервера туннель переподключится автоматически."
                        : "Нажми на строку для выбора, на шестерёнку — для параметров.");
        if (active != null) {
            TextView currentName = new TextView(this);
            currentName.setText(active.name);
            currentName.setTextColor(0xFFF3F4F6);
            currentName.setTextSize(27);
            currentName.setGravity(android.view.Gravity.CENTER);
            currentName.setTypeface(null, android.graphics.Typeface.BOLD);
            currentName.setPadding(0, dp(20), 0, dp(5));
            page.addView(currentName);

            TextView currentAddress = new TextView(this);
            currentAddress.setText(active.host + ":" + active.sshPort);
            currentAddress.setTextColor(0xFF9297A2);
            currentAddress.setTextSize(14);
            currentAddress.setGravity(android.view.Gravity.CENTER);
            currentAddress.setPadding(0, 0, 0, dp(18));
            page.addView(currentAddress);
        }

        TextView heading = new TextView(this);
        heading.setText("Серверы");
        heading.setTextColor(0xFFF3F4F6);
        heading.setTextSize(22);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setPadding(0, dp(18), 0, dp(8));
        page.addView(heading);

        for (ServerProfiles.Profile profile : profiles) {
            boolean selected = active != null && active.id.equals(profile.id);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(12), 0, dp(12));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                switchToServer(profile);
            });

            TextView marker = new TextView(this);
            marker.setText(selected ? "●" : "○");
            marker.setTextColor(selected ? 0xFFFFAA5B : 0xFF7D828D);
            marker.setTextSize(27);
            marker.setGravity(android.view.Gravity.CENTER);
            marker.setContentDescription(selected ? "Выбран" : "Не выбран");
            marker.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(54),
                    LinearLayout.LayoutParams.MATCH_PARENT));
            row.addView(marker);

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(this);
            name.setText(profile.name);
            name.setTextColor(selected ? 0xFFFFAA5B : 0xFFF1F2F4);
            name.setTextSize(18);
            name.setSingleLine(true);
            labels.addView(name);

            TextView address = new TextView(this);
            address.setText(profile.host + ":" + profile.sshPort);
            address.setTextColor(0xFF9297A2);
            address.setTextSize(13);
            address.setSingleLine(true);
            address.setPadding(0, 3, 0, 0);
            labels.addView(address);
            row.addView(labels);

            TextView edit = new TextView(this);
            edit.setText("⚙");
            edit.setTextSize(23);
            edit.setTextColor(0xFFF1F2F4);
            edit.setGravity(android.view.Gravity.CENTER);
            edit.setClickable(true);
            edit.setFocusable(true);
            edit.setEnabled(!running);
            edit.setContentDescription("Параметры сервера " + profile.name);
            edit.setLayoutParams(new LinearLayout.LayoutParams(
                    dp(62), dp(56)));
            edit.setOnClickListener(v -> showServerEditor(profile));
            row.addView(edit);
            page.addView(row);

            View divider = new View(this);
            divider.setBackgroundColor(0xFF2A2C31);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(1)));
            dividerParams.leftMargin = dp(54);
            page.addView(divider, dividerParams);
        }

        Button add = new Button(this);
        add.setText("＋  ДОБАВИТЬ СЕРВЕР");
        add.setTextSize(15);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.topMargin = dp(22);
        add.setOnClickListener(v -> showAddServerChoice());
        page.addView(add, addParams);
        showScrollablePage(page, navHome);
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
            showServerList();
            if (reconnect) toggle.postDelayed(this::startTunnel, 900);
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось переключить сервер",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showServerEditor(ServerProfiles.Profile profile) {
        if (running && profile != null) {
            Toast.makeText(this, "Сначала отключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        SecureStore store = new SecureStore(this);
        boolean sharedAccess = profile != null && profile.user.startsWith("pel_");
        LinearLayout page = createPageContent(
                profile == null ? "Новый сервер" : profile.name,
                profile == null
                        ? "Добавь данные SSH-сервера. Все параметры можно изменить позже."
                        : sharedAccess
                        ? "Сервер добавлен по коду. Название и параметры на этом телефоне можно менять независимо от владельца."
                        : "Настройки этого сервера. Они не влияют на остальные профили.");

        addSectionTitle(page, "Основное");
        EditText profileName = addServerField(page, "Название на этом телефоне",
                profile == null ? "" : profile.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText profileHost = addServerField(page, "IP-адрес или домен",
                profile == null ? "" : profile.host,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText profileUser = addServerField(page, "Пользователь SSH",
                profile == null ? "root" : profile.user,
                InputType.TYPE_CLASS_TEXT);
        EditText profilePassword = addServerField(page, "Пароль SSH",
                profile == null ? "" : ServerProfiles.password(store, profile.id),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (sharedAccess) {
            ((View) profileHost.getParent()).setVisibility(View.GONE);
            ((View) profileUser.getParent()).setVisibility(View.GONE);
            ((View) profilePassword.getParent()).setVisibility(View.GONE);
            LinearLayout shared = createCard();
            addCardTitle(shared, "Личный профиль");
            addCardSubtitle(shared,
                    "Название меняется только у тебя. Адрес, логин и пароль управляются владельцем сервера.");
            UserAccessPolicy.Policy access = UserAccessPolicy.load(store, profile.id);
            if (access.configured) {
                addCardSubtitle(shared,
                        "Срок: " + (access.expires.isEmpty() ? "бессрочно" : "до " + access.expires)
                                + "\nДень: " + (access.dailyMb > 0
                                ? access.dailyMb + " МБ" : "без лимита")
                                + " · месяц: " + (access.monthlyMb > 0
                                ? access.monthlyMb + " МБ" : "без лимита")
                                + "\nСкорость: " + (access.speedMbps > 0
                                ? access.speedMbps + " Мбит/с" : "без ограничения"));
            }
            page.addView(shared, pageCardParams());
        }

        addSectionTitle(page, "Порты");
        EditText profileSshPort = addServerField(page, "SSH-порт",
                profile == null ? "22" : profile.sshPort,
                InputType.TYPE_CLASS_NUMBER);
        if (sharedAccess) {
            ((View) profileSshPort.getParent()).setVisibility(View.GONE);
        } else {
            addQuickChoices(page, profileSshPort, "22", "443", "2222");
        }
        EditText profileSocksPort = addServerField(page, "Локальный SOCKS5-порт",
                profile == null ? "1080" : profile.socksPort,
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, profileSocksPort, "1080", "1081", "2080");

        addSectionTitle(page, "Производительность");
        LinearLayout presetCard = createCard();
        addCardTitle(presetCard, "Профиль соединения");
        addCardSubtitle(presetCard,
                "Выбери готовый вариант. Точные значения ниже нужны только для диагностики.");
        RadioGroup presets = new RadioGroup(this);
        presets.setOrientation(RadioGroup.VERTICAL);
        RadioButton compatibility = createRadio("Совместимость",
                "MTU 1500 — если сайты или тесты зависают");
        RadioButton balanced = createRadio("Баланс · рекомендуется",
                "Оптимальные скорость, пинг и расход памяти");
        RadioButton speed = createRadio("Скорость · эксперимент",
                "Больше буферы для быстрого сервера и высокого пинга");
        RadioButton custom = createRadio("Свои значения",
                "Использовать точные параметры ниже");
        presets.addView(compatibility);
        presets.addView(balanced);
        presets.addView(speed);
        presets.addView(custom);
        presetCard.addView(presets);
        page.addView(presetCard, pageCardParams());

        EditText profileWindow = addServerTuningField(page,
                "Окно SSH · КиБ", "Буфер передачи. Рекомендуется 1024.",
                profile == null ? NetworkTuning.DEFAULT_WINDOW_KIB : profile.windowKiB);
        EditText profilePacket = addServerTuningField(page,
                "Пакет SSH · КиБ", "Размер блока. Рекомендуется 32.",
                profile == null ? NetworkTuning.DEFAULT_PACKET_KIB : profile.packetKiB);
        EditText profileMtu = addServerTuningField(page,
                "MTU VPN", "8500 быстрее; 1500 полезно при зависаниях.",
                profile == null ? NetworkTuning.DEFAULT_MTU : profile.mtu);
        int windowValue = parseInt(profileWindow);
        int packetValue = parseInt(profilePacket);
        int mtuValue = parseInt(profileMtu);
        if (windowValue == 512 && packetValue == 32 && mtuValue == 1500) {
            compatibility.setChecked(true);
        } else if (windowValue == NetworkTuning.DEFAULT_WINDOW_KIB
                && packetValue == NetworkTuning.DEFAULT_PACKET_KIB
                && mtuValue == NetworkTuning.DEFAULT_MTU) {
            balanced.setChecked(true);
        } else if (windowValue == 4096 && packetValue == 64 && mtuValue == 8500) {
            speed.setChecked(true);
        } else {
            custom.setChecked(true);
        }
        presets.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == compatibility.getId()) {
                profileWindow.setText("512");
                profilePacket.setText("32");
                profileMtu.setText("1500");
            } else if (checkedId == balanced.getId()) {
                profileWindow.setText(Integer.toString(NetworkTuning.DEFAULT_WINDOW_KIB));
                profilePacket.setText(Integer.toString(NetworkTuning.DEFAULT_PACKET_KIB));
                profileMtu.setText(Integer.toString(NetworkTuning.DEFAULT_MTU));
            } else if (checkedId == speed.getId()) {
                profileWindow.setText("4096");
                profilePacket.setText("64");
                profileMtu.setText("8500");
            }
        });

        if (profile != null && !sharedAccess) {
            addSectionTitle(page, "Защита сервера");
            ServerProfiles.Profile active = ServerProfiles.active(store);
            boolean isActive = active != null && active.id.equals(profile.id);
            addPageAction(page, "TLS-обёртка",
                    isActive
                            ? "Настроить, включить или полностью удалить TLS на этом сервере"
                            : "Сначала выбери этот сервер активным",
                    () -> {
                        if (isActive) showTlsProtection();
                        else Toast.makeText(this, "Сначала выбери этот сервер",
                                Toast.LENGTH_SHORT).show();
                    });
            addPageAction(page, "Перенести на новый сервер",
                    isActive
                            ? "Скопировать пользователей и настройки, затем заменить адрес этого профиля"
                            : "Сначала выбери этот сервер активным",
                    () -> {
                        if (isActive) showServerMigration(profile);
                        else Toast.makeText(this, "Сначала выбери этот сервер",
                                Toast.LENGTH_SHORT).show();
                    });
        }

        Button saveProfile = new Button(this);
        saveProfile.setText(profile == null ? "ДОБАВИТЬ СЕРВЕР"
                : sharedAccess ? "СОХРАНИТЬ У СЕБЯ" : "СОХРАНИТЬ");
        saveProfile.setTextSize(16);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        saveParams.topMargin = dp(24);
        page.addView(saveProfile, saveParams);
        saveProfile.setOnClickListener(v -> {
            String h = profileHost.getText().toString().trim();
            String ssh = profileSshPort.getText().toString().trim();
            String socks = profileSocksPort.getText().toString().trim();
            String u = profileUser.getText().toString().trim();
            String pw = profilePassword.getText().toString();
            int window = parseInt(profileWindow);
            int packet = parseInt(profilePacket);
            int mtu = parseInt(profileMtu);
            if (Branding.isSecretInput(h, pw)) {
                boolean enabled = Branding.toggleSecret(this);
                appTitle.setText(Branding.appName(this));
                updateDebugPanel();
                showHomePage();
                Toast.makeText(this, enabled
                                ? "Huyna VPN debug mode activated"
                                : "Возвращено название «Пельмени VPN»",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (!validHost(h) || !validPort(ssh) || !validPort(socks)
                    || u.isEmpty() || pw.isEmpty()
                    || !NetworkTuning.valid(window, NetworkTuning.MIN_WINDOW_KIB,
                    NetworkTuning.MAX_WINDOW_KIB)
                    || !NetworkTuning.valid(packet, NetworkTuning.MIN_PACKET_KIB,
                    NetworkTuning.MAX_PACKET_KIB)
                    || !NetworkTuning.valid(mtu, NetworkTuning.MIN_MTU,
                    NetworkTuning.MAX_MTU)) {
                Toast.makeText(this, "Проверь адрес, порты, пароль и точные значения",
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
            boolean reconnect = running;
            if (reconnect) stopTunnel();
            try {
                ServerProfiles.saveAndActivate(store, updated, pw);
                loadSettings();
                Toast.makeText(this, "Сервер сохранён", Toast.LENGTH_SHORT).show();
                showServerList();
                if (reconnect) {
                    toggle.postDelayed(this::startTunnel, 900);
                } else if (!sharedAccess) {
                    maybeOfferTlsForCurrentServer(null);
                }
            } catch (Exception error) {
                Toast.makeText(this, "Не удалось сохранить сервер",
                        Toast.LENGTH_LONG).show();
                if (reconnect) toggle.postDelayed(this::startTunnel, 900);
            }
        });

        if (profile != null && ServerProfiles.list(store).size() > 1) {
            Button delete = new Button(this);
            delete.setText("УДАЛИТЬ СЕРВЕР");
            delete.setTextColor(0xFFFF7272);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            deleteParams.topMargin = dp(8);
            page.addView(delete, deleteParams);
            delete.setOnClickListener(v -> confirmDeleteServerPage(profile));
        }
        showScrollablePage(page, navAdd);
    }

    private void showServerMigration(ServerProfiles.Profile profile) {
        if (running) {
            Toast.makeText(this, "Сначала отключи туннель", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout page = createPageContent("Перенос сервера",
                "Старый сервер должен быть доступен хотя бы на время переноса. "
                        + "Пельмени скопируют управляемых пользователей, сроки и лимиты, "
                        + "а TLS-ключи безопасно создадут заново.");
        EditText newName = addServerField(page, "Новое название", profile.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText newHost = addServerField(page, "Новый IP или домен", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText newUser = addServerField(page, "Администратор SSH", profile.user,
                InputType.TYPE_CLASS_TEXT);
        EditText newPassword = addServerField(page, "Пароль администратора", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText newPort = addServerField(page, "SSH-порт", profile.sshPort,
                InputType.TYPE_CLASS_NUMBER);
        addQuickChoices(page, newPort, "22", "443", "2222");

        LinearLayout warning = createCard();
        addCardTitle(warning, "Что не переносится");
        addCardSubtitle(warning,
                "Чужие сайты, базы данных, firewall и прочие службы Linux не клонируются. "
                        + "Переносятся только данные Пельмени VPN. Старый сервер автоматически не удаляется.");
        page.addView(warning, pageCardParams());

        Button migrate = new Button(this);
        migrate.setText("ПРОВЕРИТЬ И ПЕРЕНЕСТИ");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        params.topMargin = dp(22);
        page.addView(migrate, params);
        migrate.setOnClickListener(v -> {
            String h = newHost.getText().toString().trim();
            String u = newUser.getText().toString().trim();
            String pw = newPassword.getText().toString();
            String portText = newPort.getText().toString().trim();
            if (!validHost(h) || !validPort(portText) || u.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this, "Проверь адрес, порт, пользователя и пароль",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String name = newName.getText().toString().trim();
            if (name.isEmpty()) name = profile.name;
            int portValue = Integer.parseInt(portText);
            String finalName = name;
            boolean hadTls = TlsTransport.isConfigured(new SecureStore(this));
            migrate.setEnabled(false);
            migrate.setText("ПЕРЕНОШУ…");
            speedWorker.execute(() -> {
                try {
                    SecureStore store = new SecureStore(this);
                    org.json.JSONArray users = ServerAccessManager.exportUsers(store);
                    ServerAccessManager.Credentials destination =
                            new ServerAccessManager.Credentials(h, portValue, u, pw);
                    ServerAccessManager.importUsers(destination, users,
                            finalName, profile.socksPort, profile.windowKiB,
                            profile.packetKiB, profile.mtu);
                    ServerProfiles.Profile moved = new ServerProfiles.Profile(
                            profile.id, finalName, h, portText, u, profile.socksPort,
                            profile.windowKiB, profile.packetKiB, profile.mtu);
                    ServerProfiles.saveAndActivate(store, moved, pw);
                    TlsTransport.clear(store);
                    String tlsNote = "";
                    if (hadTls) {
                        try {
                            ServerTlsSetup.Result tls = ServerTlsSetup.install(store);
                            TlsTransport.save(store, h, tls.port, tls.pkcs12, tls.password);
                            TlsTransport.snapshotForProfile(store, moved.id);
                            tlsNote = " TLS на новом сервере создан заново.";
                        } catch (Exception tlsError) {
                            tlsNote = " Перенос завершён, но TLS не настроился: "
                                    + tlsError.getMessage();
                        }
                    }
                    String finalTlsNote = tlsNote;
                    mainHandler.post(() -> {
                        loadSettings();
                        Toast.makeText(this,
                                "Сервер перенесён. Пользователей: " + users.length()
                                        + "." + finalTlsNote,
                                Toast.LENGTH_LONG).show();
                        showHomePage();
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        migrate.setEnabled(true);
                        migrate.setText("ПРОВЕРИТЬ И ПЕРЕНЕСТИ");
                        Toast.makeText(this,
                                "Перенос не выполнен: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        showScrollablePage(page, navAdd);
    }

    private void addQuickChoices(LinearLayout page, EditText field, String... values) {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        for (String value : values) {
            Button button = new Button(this);
            button.setText(value);
            button.setTextSize(13);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, dp(46), 1);
            if (choices.getChildCount() > 0) params.leftMargin = dp(6);
            choices.addView(button, params);
            button.setOnClickListener(v -> field.setText(value));
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        page.addView(choices, rowParams);
    }

    private void confirmDeleteServerPage(ServerProfiles.Profile profile) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить сервер «" + profile.name + "»?")
                .setMessage("Будут удалены пароль, TLS-ключи и параметры этого профиля. "
                        + "Сам удалённый сервер изменён не будет.")
                .setPositiveButton("Удалить", (ignored, which) -> {
                    try {
                        if (ServerProfiles.delete(new SecureStore(this), profile.id)) {
                            loadSettings();
                            showServerList();
                            Toast.makeText(this, "Сервер удалён",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception error) {
                        Toast.makeText(this, "Не удалось удалить сервер",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private EditText addServerField(
            LinearLayout parent, String label, String value, int inputType) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Math.round(16 * getResources().getDisplayMetrics().density);
        int vertical = Math.round(12 * getResources().getDisplayMetrics().density);
        card.setPadding(horizontal, vertical, horizontal, vertical);
        card.setBackgroundResource(R.drawable.settings_field_background);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFF9297A2);
        labelView.setTextSize(13);
        card.addView(labelView);

        EditText field = new EditText(this);
        field.setText(value);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setTextColor(0xFFF3F4F6);
        field.setTextSize(18);
        field.setPadding(0, 5, 0, 0);
        field.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        card.addView(field);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        parent.addView(card, params);
        return field;
    }

    private EditText addServerTuningField(
            LinearLayout parent, String label, String explanation, int value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Math.round(16 * getResources().getDisplayMetrics().density);
        int vertical = Math.round(12 * getResources().getDisplayMetrics().density);
        card.setPadding(horizontal, vertical, horizontal, vertical);
        card.setBackgroundResource(R.drawable.settings_field_background);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(0xFF9297A2);
        labelView.setTextSize(13);
        card.addView(labelView);

        EditText field = new EditText(this);
        field.setText(Integer.toString(value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSelectAllOnFocus(true);
        field.setTextColor(0xFFF3F4F6);
        field.setTextSize(18);
        field.setPadding(0, 5, 0, 0);
        field.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        card.addView(field);

        TextView explanationView = new TextView(this);
        explanationView.setText(explanation);
        explanationView.setTextColor(0xFF747985);
        explanationView.setTextSize(12);
        explanationView.setPadding(0, 3, 0, 0);
        card.addView(explanationView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(10 * getResources().getDisplayMetrics().density);
        parent.addView(card, params);
        return field;
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
        updateAccessLimitSummary();
        if (Branding.isSecret(this) && intent.getBooleanExtra("debug_enabled", false)) {
            debugInfo.setText("Версия: " + intent.getStringExtra("debug_version")
                    + "\nПрофиль: " + intent.getStringExtra("debug_profile")
                    + "\nСтатус: " + intent.getStringExtra("debug_status")
                    + "\nSSH: "
                    + (intent.getBooleanExtra("debug_ssh_connected", false)
                    ? "соединение установлено" : "нет соединения")
                    + " · сеансов: " + intent.getIntExtra("debug_ssh_sessions", 1)
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
            showAddServerChoice();
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
        proxyConnecting = true;
        vpnConnecting = false;
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
        IntentResult qrResult =
                IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (qrResult != null) {
            if (qrResult.getContents() != null) {
                importAccessCode(qrResult.getContents());
            }
            return;
        }
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            if (pendingLiveVpnPermission && running) {
                pendingLiveVpnPermission = false;
                reconfigureRunningModes();
            } else {
                pendingLiveVpnPermission = false;
                startVpnTunnel();
            }
        } else if (requestCode == REQUEST_VPN) {
            pendingLiveVpnPermission = false;
            waitingForVpnReady = false;
            suppressModeChanges = true;
            enableVpn.setChecked(false);
            suppressModeChanges = false;
            new SecureStore(this).putBoolean("vpn_mode", false);
            updateModeButtons();
        } else if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && data != null) {
            writeConfig(data.getData());
        } else if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null) {
            readConfig(data.getData());
        } else if (requestCode == REQUEST_SPLIT_EXPORT
                && resultCode == RESULT_OK && data != null) {
            writeSplitConfig(data.getData());
        } else if (requestCode == REQUEST_SPLIT_IMPORT
                && resultCode == RESULT_OK && data != null) {
            readSplitConfig(data.getData());
        }
    }

    private void startVpnTunnel() {
        waitingForVpnReady = true;
        proxyConnecting = enableTelegram.isChecked();
        vpnConnecting = true;
        Intent sshIntent = new Intent(this, TunnelService.class).setAction(TunnelService.START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(sshIntent);
        else startService(sshIntent);
        Intent vpnIntent = VpnTunnelService.includeRoutingSnapshot(
                new Intent(this, VpnTunnelService.class)
                        .setAction(VpnTunnelService.START), new SecureStore(this));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpnIntent);
        else startService(vpnIntent);
        update("Starting VPN...");
    }

    private void applyLiveModeChange(boolean vpnChanged, boolean checked) {
        if (!enableVpn.isChecked() && !enableTelegram.isChecked()) {
            proxyConnecting = false;
            vpnConnecting = false;
            if (running) stopTunnel();
            else update("Отключено");
            return;
        }
        if (!running) {
            if (checked) startTunnel();
            return;
        }
        if (vpnChanged) vpnConnecting = checked;
        else proxyConnecting = checked;
        if (vpnChanged && checked) {
            waitingForVpnReady = true;
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                pendingLiveVpnPermission = true;
                startActivityForResult(permission, REQUEST_VPN);
                return;
            }
        }
        reconfigureRunningModes();
    }

    private void reconfigureRunningModes() {
        SecureStore store = new SecureStore(this);
        if (!store.getBoolean("vpn_mode", false)) {
            startService(new Intent(this, VpnTunnelService.class)
                    .setAction(VpnTunnelService.STOP)
                    .putExtra(VpnTunnelService.EXTRA_STOP_SSH, false));
        }
        Intent ssh = new Intent(this, TunnelService.class)
                .setAction(TunnelService.RECONFIGURE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(ssh);
        else startService(ssh);
        if (store.getBoolean("vpn_mode", false)) {
            Intent vpn = VpnTunnelService.includeRoutingSnapshot(
                    new Intent(this, VpnTunnelService.class)
                            .setAction(VpnTunnelService.START), store);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(vpn);
            else startService(vpn);
        }
        update("Переключаемся на " + TunnelMode.label(store) + "…");
    }

    private void stopTunnel() {
        startService(new Intent(this, TunnelService.class).setAction(TunnelService.STOP));
        update("Отключено");
    }

    private void update(String text) {
        if (text == null) return;
        status.setText(text);
        running = !text.equals("Отключено");
        updateConnectionPhase(text);
        toggle.setText(running ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ");
        toggle.setActivated(running);
        updateModeButtons();
        setSettingsEnabled(!running);
        updateDebugPanel();
    }

    private void updateConnectionPhase(String text) {
        if (text.equals("Отключено")) {
            waitingForVpnReady = false;
            proxyConnecting = false;
            vpnConnecting = false;
            routingApplying = false;
            return;
        }
        if (text.startsWith("VPN подключён")) {
            waitingForVpnReady = false;
            vpnConnecting = false;
            routingApplying = false;
            return;
        }
        if (text.startsWith("Ошибка запуска VPN")
                || text.startsWith("Android не разрешил")
                || text.startsWith("SOCKS5 не запустился")) {
            waitingForVpnReady = false;
            vpnConnecting = false;
            routingApplying = false;
            return;
        }
        if (!enableVpn.isChecked()) {
            waitingForVpnReady = false;
            vpnConnecting = false;
        }
        if (text.startsWith("Подключено")) {
            proxyConnecting = false;
            routingApplying = waitingForVpnReady
                    && SplitTunnel.enabled(new SecureStore(this));
            if (!waitingForVpnReady) vpnConnecting = false;
            return;
        }
        if (text.startsWith("Подключение")
                || text.startsWith("Сервис запущен")
                || text.contains("Повтор через")) {
            proxyConnecting = enableTelegram.isChecked();
            vpnConnecting = enableVpn.isChecked();
        }
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
        enableVpn.setEnabled(true);
        enableTelegram.setEnabled(true);
        toggleVpn.setEnabled(true);
        toggleTelegram.setEnabled(true);
        serverSelect.setEnabled(true);
        serverEdit.setEnabled(enabled);
    }
}
