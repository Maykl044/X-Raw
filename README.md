# X-Rav · Windows-клиент Xray

WPF-клиент Xray под Windows 10/11 с автоматическим TUN-режимом через `hev-socks5-tunnel` + Wintun.
Идея — «портативный VPN из одного `.exe`», без установки.

> Статус: бета. Работает только под Windows. На Linux собирается ради CI и проверок.

## Возможности

- Импорт ключей `vless://`, `vmess://`, `trojan://`, `ss://` (включая base64‑варианты), а также готовых `config.json` (Xray) — поле «Серверы → Из буфера».
- Импорт подписки (`https://…/subscribe`) — тело может быть base64 со списком ссылок или plain.
- Запуск `xray.exe` в режиме SOCKS5 на `127.0.0.1:10808`.
- Подъём системного TUN-интерфейса (`hev-socks5-tunnel` + Wintun) — заворачивает весь трафик через прокси.
- Авто-подгрузка бинарников `xray.exe`, `hev-socks5-tunnel.exe`, `msys-2.0.dll`, `wintun.dll`, `geoip.dat`, `geosite.dat` с GitHub-релизов (Xray-core, hev-socks5-tunnel, v2fly/geoip, v2fly/domain-list-community).
- Сохранение состояния в `%APPDATA%\X-Rav\user.json`, лог в `%APPDATA%\X-Rav\logs\xrav-YYYYMMDD.log`.

## Запуск (готовый `.exe`)

1. Распакуйте поставляемый `.zip`.
2. **ПКМ по `X-Rav.exe` → «Запуск от имени администратора»** (Wintun/TUN иначе не поднимется; манифест уже требует этого).
3. Откройте «Настройки → Бинарники» → «Подготовить» — приложение скачает в `%APPDATA%\X-Rav\tools` всё необходимое.  
   Альтернатива: распакуйте сюда вручную содержимое `Xray-windows-64.zip` (XTLS/Xray-core) и `hev-socks5-tunnel-win64.zip` (heiher/hev-socks5-tunnel).
4. На вкладке «Серверы» добавьте ключ (в поле или «Из буфера обмена»).
5. На вкладке «Подписка» добавьте URL подписки и нажмите «Обновить» — список ключей подтянется.
6. Жмёте круглую кнопку питания на главном экране.

## Сборка

### Требования
- .NET 8 SDK
- Windows 10/11 (для запуска), но **сборка** работает и на Linux: укажите `EnableWindowsTargeting=true`.

### Команды

```powershell
# debug build
dotnet build -c Debug

# self-contained .exe (single-file, ~160 МБ)
dotnet publish src/Xrav.Desktop/Xrav.Desktop.csproj `
    -c Release -r win-x64 --self-contained true `
    /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true `
    -o publish/win-x64
```

На Linux добавьте `/p:EnableWindowsTargeting=true`.

## Структура

```
src/
  Xrav.Core/          # cross-platform домен: модели, парсер ссылок, билдер xray-config, fetch подписки
  Xrav.Desktop/       # WPF-приложение (Windows-only по таргету net8.0-windows)
    Tools/            # ToolBootstrapper — скачивание xray-core/hev/wintun
    Services/         # WinTunnelService — оркестрация процессов
    Tunnel/           # YAML для hev-socks5-tunnel + константы
    Xray/             # ImportedXrayJsonPatcher + VpnKeyXrayConfig
    Logging/          # FileLogger
    Ui/               # XAML вьюшки и конвертеры
    ViewModels/
    Storage/
.github/workflows/
  windows-build.yml   # CI: dotnet publish на windows-latest
```

## Где у пользователя данные

| что | путь |
|---|---|
| `user.json` | `%APPDATA%\X-Rav\user.json` |
| Бинарники | `%APPDATA%\X-Rav\tools\` |
| Geo-база | `%APPDATA%\X-Rav\xray_assets\` |
| Сгенерированные конфиги | `%APPDATA%\X-Rav\runtime\` |
| Логи | `%APPDATA%\X-Rav\logs\` |

## Лицензии

- xray-core — Mozilla Public License 2.0 (XTLS).
- hev-socks5-tunnel — MIT (heiher).
- Wintun — GPLv2 (WireGuard LLC) — поставляется вместе с релизом xray-core / hev.
- Сам X-Rav — MIT (см. `LICENSE`).
