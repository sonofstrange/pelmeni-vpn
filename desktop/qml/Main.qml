import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window

ApplicationWindow {
    id: root
    width: 380
    height: 680
    minimumWidth: 380
    maximumWidth: 380
    minimumHeight: 680
    maximumHeight: 680
    flags: Qt.Window | Qt.WindowTitleHint | Qt.WindowSystemMenuHint | Qt.WindowMinimizeButtonHint | Qt.WindowCloseButtonHint
    visible: true
    title: backend.appName
    color: midnight

    readonly property color midnight: "#0E0E11"
    readonly property color onyx: "#1C1D21"
    readonly property color slate: "#2C2D30"
    readonly property color charcoal: "#494B50"
    readonly property color muted: "#878B91"
    readonly property color light: "#C1C2C5"
    readonly property color pale: "#D7D8DB"
    readonly property color accent: "#FBB26A"
    readonly property color red: "#EB5757"
    readonly property color violet: "#A87BE2"
    property int currentPage: 0
    property string pendingHost: ""
    property string pendingKeyType: ""
    property string pendingFingerprint: ""
    property string editingProfileId: ""

    font.family: "Segoe UI"

    component AppButton: Button {
        id: control
        property bool primary: true
        property bool danger: false
        implicitHeight: 56
        leftPadding: 18
        rightPadding: 18
        background: Rectangle {
            radius: 16
            color: control.down ? (control.primary ? root.light : root.slate)
                                : (control.primary ? root.pale : root.onyx)
            border.width: control.primary ? 0 : 1
            border.color: control.danger ? root.red : root.slate
        }
        contentItem: Text {
            text: control.text
            color: control.danger ? root.red : (control.primary ? root.midnight : root.pale)
            font.pixelSize: 13
            font.weight: Font.DemiBold
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
    }

    component Panel: Rectangle {
        default property alias content: body.data
        implicitHeight: body.implicitHeight + 32
        radius: 16
        color: root.onyx
        border.width: 1
        border.color: root.slate
        ColumnLayout {
            id: body
            anchors.fill: parent
            anchors.margins: 16
            spacing: 8
        }
    }

    component ActionCard: Rectangle {
        id: actionCard
        property string title: ""
        property string subtitle: ""
        signal clicked()
        implicitHeight: Math.max(78, actionColumn.implicitHeight + 28)
        radius: 16
        color: actionMouse.pressed ? root.slate : root.onyx
        border.width: 1
        border.color: actionMouse.containsMouse ? root.charcoal : root.slate
        Column {
            id: actionColumn
            anchors.left: parent.left
            anchors.right: arrow.left
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 17
            anchors.rightMargin: 12
            spacing: 5
            Text {
                width: parent.width
                text: actionCard.title
                color: root.pale
                font.pixelSize: 16
                font.weight: Font.DemiBold
                wrapMode: Text.Wrap
            }
            Text {
                width: parent.width
                text: actionCard.subtitle
                color: root.muted
                font.pixelSize: 12
                lineHeight: 1.16
                wrapMode: Text.Wrap
            }
        }
        Text {
            id: arrow
            anchors.right: parent.right
            anchors.rightMargin: 17
            anchors.verticalCenter: parent.verticalCenter
            text: "›"
            color: root.light
            font.pixelSize: 29
            font.weight: Font.Light
        }
        MouseArea {
            id: actionMouse
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: actionCard.clicked()
        }
    }

    component SettingsToggleCard: Rectangle {
        id: toggleCard
        property string title: ""
        property string subtitle: ""
        property bool checked: false
        signal changed(bool value)
        implicitHeight: toggleBody.implicitHeight + 28
        radius: 16; color: root.onyx; border.width: 1; border.color: root.slate
        ColumnLayout {
            id: toggleBody
            anchors.left: parent.left; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: 16; anchors.rightMargin: 16; spacing: 4
            RowLayout {
                Layout.fillWidth: true
                Text { Layout.fillWidth: true; text: toggleCard.title; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold; wrapMode: Text.Wrap }
                Switch {
                    id: cardSwitch; checked: toggleCard.checked; onToggled: toggleCard.changed(checked)
                    indicator: Rectangle { implicitWidth: 48; implicitHeight: 28; radius: 14; color: cardSwitch.checked ? root.accent : root.charcoal
                        Rectangle { width: 22; height: 22; radius: 11; y: 3; x: cardSwitch.checked ? parent.width - width - 3 : 3; color: cardSwitch.checked ? root.midnight : root.pale; Behavior on x { NumberAnimation { duration: 130 } } }
                    }
                }
            }
            Text { Layout.fillWidth: true; text: toggleCard.subtitle; color: root.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
        }
    }

    component SectionHeading: Text {
        width: parent ? parent.width : implicitWidth
        topPadding: 18; bottomPadding: 2
        text: "Раздел"; color: root.pale; font.pixelSize: 19; font.weight: Font.Bold; wrapMode: Text.Wrap
    }
    component PageHeading: Column {
        property alias title: titleText.text
        property alias subtitle: subtitleText.text
        spacing: 5
        Text {
            id: titleText
            width: parent.width
            color: root.pale
            font.pixelSize: 28
            font.weight: Font.Bold
            wrapMode: Text.Wrap
        }
        Text {
            id: subtitleText
            width: parent.width
            color: root.muted
            font.pixelSize: 14
            lineHeight: 1.18
            wrapMode: Text.Wrap
        }
    }

    component ModeButton: Item {
        id: modeControl
        property string modeTitle: "VPN"
        property bool active: false
        property bool pending: false
        signal clicked()
        implicitWidth: 156
        implicitHeight: 156
        Rectangle {
            id: activeHalo
            width: 150
            height: 150
            anchors.centerIn: parent
            radius: width / 2
            color: "transparent"
            border.width: 10
            border.color: root.accent
            visible: modeControl.active && !modeControl.pending
            opacity: 0.18
            SequentialAnimation {
                running: activeHalo.visible
                loops: Animation.Infinite
                NumberAnimation { target: activeHalo; property: "opacity"; from: 0.12; to: 0.23; duration: 900; easing.type: Easing.InOutSine }
                NumberAnimation { target: activeHalo; property: "opacity"; from: 0.23; to: 0.12; duration: 900; easing.type: Easing.InOutSine }
            }
        }
        Rectangle {
            id: circle
            width: 150
            height: 150
            anchors.centerIn: parent
            radius: width / 2
            color: modeMouse.pressed ? root.slate : root.midnight
            border.width: modeControl.active || modeControl.pending ? 4 : 3
            border.color: modeControl.active || modeControl.pending ? root.accent : root.pale
            scale: modeMouse.pressed ? 0.96 : (modeMouse.containsMouse ? 1.015 : 1)
            Behavior on scale { NumberAnimation { duration: 115; easing.type: Easing.OutCubic } }
            Behavior on border.color { ColorAnimation { duration: 180 } }
            Column {
                anchors.centerIn: parent
                width: parent.width - 25
                spacing: 4
                Text {
                    width: parent.width
                    text: modeControl.modeTitle
                    color: modeControl.active ? root.accent : root.pale
                    font.pixelSize: 17
                    font.weight: Font.Bold
                    horizontalAlignment: Text.AlignHCenter
                }
                Text {
                    width: parent.width
                    text: modeControl.pending ? "ПОДКЛЮЧЕНИЕ" : (modeControl.active ? "ВКЛЮЧЕН" : "ВКЛЮЧИТЬ")
                    color: modeControl.active || modeControl.pending ? root.accent : root.muted
                    font.pixelSize: 10
                    font.weight: Font.DemiBold
                    horizontalAlignment: Text.AlignHCenter
                }
            }
        }
        Canvas {
            id: connectionRing
            width: 156
            height: 156
            anchors.centerIn: parent
            visible: modeControl.pending
            antialiasing: true
            property real phase: 0
            onPhaseChanged: requestPaint()
            onVisibleChanged: requestPaint()
            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                const center = width / 2
                const radius = width / 2 - 5
                ctx.lineCap = "round"
                ctx.lineWidth = 2.5
                ctx.strokeStyle = Qt.rgba(0.29, 0.30, 0.31, 0.60)
                ctx.beginPath()
                ctx.arc(center, center, radius, 0, Math.PI * 2)
                ctx.stroke()
                ctx.lineWidth = 7
                ctx.strokeStyle = root.accent
                ctx.shadowColor = root.accent
                ctx.shadowBlur = 9
                ctx.beginPath()
                const startAngle = connectionRing.phase * Math.PI * 2 - Math.PI / 2
                ctx.arc(center, center, radius, startAngle, startAngle + 125 * Math.PI / 180)
                ctx.stroke()
            }
            NumberAnimation {
                target: connectionRing
                property: "phase"
                running: modeControl.pending
                loops: Animation.Infinite
                from: 0
                to: 1
                duration: 1050
                easing.type: Easing.Linear
            }
        }        MouseArea {
            id: modeMouse
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            enabled: !backend.busy
            onClicked: modeControl.clicked()
        }
    }

    component StyledField: TextField {
        implicitHeight: 56
        color: root.pale
        placeholderTextColor: root.muted
        selectedTextColor: root.midnight
        selectionColor: root.accent
        font.pixelSize: 14
        leftPadding: 15
        rightPadding: 15
        background: Rectangle {
            radius: 14
            color: root.midnight
            border.width: 1
            border.color: parent.activeFocus ? root.accent : root.slate
        }
    }

    component DarkScrollBar: ScrollBar {
        width: 8
        policy: ScrollBar.AsNeeded
        background: Item { implicitWidth: 8 }
        contentItem: Rectangle {
            implicitWidth: 6
            radius: 3
            color: parent.pressed || parent.hovered ? root.charcoal : root.slate
            opacity: parent.active ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 160 } }
        }
    }
    Rectangle {
        id: contentArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: bottomBar.top
        color: root.midnight

        StackLayout {
            anchors.fill: parent
            currentIndex: root.currentPage

            ScrollView {
                id: homeScroll
                clip: true
                contentWidth: availableWidth
                ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                ScrollBar.vertical: DarkScrollBar {}
                Column {
                    width: homeScroll.availableWidth - 40
                    x: (homeScroll.availableWidth - width) / 2
                    topPadding: 16
                    bottomPadding: 28
                    spacing: 0

                    Text {
                        width: parent.width
                        text: backend.appName
                        color: root.pale
                        font.pixelSize: 28
                        font.weight: Font.Bold
                    }

                    Text {
                        width: parent.width
                        topPadding: 30
                        text: backend.status
                        color: backend.proxyActive || backend.vpnActive ? root.accent : root.muted
                        font.pixelSize: 15
                        font.weight: Font.DemiBold
                        horizontalAlignment: Text.AlignHCenter
                    }
                    Item { width: 1; height: 16 }
                    Row {
                        anchors.horizontalCenter: parent.horizontalCenter
                        spacing: 8
                        ModeButton {
                            modeTitle: "ПРОКСИ"
                            active: backend.proxyActive
                            pending: backend.proxyPending
                            onClicked: backend.toggleMode("proxy")
                        }
                        ModeButton {
                            modeTitle: "VPN"
                            active: backend.vpnActive
                            pending: backend.vpnPending
                            onClicked: backend.toggleMode("vpn")
                        }
                    }

                    Text {
                        width: parent.width
                        topPadding: 15
                        text: "Скорость: " + backend.speedText + " · Пинг: " + backend.pingText
                        color: root.muted
                        font.pixelSize: 14
                        horizontalAlignment: Text.AlignHCenter
                    }
                    Item { width: 1; height: 24 }
                    Panel {
                        width: parent.width
                        Text {
                            Layout.fillWidth: true
                            text: "Раздельное туннелирование"
                            color: root.pale
                            font.pixelSize: 17
                            font.weight: Font.Bold
                        }
                        Text {
                            Layout.fillWidth: true
                            text: "Выбери приложения и сайты, которые идут через VPN или напрямую."
                            color: root.muted
                            font.pixelSize: 13
                            wrapMode: Text.Wrap
                        }

                        AppButton {
                            Layout.fillWidth: true
                            Layout.topMargin: 6
                            text: "НАСТРОИТЬ ТУННЕЛИРОВАНИЕ"
                            onClicked: apiLayer.openSplitTunnel()
                        }
                        Text {
                            Layout.fillWidth: true
                            text: backend.splitEnabled ? (backend.splitMode === "only" ? "Только выбранные адреса через VPN" : "Выбранные адреса напрямую") : "Выключено · весь трафик через VPN"
                            color: root.muted
                            font.pixelSize: 12
                            wrapMode: Text.Wrap
                        }
                    }
                    Text {
                        width: parent.width
                        topPadding: 24
                        bottomPadding: 10
                        text: "Активный сервер"
                        color: root.pale
                        font.pixelSize: 18
                        font.weight: Font.Bold
                    }
                    Panel {
                        width: parent.width
                        Text {
                            Layout.fillWidth: true
                            text: backend.serverName
                            color: root.pale
                            font.pixelSize: 22
                            font.weight: Font.Bold
                        }
                        Text { Layout.fillWidth: true; text: backend.serverAddress; color: root.muted; font.pixelSize: 14 }
                        Text {
                            Layout.fillWidth: true
                            Layout.topMargin: 4
                            text: "Нажми «Выбрать», чтобы быстро переключиться. Параметры и TLS хранятся отдельно для каждого сервера."
                            color: root.muted
                            font.pixelSize: 12
                            wrapMode: Text.Wrap
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            Layout.topMargin: 5
                            spacing: 8
                            AppButton { Layout.fillWidth: true; text: "ВЫБРАТЬ"; onClicked: apiLayer.openProfiles() }
                            AppButton { Layout.fillWidth: true; text: "ПАРАМЕТРЫ"; onClicked: root.openServerEditor() }
                        }
                    }
                    Item { width: 1; height: 20 }
                    Panel {
                        width: parent.width
                        Text { Layout.fillWidth: true; text: "Трафик"; color: root.pale; font.pixelSize: 17; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; Layout.topMargin: 4; text: "За сессию"; color: root.muted; font.pixelSize: 13 }
                        RowLayout {
                            Layout.fillWidth: true
                            Text { text: "↓"; color: root.accent; font.pixelSize: 20; font.weight: Font.Bold }
                            Text { Layout.fillWidth: true; text: backend.sessionDown; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                            Text { text: "↑"; color: root.violet; font.pixelSize: 20; font.weight: Font.Bold }
                            Text { Layout.fillWidth: true; text: backend.sessionUp; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        }
                        Rectangle { Layout.fillWidth: true; height: 1; color: root.slate }
                        Text { Layout.fillWidth: true; text: "За всё время"; color: root.muted; font.pixelSize: 13 }
                        RowLayout {
                            Layout.fillWidth: true
                            Text { text: "↓"; color: root.light; font.pixelSize: 20 }
                            Text { Layout.fillWidth: true; text: backend.totalDown; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                            Text { text: "↑"; color: root.light; font.pixelSize: 20 }
                            Text { Layout.fillWidth: true; text: backend.totalUp; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        }
                    }
                }
            }

            ScrollView {
                id: peopleScroll
                clip: true
                contentWidth: availableWidth
                ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                ScrollBar.vertical: DarkScrollBar {}
                Column {
                    width: peopleScroll.availableWidth - 40
                    x: (peopleScroll.availableWidth - width) / 2
                    topPadding: 18
                    bottomPadding: 28
                    spacing: 12
                    PageHeading { width: parent.width; title: "Люди"; subtitle: "Доступ к VPN" }
                    ActionCard {
                        width: parent.width
                        title: "Ввести код доступа"
                        subtitle: "Добавить готовый сервер, которым с тобой поделились"
                        onClicked: accessCodeDialog.open()
                    }
                    Text { width: parent.width; topPadding: 10; text: "Управление сервером"; color: root.pale; font.pixelSize: 18; font.weight: Font.Bold }
                    ActionCard {
                        width: parent.width
                        title: "Добавить человека"
                        subtitle: "Отдельный логин, срок, лимиты трафика и скорости"
                        onClicked: apiLayer.openCreatePerson()
                    }
                    Panel {
                        width: parent.width
                        visible: apiLayer.people.length === 0
                        Text { Layout.fillWidth: true; text: "Пока никого нет"; color: root.pale; font.pixelSize: 17; font.weight: Font.Bold }
                        Text {
                            Layout.fillWidth: true
                            text: "Добавь профиль администратора с root/sudo, чтобы управлять пользователями сервера."
                            color: root.muted
                            font.pixelSize: 13
                            wrapMode: Text.Wrap
                        }
                    }
                    Repeater {
                        width: parent.width
                        model: apiLayer.people
                        delegate: Panel {
                            required property var modelData
                            property bool expanded: false
                            width: peopleScroll.availableWidth - 40
                            Text { Layout.fillWidth: true; text: (modelData.label || modelData.login) + "  ·  " + modelData.login; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                            Text {
                                Layout.fillWidth: true
                                text: (modelData.expired ? "ИСТЁК" : (modelData.blocked ? "ЛИМИТ ИСЧЕРПАН" : "АКТИВЕН")) + " · " + (modelData.expires ? "до " + modelData.expires : "бессрочно")
                                      + "\nСегодня: " + backend.formatBytes(modelData.day_bytes || 0) + (modelData.daily_mb ? " / " + modelData.daily_mb + " МБ" : "")
                                      + " · месяц: " + backend.formatBytes(modelData.month_bytes || 0) + (modelData.monthly_mb ? " / " + modelData.monthly_mb + " МБ" : "")
                                      + "\nЛимиты: " + (modelData.daily_mb ? modelData.daily_mb + " МБ/день" : "∞") + " · " + (modelData.monthly_mb ? modelData.monthly_mb + " МБ/месяц" : "∞") + " · " + (modelData.speed_mbps ? modelData.speed_mbps + " Мбит/с" : "без ограничения")
                                color: root.muted
                                font.pixelSize: 12
                                wrapMode: Text.Wrap
                            }
                            AppButton { Layout.fillWidth: true; text: expanded ? "УПРАВЛЕНИЕ  ▲" : "УПРАВЛЕНИЕ  ▼"; onClicked: expanded = !expanded }
                            RowLayout {
                                Layout.fillWidth: true; visible: expanded; spacing: 8
                                AppButton { Layout.fillWidth: true; text: "СТАТУС И ТРАФИК"; onClicked: messageDialog.show("Статус и трафик", "Логин: " + modelData.login + "\nСтатус: " + (modelData.blocked ? "лимит исчерпан" : (modelData.expired ? "истёк" : "активен")) + "\nСегодня: " + backend.formatBytes(modelData.day_bytes || 0) + "\nМесяц: " + backend.formatBytes(modelData.month_bytes || 0)) }
                                AppButton { Layout.fillWidth: true; text: "КОД И QR"; onClicked: apiLayer.showAccessCode(modelData.access_code || "Код доступа отсутствует") }
                            }
                            RowLayout {
                                Layout.fillWidth: true; visible: expanded; spacing: 8
                                AppButton { Layout.fillWidth: true; text: "ПРОДЛИТЬ"; onClicked: apiLayer.openPerson(modelData) }
                                AppButton { Layout.fillWidth: true; danger: true; text: "ОТОЗВАТЬ"; onClicked: backend.revokePerson(modelData.login) }
                            }
                            RowLayout {
                                Layout.fillWidth: true; visible: expanded; spacing: 8
                                AppButton { Layout.fillWidth: true; text: "ИЗМЕНИТЬ ЛИМИТЫ"; onClicked: apiLayer.openPerson(modelData) }
                                AppButton { Layout.fillWidth: true; danger: true; text: "ОБНУЛИТЬ ТРАФИК"; onClicked: backend.resetPersonUsage(modelData.login) }
                            }
                        }
                    }
                    ActionCard {
                        width: parent.width
                        title: "Обновить список"
                        subtitle: "Получить актуальные данные с сервера"
                        onClicked: backend.loadPeople()
                    }
                }
            }

            ScrollView {
                id: settingsScroll
                clip: true
                contentWidth: availableWidth
                ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                ScrollBar.vertical: DarkScrollBar {}
                Column {
                    width: settingsScroll.availableWidth - 40
                    x: (settingsScroll.availableWidth - width) / 2
                    topPadding: 18
                    bottomPadding: 28
                    spacing: 10
                    PageHeading { width: parent.width; title: "Настройки"; subtitle: "Общие параметры приложения. Настройки конкретного сервера находятся у него под шестерёнкой." }

                    SectionHeading { text: "Подключение" }
                    SettingsToggleCard { width: parent.width; title: "Автопереподключение"; subtitle: "Восстанавливать туннель после смены Wi‑Fi или сети."; checked: backend.autoReconnect; onChanged: backend.setAutoReconnect(value) }
                    SettingsToggleCard { width: parent.width; title: "Резервный сервер"; subtitle: "После трёх неудачных подключений перейти к следующему сохранённому серверу с подтверждённым SSH-ключом."; checked: backend.autoFailover; onChanged: backend.setBooleanSetting("auto_server_failover", value) }
                    SettingsToggleCard { width: parent.width; title: "Запуск после перезагрузки"; subtitle: "Попытаться вернуть последнее активное подключение после запуска компьютера."; checked: backend.startOnBoot; onChanged: backend.setBooleanSetting("start_on_boot", value) }

                    SectionHeading { text: "Обновления" }
                    Panel {
                        width: parent.width
                        visible: backend.developerMode
                        Text { Layout.fillWidth: true; text: "Канал обновлений"; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: "Debug mode: можно получать тестовые сборки."; color: root.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                        AppButton { Layout.fillWidth: true; primary: !backend.betaUpdates; text: "СТАБИЛЬНЫЕ ВЕРСИИ"; onClicked: backend.setBooleanSetting("beta_updates", false) }
                        AppButton { Layout.fillWidth: true; primary: backend.betaUpdates; text: "BETA И СТАБИЛЬНЫЕ"; onClicked: backend.setBooleanSetting("beta_updates", true) }
                    }
                    ActionCard {
                        width: parent.width
                        title: "Проверить обновления сейчас"
                        subtitle: backend.developerMode ? "Проверка выбранного канала" : "Проверить наличие новой стабильной версии"
                        onClicked: backend.checkUpdates(backend.developerMode && backend.betaUpdates)
                    }

                    SectionHeading { text: "Конфиги и инструменты" }
                    ActionCard { width: parent.width; title: "Импортировать конфиг"; subtitle: "Добавить сервер из готового файла"; onClicked: apiLayer.openConfigImport() }
                    ActionCard { width: parent.width; title: "Экспортировать активный сервер"; subtitle: "Создать файл и поделиться им"; onClicked: backend.exportConfig() }
                    ActionCard { width: parent.width; title: "Настроить Telegram"; subtitle: "Передать Telegram ссылку на локальный SOCKS5"; onClicked: backend.openTelegramProxy() }
                    ActionCard { width: parent.width; title: "Проверить скорость"; subtitle: "Загрузка, выгрузка и пинг через активный туннель"; onClicked: backend.runSpeedTest() }

                    SectionHeading { text: "Обслуживание" }
                    ActionCard { width: parent.width; title: "Сбросить временное состояние"; subtitle: "Не удаляет серверы, пароли, настройки и статистику"; onClicked: backend.stopConnection() }
                }
            }
            ScrollView {
                id: addScroll
                clip: true
                contentWidth: availableWidth
                ScrollBar.horizontal.policy: ScrollBar.AlwaysOff
                ScrollBar.vertical: DarkScrollBar {}
                Column {
                    width: addScroll.availableWidth - 40
                    x: (addScroll.availableWidth - width) / 2
                    topPadding: 18
                    bottomPadding: 28
                    spacing: 12
                    PageHeading { width: parent.width; title: "Добавить сервер"; subtitle: "Выбери, как ты хочешь подключиться." }
                    ActionCard {
                        width: parent.width
                        title: "Бесплатные серверы"
                        subtitle: "Получить отдельный доступ с личными лимитами из публичного каталога"
                        onClicked: apiLayer.openPublicServers()
                    }
                    ActionCard {
                        width: parent.width
                        title: "Настроить свой сервер"
                        subtitle: "Ввести IP, SSH-пользователя, пароль и параметры сервера"
                        onClicked: root.openNewServerEditor()
                    }
                    ActionCard {
                        width: parent.width
                        title: "Добавить по коду"
                        subtitle: "Вставить текстовый код, который создал владелец VPN-сервера"
                        onClicked: accessCodeDialog.open()
                    }

                }
            }
        }
    }

    Rectangle {
        id: bottomBar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 66
        color: root.midnight
        border.width: 1
        border.color: root.slate
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 8
            anchors.rightMargin: 8
            spacing: 2
            Repeater {
                model: [
                    {"label": "Главная", "symbol": "", "path": "M12,3.2L3,10.5V21h6.2v-6.2h5.6V21H21V10.5L12,3.2zM19,19h-2.2v-6.2H7.2V19H5v-7.5l7,-5.7 7,5.7V19z"},
                    {"label": "Люди", "symbol": "", "path": "M16,11c1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3c-0.32,0 -0.63,0.05 -0.91,0.14C15.66,5.94 16,6.93 16,8s-0.34,2.06 -0.91,2.86c0.28,0.09 0.59,0.14 0.91,0.14zM8,11c1.66,0 3,-1.34 3,-3S9.66,5 8,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5C15,14.17 10.33,13 8,13zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05C16.19,13.89 17,15.02 17,16.5V19h6v-2.5C23,14.17 18.33,13 16,13z"},
                    {"label": "Настройки", "symbol": "", "path": "M19.4,13a7.5,7.5 0,0 0,0.1 -1,7.5 7.5,0 0,0 -0.1,-1l2.1,-1.7 -2,-3.4 -2.5,1a7.8,7.8 0,0 0,-1.7 -1L15,3.2h-4L10.6,6a7.8,7.8 0,0 0,-1.7,1L6.4,6l-2,3.4L6.6,11a7.5,7.5 0,0 0,-0.1,1 7.5,7.5 0,0 0,0.1 1l-2.1,1.7 2,3.4 2.5,-1a7.8,7.8 0,0 0,1.7 1l0.4,2.7h4l0.4,-2.7a7.8,7.8 0,0 0,1.7 -1l2.5,1 2,-3.4L19.4,13zM13,16a4,4 0,1 1,0 -8,4 4,0 0,1 0,8zM13,14a2,2 0,1 0,0 -4,2 2,0 0,0 0,4z"},
                    {"label": "Сервер", "symbol": "", "path": "M11,5h2v6h6v2h-6v6h-2v-6H5v-2h6z"}
                ]
                Item {
                    required property var modelData
                    required property int index
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Rectangle {
                        anchors.fill: parent
                        anchors.margins: 5
                        radius: 13
                        color: navMouse.pressed || (navMouse.containsMouse && root.currentPage !== index) ? root.onyx : "transparent"
                    }
                    Column {
                        anchors.centerIn: parent
                        spacing: 2
                        Image {
                            id: navIcon
                            anchors.horizontalCenter: parent.horizontalCenter
                            width: 28
                            height: 28
                            sourceSize.width: 28
                            sourceSize.height: 28
                            source: "data:image/svg+xml;utf8," + encodeURIComponent(
                                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>" +
                                "<path fill='" + (root.currentPage === index ? root.accent : root.muted) +
                                "' d='" + modelData.path + "'/></svg>")
                            smooth: true
                            visible: modelData.path !== ""
                        }
                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            width: 28; height: 28
                            visible: modelData.symbol !== ""
                            text: modelData.symbol
                            color: root.currentPage === index ? root.accent : root.light
                            font.pixelSize: modelData.symbol === "+" ? 34 : 28
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            text: modelData.label
                            color: root.currentPage === index ? root.accent : root.light
                            font.pixelSize: 11
                            font.weight: root.currentPage === index ? Font.DemiBold : Font.Normal
                        }
                    }
                    MouseArea {
                        id: navMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: { serverDialog.close(); apiLayer.closePage(); root.currentPage = index }
                    }
                }
            }
        }
    }
    ApiLayer2 {
        id: apiLayer
        anchors.fill: parent
        controller: backend
    }


    Popup {
        id: messageDialog
        objectName: "messageDialog"
        parent: Overlay.overlay
        property string dialogTitle: backend.appName
        property string bodyText: ""
        function show(titleText, text) { dialogTitle = titleText; bodyText = text; open() }
        anchors.centerIn: parent
        width: Math.min(root.width - 28, 352)
        modal: true
        padding: 18
        background: Rectangle { color: root.onyx; radius: 18; border.width: 1; border.color: root.slate }
        contentItem: ColumnLayout {
            spacing: 12
            Text { Layout.fillWidth: true; text: messageDialog.dialogTitle; color: root.pale; font.pixelSize: 20; font.weight: Font.Bold; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: messageDialog.bodyText; color: root.light; font.pixelSize: 14; wrapMode: Text.Wrap }
            AppButton { Layout.fillWidth: true; primary: true; text: "OK"; onClicked: messageDialog.close() }
        }
    }

    Popup {
        id: hostKeyDialog
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(root.width - 28, 352)
        modal: true
        closePolicy: Popup.NoAutoClose
        padding: 18
        background: Rectangle { color: root.onyx; radius: 18; border.width: 1; border.color: root.slate }
        contentItem: ColumnLayout {
            spacing: 12
            Text { Layout.fillWidth: true; text: "Новый SSH host key"; color: root.pale; font.pixelSize: 20; font.weight: Font.Bold }
            Text {
                Layout.fillWidth: true
                text: "Сервер: " + root.pendingHost + "\nТип ключа: " + root.pendingKeyType + "\nОтпечаток: " + root.pendingFingerprint + "\n\nСверь отпечаток с владельцем сервера. Доверять этому ключу?"
                color: root.light; font.pixelSize: 13; wrapMode: Text.WrapAnywhere
            }
            RowLayout {
                Layout.fillWidth: true
                AppButton { Layout.fillWidth: true; text: "НЕТ"; onClicked: { backend.answerHostKey(false); hostKeyDialog.close() } }
                AppButton { Layout.fillWidth: true; primary: true; text: "ДОВЕРЯТЬ"; onClicked: { backend.answerHostKey(true); hostKeyDialog.close() } }
            }
        }
    }

    Popup {
        id: accessCodeDialog
        objectName: "accessCodeDialog"
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(root.width - 28, 352)
        modal: true
        padding: 18
        background: Rectangle { color: root.onyx; radius: 18; border.width: 1; border.color: root.slate }
        contentItem: ColumnLayout {
            spacing: 10
            Text { Layout.fillWidth: true; text: "Код доступа"; color: root.pale; font.pixelSize: 20; font.weight: Font.Bold }
            Text { Layout.fillWidth: true; text: "Вставь код от владельца сервера."; color: root.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
            StyledField { id: accessCodeField; Layout.fillWidth: true; placeholderText: "PEL1-…" }
            RowLayout {
                Layout.fillWidth: true
                AppButton { Layout.fillWidth: true; text: "ОТМЕНА"; onClicked: accessCodeDialog.close() }
                AppButton { Layout.fillWidth: true; primary: true; text: "ДОБАВИТЬ"; onClicked: { backend.importAccessCode(accessCodeField.text); accessCodeDialog.close() } }
            }
        }
    }

    Popup {
        id: deleteServerDialog
        parent: Overlay.overlay
        anchors.centerIn: parent
        width: Math.min(root.width - 28, 352)
        modal: true
        padding: 18
        background: Rectangle { color: root.onyx; radius: 18; border.width: 1; border.color: root.slate }
        contentItem: ColumnLayout {
            spacing: 12
            Text { Layout.fillWidth: true; text: "Удалить сервер?"; color: root.pale; font.pixelSize: 20; font.weight: Font.Bold }
            Text { Layout.fillWidth: true; text: "Будут удалены пароль, TLS-ключи и параметры этого профиля. Сам VPS и его данные не изменятся."; color: root.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
            RowLayout {
                Layout.fillWidth: true
                AppButton { Layout.fillWidth: true; text: "ОТМЕНА"; onClicked: deleteServerDialog.close() }
                AppButton { Layout.fillWidth: true; danger: true; text: "УДАЛИТЬ"; onClicked: { deleteServerDialog.close(); backend.deleteProfile(root.editingProfileId); serverDialog.close(); apiLayer.openProfiles() } }
            }
        }
    }
    Rectangle {
        id: serverDialog
        objectName: "serverDialog"
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: bottomBar.top
        color: root.midnight
        visible: false
        z: 190
        function open() { visible = true }
        function close() { visible = false }
        ColumnLayout {
            anchors.fill: parent
            anchors.leftMargin: 20
            anchors.rightMargin: 20
            anchors.topMargin: 18
            anchors.bottomMargin: 12
            spacing: 10
            Text { Layout.fillWidth: true; text: root.editingProfileId === "" ? "Новый сервер" : nameField.text; color: root.pale; font.pixelSize: 28; font.weight: Font.Bold; elide: Text.ElideRight }
            Text { Layout.fillWidth: true; text: root.editingProfileId === "" ? "Добавь данные SSH-сервера. Все параметры можно изменить позже." : "Настройки этого сервера. Они не влияют на остальные профили."; color: root.muted; font.pixelSize: 14; wrapMode: Text.Wrap }
            ScrollView {
                Layout.fillWidth: true; Layout.fillHeight: true
                ScrollBar.vertical: DarkScrollBar {}
                clip: true; contentWidth: availableWidth
                Column {
                    width: parent.width; spacing: 9; bottomPadding: 20
                    SectionHeading { text: "Основное" }
                    Text { width: parent.width; text: "Название на этом компьютере"; color: root.muted; font.pixelSize: 12 }
                    StyledField { id: nameField; width: parent.width; placeholderText: "Мой сервер" }
                    Text { width: parent.width; text: "IP-адрес или домен"; color: root.muted; font.pixelSize: 12 }
                    StyledField { id: hostField; width: parent.width; placeholderText: "vpn.example.com" }
                    SectionHeading { text: "Порты" }
                    Row {
                        width: parent.width; spacing: 10
                        Column { width: (parent.width - 10) / 2; spacing: 6
                            Text { text: "SSH-порт"; color: root.muted; font.pixelSize: 12 }
                            StyledField { id: portField; width: parent.width; placeholderText: "22"; inputMethodHints: Qt.ImhDigitsOnly }
                        }
                        Column { width: (parent.width - 10) / 2; spacing: 6
                            Text { text: "SOCKS5-порт"; color: root.muted; font.pixelSize: 12 }
                            StyledField { id: socksField; width: parent.width; placeholderText: "1080"; inputMethodHints: Qt.ImhDigitsOnly }
                        }
                    }
                    Text { width: parent.width; text: "Пользователь SSH"; color: root.muted; font.pixelSize: 12 }
                    StyledField { id: userField; width: parent.width; placeholderText: "root" }
                    Text { width: parent.width; text: "Пароль SSH"; color: root.muted; font.pixelSize: 12 }
                    StyledField { id: passwordField; width: parent.width; echoMode: TextInput.Password; placeholderText: "••••••••" }
                    SectionHeading { text: "Производительность" }
                    Panel {
                        width: parent.width
                        Text { Layout.fillWidth: true; text: "Профиль соединения"; color: root.pale; font.pixelSize: 16; font.weight: Font.Bold }
                        Text { Layout.fillWidth: true; text: "Выбери готовый вариант. Точные значения ниже нужны только для диагностики."; color: root.muted; font.pixelSize: 13; wrapMode: Text.Wrap }
                    }
                    Row {
                        width: parent.width; spacing: 8
                        Column { width: (parent.width - 16) / 3; spacing: 5; Text { text: "Окно, КиБ"; color: root.muted; font.pixelSize: 10 } StyledField { id: windowField; width: parent.width; inputMethodHints: Qt.ImhDigitsOnly } }
                        Column { width: (parent.width - 16) / 3; spacing: 5; Text { text: "Пакет, КиБ"; color: root.muted; font.pixelSize: 10 } StyledField { id: packetField; width: parent.width; inputMethodHints: Qt.ImhDigitsOnly } }
                        Column { width: (parent.width - 16) / 3; spacing: 5; Text { text: "MTU"; color: root.muted; font.pixelSize: 10 } StyledField { id: mtuField; width: parent.width; inputMethodHints: Qt.ImhDigitsOnly } }
                    }
                    Row {
                        width: parent.width; spacing: 7
                        AppButton { width: (parent.width - 14) / 3; text: "СОВМЕСТ."; onClicked: { windowField.text = "512"; packetField.text = "32"; mtuField.text = "1500" } }
                        AppButton { width: (parent.width - 14) / 3; text: "БАЛАНС"; onClicked: { windowField.text = "4096"; packetField.text = "32"; mtuField.text = "8500" } }
                        AppButton { width: (parent.width - 14) / 3; text: "СКОРОСТЬ"; onClicked: { windowField.text = "4096"; packetField.text = "64"; mtuField.text = "8500" } }
                    }
                    SectionHeading { visible: root.editingProfileId !== ""; text: "Защита сервера" }
                    ActionCard { width: parent.width; visible: root.editingProfileId !== ""; title: "TLS-обёртка"; subtitle: "Настроить, включить или полностью удалить TLS на этом сервере"; onClicked: { serverDialog.close(); apiLayer.openTls() } }
                    ActionCard { width: parent.width; visible: root.editingProfileId !== ""; title: "Проверить SSH-ключ"; subtitle: "Показать текущий fingerprint и безопасно заменить закреплённый ключ после переустановки VPS"; onClicked: backend.checkServerKey() }
                    ActionCard { width: parent.width; visible: root.editingProfileId !== ""; title: "Перенести на новый сервер"; subtitle: "Скопировать пользователей и настройки, затем заменить адрес этого профиля"; onClicked: { serverDialog.close(); apiLayer.openMigration() } }
                    ActionCard { width: parent.width; visible: root.editingProfileId !== ""; title: "Сделать сервер публичным"; subtitle: "Создать безопасную выдачу отдельных аккаунтов и лимитов для бесплатного каталога"; onClicked: { serverDialog.close(); apiLayer.openPublish() } }
                    Text { width: parent.width; text: "Пароль хранится через Windows DPAPI. Новый SSH host key всегда требует подтверждения."; color: root.muted; font.pixelSize: 11; wrapMode: Text.Wrap }
                    AppButton {
                        width: parent.width; primary: true; text: root.editingProfileId === "" ? "ДОБАВИТЬ СЕРВЕР" : "СОХРАНИТЬ"
                        onClicked: {
                            backend.saveProfile(nameField.text, hostField.text, portField.text, userField.text, passwordField.text, socksField.text, root.editingProfileId)
                            backend.saveTuning(Number(windowField.text), Number(packetField.text), Number(mtuField.text))
                            serverDialog.close(); apiLayer.openProfiles()
                        }
                    }
                    AppButton { width: parent.width; visible: root.editingProfileId !== "" && JSON.parse(backend.profilesJson || "[]").length > 1; danger: true; text: "УДАЛИТЬ СЕРВЕР"; onClicked: deleteServerDialog.open() }
                }
            }
        }
    }
    function openServerEditor() {
        root.currentPage = 3
        const profile = backend.profile()
        root.editingProfileId = profile.id || ""
        nameField.text = profile.name || "Мой сервер"
        hostField.text = profile.host || ""
        portField.text = String(profile.port || 22)
        userField.text = profile.username || "root"
        passwordField.text = profile.password || ""
        socksField.text = String(profile.socks_port || 1080)
        windowField.text = String(profile.window_kib || 4096)
        packetField.text = String(profile.packet_kib || 32)
        mtuField.text = String(profile.mtu || 8500)
        serverDialog.open()
    }

    function openNewServerEditor() {
        root.currentPage = 3
        root.editingProfileId = ""
        nameField.text = ""
        hostField.text = ""
        portField.text = "22"
        userField.text = "root"
        passwordField.text = ""
        socksField.text = "1080"
        windowField.text = "4096"
        packetField.text = "32"
        mtuField.text = "8500"
        serverDialog.open()
    }
    Connections {
        target: backend
        function onHostKeyRequested(host, keyType, fingerprint) {
            root.pendingHost = host
            root.pendingKeyType = keyType
            root.pendingFingerprint = fingerprint
            hostKeyDialog.open()
        }
        function onMessageRequested(title, text) { messageDialog.show(title, text) }
        function onOpenServerEditorRequested() { root.openServerEditor() }
    }
}
