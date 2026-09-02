# Совместимость с Android API

Desktop 1.32 использует те же форматы и серверные операции, что Android-клиент.

## Перенесено

- импорт кода доступа `PEL1-` с теми же полями и проверками;
- безопасный JSON format 2 без пароля и импорт format 1/2;
- несколько SSH-профилей, активация и удаление;
- GitHub-каталог `public-server` и выдача личного доступа;
- проверка опубликованного SSH host key до авторизации регистратора;
- управление людьми: список, создание, продление, лимиты, сброс трафика и отзыв;
- серверные worker/policy-скрипты извлекаются из Android `ServerAccessManager.java`;
- получение PKCS#12 и пароля через SFTP с защитой Windows DPAPI;
- проверка GitHub Releases, тест скорости и ссылка Telegram;
- настройки SSH window, packet и MTU.

## Отличия платформы

- Android использует `VpnService`; Windows использует отдельный Wintun/tun2socks helper;
- на ПК доступна вставка PEL1-кода без отдельной QR-кнопки;
- Android Quick Settings и BootReceiver неприменимы в Windows;
- установка APK заменена открытием подходящего GitHub Release.

Ни один экспортируемый файл не содержит SSH-пароль. Пароли профилей хранятся
через DPAPI в `%APPDATA%\PelmeniVPN Desktop`.
