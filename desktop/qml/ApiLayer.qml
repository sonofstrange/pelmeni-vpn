import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: api
    property var controller
    property var publicServers: []
    property var people: []
    property var profiles: []
    property var selectedPerson: ({})
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
        implicitHeight: 46
        color: api.pale
        placeholderTextColor: api.muted
        selectedTextColor: api.midnight
        selectionColor: api.accent
        leftPadding: 14
        rightPadding: 14
        background: Rectangle {
            radius: 14
            color: api.midnight
            border.width: 1
            border.color: parent.activeFocus ? api.accent : api.slate
        }
    }

    component ApiButton: Button {
        id: control
        property bool danger: false
        implicitHeight: 44
        background: Rectangle {
            radius: 14
            color: control.down ? api.slate : api.onyx
            border.width: 1
            border.color: control.danger ? api.red : api.slate
        }
        contentItem: Text {
            text: control.text
            color: control.danger ? api.red : api.pale
            font.pixelSize: 12
            font.weight: Font.DemiBold
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
    }

    function openPublicServers() {
        controller.loadPublicServers()
        publicDialog.open()
    }
    function openCreatePerson() { createPersonDialog.open() }
    function openProfiles() {
        profiles = JSON.parse(controller.profilesJson || "[]")
        profilesDialog.open()
    }
    function openConfigImport() { configImportDialog.open() }
    function openPerson(person) {
        selectedPerson = person
        personLogin.text = person.login || ""
        personDaily.text = String(person.daily_mb || 0)
        personMonthly.text = String(person.monthly_mb || 0)
        personSpeed.text = String(person.speed_mbps || 0)
        personDays.text = "30"
        personDialog.open()
    }
    function showAccessCode(code) {
        accessCodeOutput.text = code
        accessCodeOutputDialog.open()
    }

    Rectangle {
        anchors.fill: parent
        visible: controller.actionBusy
        color: "#990E0E11"
        MouseArea { anchors.fill: parent }
        Column {
            anchors.centerIn: parent
            spacing: 14
            BusyIndicator { anchors.horizontalCenter: parent.horizontalCenter; running: parent.visible }
            Text { text: "Выполняем операцию…"; color: api.pale; font.pixelSize: 14 }
        }
    }

    Dialog {
        id: publicDialog
        anchors.centerIn: parent
        width: 540
        height: 660
        modal: true
        title: "Бесплатные серверы"
        standardButtons: Dialog.Close
        background: Rectangle { color: api.midnight; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout {
            spacing: 10
            Text {
                Layout.fillWidth: true
                text: api.publicServers.length ? "Доступные серверы: " + api.publicServers.length : "Каталог пуст или ещё загружается…"
                color: api.muted
                font.pixelSize: 13
                wrapMode: Text.Wrap
            }
            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 10
                model: api.publicServers
                delegate: Rectangle {
                    required property var modelData
                    required property int index
                    width: ListView.view.width
                    height: 154
                    radius: 16
                    color: api.onyx
                    border.width: 1
                    border.color: api.slate
                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 14
                        spacing: 5
                        Text { Layout.fillWidth: true; text: modelData.name || modelData.host; color: api.pale; font.pixelSize: 17; font.weight: Font.Bold; elide: Text.ElideRight }
                        Text { Layout.fillWidth: true; text: (modelData.location || "Регион не указан") + " · " + modelData.host; color: api.muted; font.pixelSize: 12; elide: Text.ElideRight }
                        Text {
                            Layout.fillWidth: true
                            text: "День: " + (modelData.daily_mb || "∞") + " МБ · месяц: " + (modelData.monthly_mb || "∞") + " МБ\nСкорость: " + (modelData.speed_mbps || "∞") + " Мбит/с · срок: " + (modelData.days || "∞") + " дн."
                            color: api.muted
                            font.pixelSize: 11
                            wrapMode: Text.Wrap
                        }
                        ApiButton { Layout.fillWidth: true; text: "ПОЛУЧИТЬ ДОСТУП"; onClicked: controller.claimPublicServer(index) }
                    }
                }
            }
            ApiButton { Layout.fillWidth: true; text: "ОБНОВИТЬ КАТАЛОГ"; onClicked: controller.loadPublicServers() }
        }
    }

    Dialog {
        id: createPersonDialog
        anchors.centerIn: parent
        width: 500
        modal: true
        title: "Новый пользователь"
        standardButtons: Dialog.Save | Dialog.Cancel
        onAccepted: controller.createPerson(
            personLabel.text, personNewLogin.text, Number(personNewDays.text),
            Number(personNewDaily.text), Number(personNewMonthly.text), Number(personNewSpeed.text))
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: GridLayout {
            columns: 2
            columnSpacing: 10
            rowSpacing: 8
            Text { text: "Имя"; color: api.muted }
            Field { id: personLabel; Layout.fillWidth: true; placeholderText: "Иван" }
            Text { text: "Логин"; color: api.muted }
            Field { id: personNewLogin; Layout.fillWidth: true; placeholderText: "ivan" }
            Text { text: "Срок, дней"; color: api.muted }
            Field { id: personNewDays; Layout.fillWidth: true; text: "30"; inputMethodHints: Qt.ImhDigitsOnly }
            Text { text: "МБ в день"; color: api.muted }
            Field { id: personNewDaily; Layout.fillWidth: true; text: "0"; inputMethodHints: Qt.ImhDigitsOnly }
            Text { text: "МБ в месяц"; color: api.muted }
            Field { id: personNewMonthly; Layout.fillWidth: true; text: "0"; inputMethodHints: Qt.ImhDigitsOnly }
            Text { text: "Мбит/с"; color: api.muted }
            Field { id: personNewSpeed; Layout.fillWidth: true; text: "0"; inputMethodHints: Qt.ImhDigitsOnly }
        }
    }

    Dialog {
        id: personDialog
        anchors.centerIn: parent
        width: 510
        modal: true
        title: selectedPerson.label || selectedPerson.login || "Пользователь"
        standardButtons: Dialog.Close
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout {
            spacing: 9
            Field { id: personLogin; Layout.fillWidth: true; readOnly: true }
            RowLayout {
                Layout.fillWidth: true
                Text { text: "Продлить, дней"; color: api.muted }
                Field { id: personDays; Layout.fillWidth: true; inputMethodHints: Qt.ImhDigitsOnly }
                ApiButton { text: "ПРОДЛИТЬ"; onClicked: controller.extendPerson(personLogin.text, Number(personDays.text)) }
            }
            RowLayout {
                Layout.fillWidth: true
                Field { id: personDaily; Layout.fillWidth: true; placeholderText: "МБ/день"; inputMethodHints: Qt.ImhDigitsOnly }
                Field { id: personMonthly; Layout.fillWidth: true; placeholderText: "МБ/месяц"; inputMethodHints: Qt.ImhDigitsOnly }
                Field { id: personSpeed; Layout.fillWidth: true; placeholderText: "Мбит/с"; inputMethodHints: Qt.ImhDigitsOnly }
            }
            ApiButton { Layout.fillWidth: true; text: "СОХРАНИТЬ ЛИМИТЫ"; onClicked: controller.updatePersonLimits(personLogin.text, Number(personDaily.text), Number(personMonthly.text), Number(personSpeed.text)) }
            ApiButton { Layout.fillWidth: true; text: "ОБНУЛИТЬ ТРАФИК"; onClicked: controller.resetPersonUsage(personLogin.text) }
            ApiButton { Layout.fillWidth: true; text: "ОТОЗВАТЬ ДОСТУП"; danger: true; onClicked: controller.revokePerson(personLogin.text) }
        }
    }

    Dialog {
        id: profilesDialog
        anchors.centerIn: parent
        width: 520
        height: 590
        modal: true
        title: "Серверы"
        standardButtons: Dialog.Close
        background: Rectangle { color: api.midnight; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ListView {
            clip: true
            spacing: 10
            model: api.profiles
            delegate: Rectangle {
                required property var modelData
                width: ListView.view.width
                height: 116
                radius: 16
                color: api.onyx
                border.width: modelData.active ? 2 : 1
                border.color: modelData.active ? api.accent : api.slate
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 13
                    Text { Layout.fillWidth: true; text: modelData.name; color: api.pale; font.pixelSize: 16; font.weight: Font.Bold }
                    Text { Layout.fillWidth: true; text: modelData.host + ":" + modelData.port; color: api.muted; font.pixelSize: 12 }
                    RowLayout {
                        Layout.fillWidth: true
                        ApiButton { Layout.fillWidth: true; text: modelData.active ? "АКТИВЕН" : "ВЫБРАТЬ"; enabled: !modelData.active; onClicked: controller.activateProfile(modelData.id) }
                        ApiButton { Layout.fillWidth: true; text: "УДАЛИТЬ"; danger: true; onClicked: controller.deleteProfile(modelData.id) }
                    }
                }
            }
        }
    }

    Dialog {
        id: configImportDialog
        anchors.centerIn: parent
        width: 500
        modal: true
        title: "Импорт конфигурации"
        standardButtons: Dialog.Open | Dialog.Cancel
        onAccepted: controller.importConfigFile(configPath.text)
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout {
            spacing: 8
            Text { Layout.fillWidth: true; text: "Укажи путь к JSON-файлу. Размер ограничен 64 КБ."; color: api.muted; wrapMode: Text.Wrap }
            Field { id: configPath; Layout.fillWidth: true; placeholderText: "C:\\Users\\...\\pelmeni-vpn-config.json" }
        }
    }

    Dialog {
        id: accessCodeOutputDialog
        anchors.centerIn: parent
        width: 520
        modal: true
        title: "Код доступа"
        standardButtons: Dialog.Close
        background: Rectangle { color: api.onyx; radius: 18; border.width: 1; border.color: api.slate }
        contentItem: ColumnLayout {
            spacing: 10
            TextArea { id: accessCodeOutput; Layout.fillWidth: true; Layout.preferredHeight: 150; readOnly: true; wrapMode: Text.WrapAnywhere; color: api.pale; background: Rectangle { color: api.midnight; radius: 12; border.width: 1; border.color: api.slate } }
            ApiButton { Layout.fillWidth: true; text: "КОПИРОВАТЬ"; onClicked: controller.copyText(accessCodeOutput.text) }
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
