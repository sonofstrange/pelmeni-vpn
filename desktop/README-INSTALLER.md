# Установщик Пельмени VPN Desktop 1.32

## Сборка

```powershell
powershell -ExecutionPolicy Bypass -File .\build-qt-final.ps1
```

Скрипт создаёт:

- `dist\PelmeniVPN-Desktop.exe` — portable-приложение;
- `dist\PelmeniVPN-TunHelper.exe` — системный VPN helper с Wintun/tun2socks;
- `dist\PelmeniVPN-Desktop-Setup-1.32.exe` — стандартный Inno Setup-установщик.

## Поведение установщика

- использует обычный мастер установки Windows на русском языке;
- устанавливает приложение в `C:\Program Files\PelmeniVPN Desktop`;
- создаёт ярлык в меню «Пуск» и опциональный ярлык на рабочем столе;
- регистрирует штатное удаление в списке приложений Windows;
- обновляет прежнюю beta.7 и удаляет старый самодельный деинсталлятор;
- сохраняет профили в `%APPDATA%\PelmeniVPN Desktop`;
- перед удалением восстанавливает системные прокси-настройки.

Приложение и установщик пока не подписаны издательским code-signing сертификатом.
