import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: api
    objectName: "apiLayer"
    property var controller
    property var publicServers: []
    property var people: []
    property var profiles: []
    property var selectedPerson: ({})
    property var splitEntries: []
    property var appList: []
    property string pageName: ""
    property bool publishUseTls: false
    anchors.fill: parent
    z: 100

    readonly property color midnight: "#0E0E11"
    readonly property color onyx: "#1C1D21"
    readonly property color slate: "#2C2D30"
    readonly property color muted: "#878B91"
    readonly property color pale: "#D7D8DB"
    readonly property color accent: "#FBB26A"
    readonly property color red: "#EB5757"

    component Field: TextField {
        implicitHeight: 56; color: api.pale; placeholderTextColor: api.muted
        selectedTextColor: api.midnight; selectionColor: api.accent
        leftPadding: 14; rightPadding: 14
        background: Rectangle { radius: 14; color: api.midnight; border.width: 1; border.color: parent.activeFocus ? api.accent : api.slate }
    }
    component ApiButton: Button {
        id: control
        property bool primary: false
        property bool danger: false
        implicitHeight: 56
        background: Rectangle {
            radius: 16
            color: control.down ? api.slate : (control.primary ? api.pale : api.onyx)
            border.width: control.primary ? 0 : 1
            border.color: control.danger ? api.red : api.slate
        }
        contentItem: Text {
            text: control.text; color: control.danger ? api.red : (control.primary ? api.midnight : api.pale)
            font.pixelSize: 12; font.weight: Font.DemiBold
            horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight
        }
    }
    component PageTitle: Column {
        property alias title: titleText.text
        property alias subtitle: subtitleText.text
        spacing: 5
        Text { id: titleText; width: parent.width; color: api.pale; font.pixelSize: 28; font.weight: Font.Bold; wrapMode: Text.Wrap }
        Text { id: subtitleText; width: parent.width; color: api.muted; font.pixelSize: 14; lineHeight: 1.18; wrapMode: Text.Wrap }
    }
    component Card: Rectangle {
        default property alias content: cardBody.data
        implicitHeight: cardBody.implicitHeight + 28
        radius: 16; color: api.onyx; border.width: 1; border.color: api.slate
        ColumnLayout { id: cardBody; anchors.fill: parent; anchors.leftMargin: 16; anchors.rightMargin: 16; anchors.topMargin: 14; anchors.bottomMargin: 14; spacing: 7 }
    }

    component SectionTitle: Text {
        width: parent ? parent.width : implicitWidth
        topPadding: 18; bottomPadding: 2
        color: api.pale; font.pixelSize: 19; font.weight: Font.Bold; wrapMode: Text.Wrap
    }
    component PageAction: Rectangle {
        id: action
        property string title: ""
        property string subtitle: ""
        signal clicked()
        width: parent ? parent.width : implicitWidth
        implicitHeight: actionBody.implicitHeight + 28
        radius: 16; color: actionMouse.pressed ? api.slate : api.onyx
        border.width: 1; border.color: api.slate
        Column {
            id: actionBody
            anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16; anchors.rightMargin: 16; spacing: 4
            Text { width: parent.width; text: action.title + "  ›"; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold; wrapMode: Text.Wrap }
            Text { width: parent.width; text: action.subtitle; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
        }
        MouseArea { id: actionMouse; anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: action.clicked() }
    }
    component ToggleCard: Rectangle {
        id: toggleCard
        property string title: ""
        property string subtitle: ""
        property bool checked: false
        signal changed(bool value)
        width: parent ? parent.width : implicitWidth
        implicitHeight: toggleBody.implicitHeight + 28
        radius: 16; color: api.onyx; border.width: 1; border.color: api.slate
        ColumnLayout {
            id: toggleBody
            anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16; anchors.rightMargin: 16; spacing: 4
            RowLayout {
                Layout.fillWidth: true
                Text { Layout.fillWidth: true; text: toggleCard.title; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold; wrapMode: Text.Wrap }
                Switch {
                    id: toggle
                    checked: toggleCard.checked
                    onToggled: toggleCard.changed(checked)
                    indicator: Rectangle {
                        implicitWidth: 48; implicitHeight: 28; radius: 14
                        color: toggle.checked ? api.accent : "#494B50"
                        Rectangle { width: 22; height: 22; radius: 11; y: 3; x: toggle.checked ? parent.width - width - 3 : 3; color: toggle.checked ? api.midnight : api.pale; Behavior on x { NumberAnimation { duration: 130 } } }
                    }
                }
            }
            Text { Layout.fillWidth: true; text: toggleCard.subtitle; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
        }
    }
    function openPublicServers() { pageName = "public"; root.currentPage = 3; controller.loadPublicServers() }
    function openCreatePerson() { createPersonPopup.open() }
    function openProfiles() { profiles = JSON.parse(controller.profilesJson || "[]"); if (profiles.length === 0) { pageName = ""; root.currentPage = 3; return } pageName = "profiles"; root.currentPage = 0 }
    function openConfigImport() { configImportPopup.open() }
    function closePage() { pageName = "" }
    function openSplitTunnel() { splitEntries = JSON.parse(controller.splitEntriesJson || "[]"); pageName = "split"; root.currentPage = 0 }
    function openAppSplit() { appList = JSON.parse(controller.appSplitAppsJson || "[]"); pageName = "appsplit"; root.currentPage = 0 }
    function openTls() { pageName = "tls"; root.currentPage = 3 }
    function openMigration() { pageName = "migration"; root.currentPage = 3 }
    function openPublish() { pageName = "publish"; root.currentPage = 3 }
    function openPerson(person) {
        selectedPerson = person
        personLogin.text = person.login || ""
        personDaily.text = String(person.daily_mb || 0)
        personMonthly.text = String(person.monthly_mb || 0)
        personSpeed.text = String(person.speed_mbps || 0)
        personDays.text = "30"
        personPopup.open()
    }
    function showAccessCode(code) { accessCodeOutput.text = code; accessCodePopup.open() }

    Rectangle {
        id: pageOverlay
        visible: api.pageName !== ""
        anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top; anchors.bottom: parent.bottom
        anchors.bottomMargin: 66
        color: api.midnight

        ScrollView {
            anchors.fill: parent; clip: true; contentWidth: availableWidth
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
            Column {
                width: parent.width - 40; x: 20; topPadding: 18; bottomPadding: 28; spacing: 10
                PageTitle {
                    width: parent.width
                    title: api.pageName === "profiles" ? "Серверы"
                           : api.pageName === "public" ? "Бесплатные серверы"
                           : api.pageName === "split" ? "Раздельное туннелирование"
                           : api.pageName === "appsplit" ? "Туннелирование приложений"
                           : api.pageName === "tls" ? "TLS-защита"
                           : api.pageName === "migration" ? "Перенос сервера"
                           : api.pageName === "publish" ? "Публичный сервер"
                           : "Пельмени VPN"
                    subtitle: api.pageName === "profiles" ? "Нажми на строку для выбора, на шестерёнку — для параметров."
                              : api.pageName === "public" ? "Каждое подключение получает отдельный SSH-аккаунт, свои счётчики трафика и лимиты владельца."
                              : api.pageName === "split" ? "Выбери приложения и сайты, которые должны использовать VPN. Правила приложений и адресов можно применять одновременно."
                              : api.pageName === "appsplit" ? "Выбери программы, которые работают через VPN или напрямую."
                              : api.pageName === "tls" ? "Дополнительная TLS-обёртка SSH с клиентским сертификатом."
                              : api.pageName === "migration" ? "Скопировать пользователей и настройки, затем заменить адрес активного профиля."
                              : api.pageName === "publish" ? "Безопасная выдача отдельных аккаунтов и лимитов для бесплатного каталога."
                              : ""
                }
                Column {
                    width: parent.width; spacing: 12; visible: api.pageName === "profiles"
                    Text {
                        width: parent.width
                        text: api.profiles.length ? "Нажми на сервер для выбора, на шестерёнку — для параметров." : "Серверов пока нет. Добавь первый сервер."
                        color: api.muted; font.pixelSize: 14; lineHeight: 1.18; wrapMode: Text.Wrap
                    }
                    Column {
                        width: parent.width; spacing: 0
                        Repeater {
                            model: api.profiles
                            delegate: Item {
                                required property var modelData
                                width: parent.width; height: 76
                                RowLayout {
                                    anchors.fill: parent; spacing: 10
                                    Text { text: modelData.active ? "●" : "○"; color: modelData.active ? api.accent : api.muted; font.pixelSize: 24; Layout.preferredWidth: 28; horizontalAlignment: Text.AlignHCenter }
                                    ColumnLayout {
                                        Layout.fillWidth: true; spacing: 3
                                        Text { Layout.fillWidth: true; text: modelData.name; color: modelData.active ? api.accent : api.pale; font.pixelSize: 17; font.weight: Font.DemiBold; elide: Text.ElideRight }
                                        Text { Layout.fillWidth: true; text: modelData.host + ":" + modelData.port; color: api.muted; font.pixelSize: 12; elide: Text.ElideRight }
                                    }
                                    Button {
                                        implicitWidth: 48; implicitHeight: 48
                                        background: Rectangle { radius: 14; color: parent.down ? api.slate : "transparent" }
                                        contentItem: Text { text: "⚙"; color: api.pale; font.pixelSize: 21; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                        onClicked: { controller.activateProfile(modelData.id); root.openServerEditor() }
                                    }
                                }
                                MouseArea {
                                    anchors.left: parent.left; anchors.right: parent.right; anchors.top: parent.top; anchors.bottom: parent.bottom
                                    anchors.rightMargin: 54; cursorShape: Qt.PointingHandCursor
                                    onClicked: { controller.activateProfile(modelData.id); api.profiles = JSON.parse(controller.profilesJson || "[]") }
                                }
                                Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.leftMargin: 38; height: 1; color: api.slate }
                            }
                        }
                    }
                    ApiButton { width: parent.width; text: "+  ДОБАВИТЬ СЕРВЕР"; primary: true; onClicked: { api.closePage(); root.openNewServerEditor() } }
                    ApiButton { width: parent.width; text: "БЕСПЛАТНЫЕ СЕРВЕРЫ"; onClicked: api.openPublicServers() }
                }

                Column {
                    width: parent.width; spacing: 12; visible: api.pageName === "public"
                    Text {
                        width: parent.width
                        text: "Каждое подключение получает отдельный SSH-аккаунт, свои счётчики трафика и лимиты владельца."
                        color: api.muted; font.pixelSize: 14; lineHeight: 1.18; wrapMode: Text.Wrap
                    }
                    Card {
                        width: parent.width
                        Text { Layout.fillWidth: true; text: "Общественный каталог"; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: "Серверы добавляют участники сообщества. Владелец VPN технически может видеть адреса назначения и незашифрованный HTTP-трафик."; color: api.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                    }
                    Card {
                        width: parent.width; visible: api.publicServers.length === 0
                        Text { Layout.fillWidth: true; text: controller.actionBusy ? "Обновляю каталог…" : "Пока нет свободных серверов"; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: controller.actionBusy ? "Список загружается из открытого реестра проекта." : "Попробуй обновить каталог позже."; color: api.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                    }
                    Repeater {
                        model: api.publicServers
                        delegate: Card {
                            required property var modelData
                            required property int index
                            width: parent.width
                            Text { Layout.fillWidth: true; text: modelData.name || modelData.host; color: api.pale; font.pixelSize: 17; font.weight: Font.Bold; elide: Text.ElideRight }
                            Text { Layout.fillWidth: true; text: (modelData.location || "Регион не указан") + "\nДень: " + (modelData.daily_mb || "∞") + " МБ · месяц: " + (modelData.monthly_mb || "∞") + " МБ\nСкорость: " + (modelData.speed_mbps || "∞") + " Мбит/с · срок: " + (modelData.days || "∞") + " дн."; color: api.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                            ApiButton { Layout.fillWidth: true; text: "ПОЛУЧИТЬ ЛИЧНЫЙ ДОСТУП"; primary: true; onClicked: controller.claimPublicServer(index) }
                        }
                    }
                    ApiButton { width: parent.width; text: "ОБНОВИТЬ КАТАЛОГ"; onClicked: controller.loadPublicServers() }
                }

                Column {
                    width: parent.width; spacing: 10; visible: api.pageName === "split"
                    SectionTitle { text: "Приложения" }
                    PageAction { title: "Туннелирование приложений"; subtitle: controller.appSplitEnabled ? (controller.appSplitMode === "only" ? "VPN только для выбранных" : "VPN везде, кроме выбранных") + " · " + api.appList.length : "Выключено · VPN доступен всем программам"; onClicked: api.openAppSplit() }
                    SectionTitle { text: "Сайты и IP-адреса" }
                    Text { width: parent.width; text: "Можно выбрать набор адресов. Домены, IP и CIDR применяются одновременно с правилами программ."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                    ToggleCard { title: "Использовать раздельные маршруты"; subtitle: "Если выключено, весь трафик работает как раньше и идёт через VPN."; checked: controller.splitEnabled; onChanged: controller.setBooleanSetting("split_enabled", value) }
                    Card {
                        width: parent.width; visible: controller.splitEnabled
                        Text { Layout.fillWidth: true; text: "Активный набор"; color: api.accent; font.pixelSize: 18; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: (controller.splitMode === "only" ? "Через VPN только адреса из списка" : "Через VPN всё, кроме адресов из списка") + " · " + api.splitEntries.length + " записей"; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                    }
                    SectionTitle { text: "Наборы адресов" }
                    Item {
                        width: parent.width; height: 68
                        RowLayout { anchors.fill: parent
                            Text { text: "☑"; color: api.accent; font.pixelSize: 28; Layout.preferredWidth: 42; horizontalAlignment: Text.AlignHCenter }
                            ColumnLayout { Layout.fillWidth: true; spacing: 2
                                Text { Layout.fillWidth: true; text: "Свои адреса"; color: api.accent; font.pixelSize: 17; elide: Text.ElideRight }
                                Text { Layout.fillWidth: true; text: (controller.splitMode === "only" ? "Только через VPN" : "Исключить из VPN") + " · " + api.splitEntries.length + " записей"; color: api.muted; font.pixelSize: 13 }
                            }
                            Button {
                                implicitWidth: 52; implicitHeight: 52
                                background: Rectangle { color: parent.down ? api.slate : "transparent"; radius: 14 }
                                contentItem: Text { text: "⚙"; color: api.pale; font.pixelSize: 22; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                onClicked: { splitListInput.text = api.splitEntries.join("\n"); splitEditorPopup.open() }
                            }
                        }
                        Rectangle { anchors.left: parent.left; anchors.right: parent.right; anchors.bottom: parent.bottom; anchors.leftMargin: 42; height: 1; color: api.slate }
                    }
                    PageAction { title: "Создать новый набор"; subtitle: "Свой режим и список доменов, IP или CIDR"; onClicked: { splitListInput.text = api.splitEntries.join("\n"); splitEditorPopup.open() } }
                    SectionTitle { text: "Перенос списков" }
                    PageAction { title: "Импортировать набор"; subtitle: "Файл Pelmeni Split Tunnel в формате JSON"; onClicked: splitImportPopup.open() }
                    PageAction { title: "Экспортировать активный набор"; subtitle: "Сохранить список и режим в отдельный файл"; onClicked: splitExportPopup.open() }
                    Text { width: parent.width; topPadding: 10; text: "Важно: домены используют CDN и меняют адреса, поэтому список не может быть абсолютно полным."; color: api.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                }

                Column {
                    width: parent.width; spacing: 10; visible: api.pageName === "appsplit"
                    ToggleCard { title: "Использовать правила приложений"; subtitle: "Если выключено, VPN доступен всем программам."; checked: controller.appSplitEnabled; onChanged: controller.setBooleanSetting("app_split_enabled", value) }
                    SectionTitle { text: "Режим" }
                    Card {
                        width: parent.width
                        ApiButton { Layout.fillWidth: true; primary: controller.appSplitMode === "bypass"; text: "VPN ВЕЗДЕ, КРОМЕ ВЫБРАННЫХ"; onClicked: controller.setStringSetting("app_split_mode", "bypass") }
                        ApiButton { Layout.fillWidth: true; primary: controller.appSplitMode === "only"; text: "VPN ТОЛЬКО ДЛЯ ВЫБРАННЫХ"; onClicked: controller.setStringSetting("app_split_mode", "only") }
                    }
                    SectionTitle { text: "Выбор программ" }
                    Text { width: parent.width; text: "Укажи EXE-файлы программ. Правила сохраняются отдельно и применяются при подключении."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                    RowLayout { width: parent.width
                        Field { id: appPathInput; Layout.fillWidth: true; placeholderText: "C:\\Program Files\\App\\app.exe" }
                        ApiButton { text: "+"; implicitWidth: 56; onClicked: { if (appPathInput.text.trim()) { api.appList.push(appPathInput.text.trim()); api.appList = api.appList.slice(); appPathInput.text = "" } } }
                    }
                    Repeater {
                        model: api.appList
                        delegate: Card {
                            required property string modelData; required property int index
                            width: parent.width
                            Text { Layout.fillWidth: true; text: modelData; color: api.pale; font.pixelSize: 13; wrapMode: Text.WrapAnywhere }
                            ApiButton { Layout.fillWidth: true; danger: true; primary: false; text: "УДАЛИТЬ"; onClicked: { api.appList.splice(index, 1); api.appList = api.appList.slice() } }
                        }
                    }
                    ApiButton { width: parent.width; text: "СОХРАНИТЬ"; onClicked: controller.setJsonListSetting("app_split_apps", JSON.stringify(api.appList)) }
                    Text { width: parent.width; text: "Windows не предоставляет Android VpnService: правило действует для программ, поддерживающих системный прокси. Сайты и IP применяются системно через PAC."; color: api.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                }

                Column {
                    width: parent.width; spacing: 10; visible: api.pageName === "tls"
                    Card {
                        width: parent.width
                        Text { Layout.fillWidth: true; text: "Как это работает"; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: "Обычный SSH уже шифрует данные. Этот режим дополнительно оборачивает SSH в TLS на порту 443 и требует клиентский сертификат, чтобы скрыть SSH от простого распознавания и сканирования."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                    }
                    ApiButton { width: parent.width; primary: true; text: controller.tlsConfigured ? "ПЕРЕНАСТРОИТЬ TLS НА СЕРВЕРЕ" : "НАСТРОИТЬ TLS НА СЕРВЕРЕ"; onClicked: controller.configureTls() }
                    ToggleCard { title: "Использовать TLS-обёртку"; subtitle: controller.tlsConfigured ? "Применяется отдельно для активного сервера при следующем подключении." : "Сначала настрой TLS на сервере и получи клиентский сертификат."; checked: controller.tlsEnabled; onChanged: controller.setTlsEnabled(value) }
                    ApiButton { width: parent.width; primary: false; danger: true; text: "УДАЛИТЬ TLS С СЕРВЕРА"; onClicked: confirmTlsRemovePopup.open() }
                }

                Column {
                    width: parent.width; spacing: 10; visible: api.pageName === "migration"
                    SectionTitle { text: "Новый сервер" }
                    Field { id: migrationName; width: parent.width; placeholderText: "Новое название"; text: controller.serverName }
                    Field { id: migrationHost; width: parent.width; placeholderText: "Новый IP или домен" }
                    Field { id: migrationUser; width: parent.width; placeholderText: "Администратор SSH"; text: "root" }
                    Field { id: migrationPassword; width: parent.width; placeholderText: "Пароль SSH"; echoMode: TextInput.Password }
                    Field { id: migrationPort; width: parent.width; placeholderText: "SSH-порт"; text: "22" }
                    Card { width: parent.width; Text { Layout.fillWidth: true; text: "Переносятся пользователи, лимиты и настройки Пельмени VPN. Старый сервер автоматически не удаляется до успешного завершения."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap } }
                    ApiButton { width: parent.width; text: "ПРОВЕРИТЬ И ПЕРЕНЕСТИ"; onClicked: controller.migrateServer(migrationName.text, migrationHost.text, migrationPort.text, migrationUser.text, migrationPassword.text) }
                }

                Column {
                    width: parent.width; spacing: 10; visible: api.pageName === "publish"
                    SectionTitle { text: "Карточка каталога" }
                    Field { id: publishName; width: parent.width; placeholderText: "Название сервера" }
                    Field { id: publishLocation; width: parent.width; placeholderText: "Страна или регион" }
                    SectionTitle { text: "Лимиты каждого доступа" }
                    RowLayout { width: parent.width; Field { id: publishDays; Layout.fillWidth: true; text: "30"; placeholderText: "Дней" } Field { id: publishSpeed; Layout.fillWidth: true; text: "10"; placeholderText: "Мбит/с" } }
                    RowLayout { width: parent.width; Field { id: publishDaily; Layout.fillWidth: true; text: "2048"; placeholderText: "МБ/день" } Field { id: publishMonthly; Layout.fillWidth: true; text: "51200"; placeholderText: "МБ/месяц" } }
                    Field { id: publishMaxUsers; width: parent.width; text: "50"; placeholderText: "Максимум пользователей" }
                    ToggleCard { title: "Выдавать TLS"; subtitle: controller.tlsConfigured ? "Каждый пользователь получит свой доступ и клиентский TLS-сертификат." : "TLS на этом сервере не настроен."; checked: api.publishUseTls && controller.tlsConfigured; onChanged: api.publishUseTls = value && controller.tlsConfigured }
                    Card { width: parent.width; Text { Layout.fillWidth: true; text: "Пользователи получают отдельные SSH-аккаунты. Администраторский пароль не публикуется."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap } }
                    ApiButton { width: parent.width; text: "ПРОВЕРИТЬ SSH-КЛЮЧ И ПОДГОТОВИТЬ"; onClicked: controller.preparePublicServer(publishName.text, publishLocation.text, Number(publishDays.text), Number(publishDaily.text), Number(publishMonthly.text), Number(publishSpeed.text), Number(publishMaxUsers.text), api.publishUseTls) }
                }                }
            }
        }

    Rectangle {
        anchors.fill: parent; visible: controller.actionBusy; color: "#B30E0E11"; z: 300
        MouseArea { anchors.fill: parent }
        Column { anchors.centerIn: parent; spacing: 12
            BusyIndicator { anchors.horizontalCenter: parent.horizontalCenter; running: parent.visible }
            Text { text: "Выполняем операцию…"; color: api.pale; font.pixelSize: 14 }
        }
    }

    Popup {
        id: confirmTlsRemovePopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 12
            Text { Layout.fillWidth: true; text: "Полностью удалить TLS?"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: "Сервис stunnel, сертификаты и локальный ключ будут удалены. Обычное SSH-подключение останется доступно."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
            RowLayout { Layout.fillWidth: true
                ApiButton { Layout.fillWidth: true; text: "ОТМЕНА"; onClicked: confirmTlsRemovePopup.close() }
                ApiButton { Layout.fillWidth: true; danger: true; text: "УДАЛИТЬ"; onClicked: { confirmTlsRemovePopup.close(); controller.removeTls() } }
            }
        }
    }
    Popup {
        id: createPersonPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 9
            Text { Layout.fillWidth: true; text: "Новый пользователь"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Field { id: personLabel; Layout.fillWidth: true; placeholderText: "Имя" }
            Field { id: personNewLogin; Layout.fillWidth: true; placeholderText: "Логин" }
            RowLayout { Layout.fillWidth: true; Field { id: personNewDays; Layout.fillWidth: true; text: "30"; placeholderText: "Дней" } Field { id: personNewSpeed; Layout.fillWidth: true; text: "0"; placeholderText: "Мбит/с" } }
            RowLayout { Layout.fillWidth: true; Field { id: personNewDaily; Layout.fillWidth: true; text: "0"; placeholderText: "МБ/день" } Field { id: personNewMonthly; Layout.fillWidth: true; text: "0"; placeholderText: "МБ/месяц" } }
            RowLayout { Layout.fillWidth: true; ApiButton { Layout.fillWidth: true; text: "ОТМЕНА"; onClicked: createPersonPopup.close() } ApiButton { Layout.fillWidth: true; primary: true; text: "СОЗДАТЬ"; onClicked: { controller.createPerson(personLabel.text, personNewLogin.text, Number(personNewDays.text), Number(personNewDaily.text), Number(personNewMonthly.text), Number(personNewSpeed.text)); createPersonPopup.close() } } }
        }
    }

    Popup {
        id: personPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 9
            Text { Layout.fillWidth: true; text: selectedPerson.label || selectedPerson.login || "Пользователь"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Field { id: personLogin; Layout.fillWidth: true; readOnly: true }
            RowLayout { Layout.fillWidth: true; Field { id: personDays; Layout.fillWidth: true; placeholderText: "Дней" } ApiButton { text: "ПРОДЛИТЬ"; onClicked: controller.extendPerson(personLogin.text, Number(personDays.text)) } }
            RowLayout { Layout.fillWidth: true; Field { id: personDaily; Layout.fillWidth: true; placeholderText: "МБ/день" } Field { id: personMonthly; Layout.fillWidth: true; placeholderText: "МБ/мес." } Field { id: personSpeed; Layout.fillWidth: true; placeholderText: "Мбит/с" } }
            ApiButton { Layout.fillWidth: true; text: "СОХРАНИТЬ ЛИМИТЫ"; onClicked: controller.updatePersonLimits(personLogin.text, Number(personDaily.text), Number(personMonthly.text), Number(personSpeed.text)) }
            ApiButton { Layout.fillWidth: true; danger: true; text: "ОТОЗВАТЬ ДОСТУП"; onClicked: controller.revokePerson(personLogin.text) }
            ApiButton { Layout.fillWidth: true; text: "ЗАКРЫТЬ"; onClicked: personPopup.close() }
        }
    }

    Popup {
        id: configImportPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 10
            Text { Layout.fillWidth: true; text: "Импорт конфигурации"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Text { Layout.fillWidth: true; text: "Укажи путь к JSON-файлу Android-клиента."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
            Field { id: configPath; Layout.fillWidth: true; placeholderText: "C:\\Users\\...\\config.json" }
            RowLayout { Layout.fillWidth: true; ApiButton { Layout.fillWidth: true; text: "ОТМЕНА"; onClicked: configImportPopup.close() } ApiButton { Layout.fillWidth: true; primary: true; text: "ОТКРЫТЬ"; onClicked: { controller.importConfigFile(configPath.text); configImportPopup.close() } } }
        }
    }

    Popup {
        id: accessCodePopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 10
            Text { Layout.fillWidth: true; text: "Код доступа"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            TextArea { id: accessCodeOutput; Layout.fillWidth: true; Layout.preferredHeight: 140; readOnly: true; wrapMode: Text.WrapAnywhere; color: api.pale; background: Rectangle { color: api.midnight; radius: 12; border.width: 1; border.color: api.slate } }
            ApiButton { Layout.fillWidth: true; primary: true; text: "КОПИРОВАТЬ"; onClicked: controller.copyText(accessCodeOutput.text) }
            ApiButton { Layout.fillWidth: true; text: "ЗАКРЫТЬ"; onClicked: accessCodePopup.close() }
        }
    }

    Popup {
        id: splitEditorPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); height: Math.min(570, root.height - 40); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 22; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 10
            Text { Layout.fillWidth: true; text: "Набор адресов"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Text { Layout.fillWidth: true; text: "По одному домену, IP или CIDR в строке."; color: api.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
            RowLayout { Layout.fillWidth: true
                ApiButton { Layout.fillWidth: true; primary: controller.splitMode === "bypass"; text: "ИСКЛЮЧИТЬ"; onClicked: controller.setStringSetting("split_mode", "bypass") }
                ApiButton { Layout.fillWidth: true; primary: controller.splitMode === "only"; text: "ТОЛЬКО VPN"; onClicked: controller.setStringSetting("split_mode", "only") }
            }
            TextArea { id: splitListInput; Layout.fillWidth: true; Layout.fillHeight: true; color: api.pale; wrapMode: TextEdit.NoWrap; font.family: "Consolas"; font.pixelSize: 13; leftPadding: 12; rightPadding: 12; background: Rectangle { color: api.midnight; radius: 14; border.width: 1; border.color: splitListInput.activeFocus ? api.accent : api.slate } }
            RowLayout { Layout.fillWidth: true
                ApiButton { Layout.fillWidth: true; primary: false; text: "ОТМЕНА"; onClicked: splitEditorPopup.close() }
                ApiButton { Layout.fillWidth: true; text: "СОХРАНИТЬ"; onClicked: { api.splitEntries = splitListInput.text.split(/\r?\n/).map(x => x.trim()).filter(x => x); controller.setJsonListSetting("split_entries", JSON.stringify(api.splitEntries)); splitEditorPopup.close() } }
            }
        }
    }

    Popup {
        id: splitImportPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 22; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 10
            Text { Layout.fillWidth: true; text: "Импортировать набор"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Field { id: splitImportPath; Layout.fillWidth: true; placeholderText: "C:\\Users\\...\\split.json" }
            RowLayout { Layout.fillWidth: true
                ApiButton { Layout.fillWidth: true; primary: false; text: "ОТМЕНА"; onClicked: splitImportPopup.close() }
                ApiButton { Layout.fillWidth: true; text: "ИМПОРТ"; onClicked: { controller.importSplitFile(splitImportPath.text); splitImportPopup.close() } }
            }
        }
    }

    Popup {
        id: splitExportPopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        background: Rectangle { color: api.onyx; radius: 22; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 10
            Text { Layout.fillWidth: true; text: "Экспортировать набор"; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold }
            Field { id: splitExportPath; Layout.fillWidth: true; placeholderText: "C:\\Users\\...\\pelmeni-split.json" }
            RowLayout { Layout.fillWidth: true
                ApiButton { Layout.fillWidth: true; primary: false; text: "ОТМЕНА"; onClicked: splitExportPopup.close() }
                ApiButton { Layout.fillWidth: true; text: "СОХРАНИТЬ"; onClicked: { controller.exportSplitFile(splitExportPath.text); splitExportPopup.close() } }
            }
        }
    }

    Popup {
        id: messagePopup; parent: Overlay.overlay; anchors.centerIn: parent; width: Math.min(348, root.width - 24); modal: true; padding: 18
        property string dialogTitle: "Пельмени VPN"
        property string dialogText: ""
        function show(title, text) { dialogTitle = title; dialogText = text; open() }
        background: Rectangle { color: api.onyx; radius: 22; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout { spacing: 12
            Text { Layout.fillWidth: true; text: messagePopup.dialogTitle; color: api.pale; font.pixelSize: 21; font.weight: Font.Bold; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: messagePopup.dialogText; color: api.pale; font.pixelSize: 14; wrapMode: Text.Wrap }
            ApiButton { Layout.fillWidth: true; text: "OK"; onClicked: messagePopup.close() }
        }
    }
    Connections {
        target: controller
        function onPublicServersChanged() { api.publicServers = JSON.parse(controller.publicServersJson || "[]") }
        function onPeopleChanged() { api.people = JSON.parse(controller.peopleJson || "[]") }
        function onProfilesChanged() { api.profiles = JSON.parse(controller.profilesJson || "[]") }
        function onAccessCodeReady(code) { api.showAccessCode(code) }
    }
    Component.onCompleted: {
        profiles = JSON.parse(controller.profilesJson || "[]")
        people = JSON.parse(controller.peopleJson || "[]")
    }
}
