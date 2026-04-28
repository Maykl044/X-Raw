using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;

namespace Xrav.Desktop.Localization;

/// <summary>
/// Лёгкий i18n-сервис: словарь строк на язык + смена языка в рантайме без перезапуска.
/// Используется через индексер из XAML: <c>{Binding [Connect], Source={x:Static loc:LocalizationService.Current}}</c>.
/// </summary>
public sealed class LocalizationService : INotifyPropertyChanged
{
    public static LocalizationService Current { get; } = new();

    public enum Lang { Ru, En, Tr }

    private Lang _lang = Lang.Ru;
    private IReadOnlyDictionary<string, string> _strings = LangRu();

    public Lang Language
    {
        get => _lang;
        set
        {
            if (_lang == value) return;
            _lang = value;
            _strings = value switch
            {
                Lang.En => LangEn(),
                Lang.Tr => LangTr(),
                _ => LangRu()
            };
            OnPropertyChanged(nameof(Language));
            OnPropertyChanged("Item[]"); // обновит все binding'и через индексер
            OnPropertyChanged(nameof(LanguageDisplay));
        }
    }

    public string LanguageDisplay => Language switch
    {
        Lang.En => "English",
        Lang.Tr => "Türkçe",
        _ => "Русский"
    };

    /// <summary>Индексер для binding'ов из XAML.</summary>
    public string this[string key] => _strings.TryGetValue(key, out var s) ? s : key;

    public static Lang DetectFromCulture()
    {
        var name = CultureInfo.InstalledUICulture.TwoLetterISOLanguageName;
        return name switch
        {
            "tr" => Lang.Tr,
            "ru" or "uk" or "be" or "kk" or "ky" or "uz" or "tg" or "az" => Lang.Ru,
            _ => Lang.En
        };
    }

    public static string LangCode(Lang l) => l switch { Lang.En => "en", Lang.Tr => "tr", _ => "ru" };
    public static Lang ParseCode(string? code) => code?.ToLowerInvariant() switch
    {
        "en" => Lang.En, "tr" => Lang.Tr, _ => Lang.Ru
    };

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name ?? string.Empty));

    private static Dictionary<string, string> LangRu() => new()
    {
        ["app.title"] = "X-Rav",
        ["nav.servers"] = "Серверы",
        ["nav.subscription"] = "Подписка",
        ["nav.log"] = "Лог",
        ["nav.settings"] = "Настройки",

        ["state.connected"] = "Подключено",
        ["state.connecting"] = "Подключение",
        ["state.reconnecting"] = "Переподключение",
        ["state.disconnected"] = "Отключено",
        ["state.error"] = "Ошибка",

        ["power.connect"] = "Нажмите для подключения",
        ["power.disconnect"] = "Нажмите для отключения",
        ["power.connecting"] = "Подключение…",
        ["power.reconnecting"] = "Переподключение…",
        ["power.error"] = "Ошибка — попробуйте ещё раз",
        ["power.auto"] = "Авто-режим: при сбое выберу другой ключ",

        ["servers.title"] = "Серверы",
        ["servers.caption"] = "vless / vmess / trojan / ss / hysteria2 / tuic — добавьте ключ или подписку.",
        ["servers.add"] = "Добавить",
        ["servers.fromClipboard"] = "Из буфера",
        ["servers.removeSelected"] = "Удалить выбранный",
        ["servers.ping"] = "Пинг",
        ["servers.pingAll"] = "Пинг всех",
        ["servers.count"] = "{0} ключей",

        ["sub.title"] = "Подписка",
        ["sub.caption"] = "URL подписки: тело — base64 со ссылками или plain. Ключи привязаны к подписке.",
        ["sub.label"] = "Метка (опционально)",
        ["sub.add"] = "Добавить",
        ["sub.refreshAll"] = "Обновить все",
        ["sub.refreshOne"] = "Обновить выбранную",
        ["sub.remove"] = "Удалить",

        ["settings.title"] = "Настройки",
        ["settings.appearance"] = "Внешний вид",
        ["settings.language"] = "Язык",
        ["settings.theme"] = "Тема",
        ["settings.theme.light"] = "Светлая",
        ["settings.theme.dark"] = "Тёмная",
        ["settings.theme.system"] = "Системная",
        ["settings.mode"] = "Режим",
        ["settings.mode.manual"] = "Ручной выбор",
        ["settings.mode.auto"] = "Авто-выбор (пинг + переключение)",
        ["settings.tools"] = "Бинарники",
        ["settings.toolsCaption"] = "xray-core, sing-box, hev, wintun, geo. Встроены в .exe; можно обновить с GitHub.",
        ["settings.toolsPrepare"] = "Обновить с GitHub",
        ["settings.folders"] = "Папки",
        ["settings.openData"] = "Открыть папку",
        ["settings.openLog"] = "Открыть лог",
        ["settings.url"] = "URL-схема",
        ["settings.urlCaption"] = "Регистрирует протокол x-rav:// для импорта ключей по ссылке.",
        ["settings.urlRegister"] = "Зарегистрировать x-rav://",
        ["settings.urlRegistered"] = "Зарегистрировано",
        ["settings.faq"] = "FAQ",
        ["settings.about"] = "О приложении",
        ["settings.version"] = "Версия",
        ["settings.website"] = "Сайт",
        ["settings.developers"] = "Разработчики",
        ["settings.github"] = "GitHub",
        ["settings.license"] = "Лицензия",
        ["settings.licenseText"] = "MIT",
        ["settings.autoHint"] = "В авто-режиме приложение само выбирает быстрейший ключ и переключается при сбое.",

        ["log.title"] = "Лог процессов",
        ["log.copy"] = "Скопировать",
        ["log.clear"] = "Очистить",
        ["log.empty"] = "Лог пуст. Нажмите кнопку питания — здесь появится вывод xray / sing-box / hev.",

        ["banner.update"] = "Доступны обновления: {0}. Откройте Настройки → «Обновить с GitHub».",

        ["auto.connectedTo"] = "Подключено к: {0} · {1} мс",
        ["auto.searching"] = "Поиск лучшего ключа…",

        ["faq.q1"] = "Почему «xray сразу завершился (код -1)»?",
        ["faq.a1"] = "Чаще всего Windows Defender блокирует xray.exe. Добавьте папку %APPDATA%\\X-Rav в исключения антивируса.",
        ["faq.q2"] = "Нужны ли права администратора?",
        ["faq.a2"] = "Да. Системный TUN (Wintun) без админа не работает. .bat-файлы запускают .exe с UAC-промптом.",
        ["faq.q3"] = "Что такое «Авто-выбор»?",
        ["faq.a3"] = "Приложение пингует все ваши ключи раз в 30 секунд и подключается к самому быстрому. При обрыве — автоматически переключается на следующий.",
        ["faq.q4"] = "Туннелируется ли вся система?",
        ["faq.a4"] = "Да. Wintun (для xray+hev) и встроенный TUN sing-box перехватывают весь IP-трафик. Локальные адреса (192.168.*, 10.*, 127.*) идут напрямую.",
        ["faq.q5"] = "Какие протоколы поддерживаются?",
        ["faq.a5"] = "VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / TUIC + raw xray JSON-конфиги. REALITY и uTLS-fingerprint работают."
    };

    private static Dictionary<string, string> LangEn() => new()
    {
        ["app.title"] = "X-Rav",
        ["nav.servers"] = "Servers",
        ["nav.subscription"] = "Subscription",
        ["nav.log"] = "Log",
        ["nav.settings"] = "Settings",

        ["state.connected"] = "Connected",
        ["state.connecting"] = "Connecting",
        ["state.reconnecting"] = "Reconnecting",
        ["state.disconnected"] = "Disconnected",
        ["state.error"] = "Error",

        ["power.connect"] = "Tap to connect",
        ["power.disconnect"] = "Tap to disconnect",
        ["power.connecting"] = "Connecting…",
        ["power.reconnecting"] = "Reconnecting…",
        ["power.error"] = "Error — try again",
        ["power.auto"] = "Auto: will switch to another key on failure",

        ["servers.title"] = "Servers",
        ["servers.caption"] = "vless / vmess / trojan / ss / hysteria2 / tuic — add a key or subscription.",
        ["servers.add"] = "Add",
        ["servers.fromClipboard"] = "From clipboard",
        ["servers.removeSelected"] = "Remove selected",
        ["servers.ping"] = "Ping",
        ["servers.pingAll"] = "Ping all",
        ["servers.count"] = "{0} keys",

        ["sub.title"] = "Subscription",
        ["sub.caption"] = "Subscription URL: body is base64-list of links or plain. Keys are linked to the subscription.",
        ["sub.label"] = "Label (optional)",
        ["sub.add"] = "Add",
        ["sub.refreshAll"] = "Refresh all",
        ["sub.refreshOne"] = "Refresh selected",
        ["sub.remove"] = "Remove",

        ["settings.title"] = "Settings",
        ["settings.appearance"] = "Appearance",
        ["settings.language"] = "Language",
        ["settings.theme"] = "Theme",
        ["settings.theme.light"] = "Light",
        ["settings.theme.dark"] = "Dark",
        ["settings.theme.system"] = "System",
        ["settings.mode"] = "Mode",
        ["settings.mode.manual"] = "Manual selection",
        ["settings.mode.auto"] = "Auto (ping + failover)",
        ["settings.tools"] = "Binaries",
        ["settings.toolsCaption"] = "xray-core, sing-box, hev, wintun, geo. Embedded in .exe; can be refreshed from GitHub.",
        ["settings.toolsPrepare"] = "Update from GitHub",
        ["settings.folders"] = "Folders",
        ["settings.openData"] = "Open folder",
        ["settings.openLog"] = "Open log",
        ["settings.url"] = "URL scheme",
        ["settings.urlCaption"] = "Registers the x-rav:// protocol so links open in this app.",
        ["settings.urlRegister"] = "Register x-rav://",
        ["settings.urlRegistered"] = "Registered",
        ["settings.faq"] = "FAQ",
        ["settings.about"] = "About",
        ["settings.version"] = "Version",
        ["settings.website"] = "Website",
        ["settings.developers"] = "Developers",
        ["settings.github"] = "GitHub",
        ["settings.license"] = "License",
        ["settings.licenseText"] = "MIT",
        ["settings.autoHint"] = "In Auto mode the app picks the fastest key and switches over on failure.",

        ["log.title"] = "Process log",
        ["log.copy"] = "Copy",
        ["log.clear"] = "Clear",
        ["log.empty"] = "Log is empty. Press the power button to see xray / sing-box / hev output here.",

        ["banner.update"] = "Updates available: {0}. Open Settings → \"Update from GitHub\".",

        ["auto.connectedTo"] = "Connected to: {0} · {1} ms",
        ["auto.searching"] = "Looking for the best key…",

        ["faq.q1"] = "Why does xray exit with code -1?",
        ["faq.a1"] = "Usually Windows Defender quarantines xray.exe. Add %APPDATA%\\X-Rav to your AV exclusions.",
        ["faq.q2"] = "Do I need admin rights?",
        ["faq.a2"] = "Yes. System TUN (Wintun) requires admin. The .bat launchers prompt UAC automatically.",
        ["faq.q3"] = "What is Auto mode?",
        ["faq.a3"] = "The app pings all your keys every 30s and connects to the fastest one. On failure it auto-switches to the next.",
        ["faq.q4"] = "Is the whole system tunneled?",
        ["faq.a4"] = "Yes. Wintun (for xray+hev) and sing-box's built-in TUN intercept all IP traffic. Private addresses (192.168.*, 10.*, 127.*) bypass.",
        ["faq.q5"] = "Which protocols are supported?",
        ["faq.a5"] = "VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / TUIC + raw xray JSON. REALITY and uTLS fingerprints work."
    };

    private static Dictionary<string, string> LangTr() => new()
    {
        ["app.title"] = "X-Rav",
        ["nav.servers"] = "Sunucular",
        ["nav.subscription"] = "Abonelik",
        ["nav.log"] = "Günlük",
        ["nav.settings"] = "Ayarlar",

        ["state.connected"] = "Bağlandı",
        ["state.connecting"] = "Bağlanıyor",
        ["state.reconnecting"] = "Yeniden bağlanıyor",
        ["state.disconnected"] = "Bağlı değil",
        ["state.error"] = "Hata",

        ["power.connect"] = "Bağlanmak için dokunun",
        ["power.disconnect"] = "Bağlantıyı kesmek için dokunun",
        ["power.connecting"] = "Bağlanıyor…",
        ["power.reconnecting"] = "Yeniden bağlanıyor…",
        ["power.error"] = "Hata — tekrar deneyin",
        ["power.auto"] = "Oto: hata durumunda başka anahtara geçer",

        ["servers.title"] = "Sunucular",
        ["servers.caption"] = "vless / vmess / trojan / ss / hysteria2 / tuic — anahtar veya abonelik ekleyin.",
        ["servers.add"] = "Ekle",
        ["servers.fromClipboard"] = "Panodan",
        ["servers.removeSelected"] = "Seçileni sil",
        ["servers.ping"] = "Ping",
        ["servers.pingAll"] = "Tümünü pingle",
        ["servers.count"] = "{0} anahtar",

        ["sub.title"] = "Abonelik",
        ["sub.caption"] = "Abonelik URL'si: gövde base64 ya da düz metin. Anahtarlar aboneliğe bağlanır.",
        ["sub.label"] = "Etiket (isteğe bağlı)",
        ["sub.add"] = "Ekle",
        ["sub.refreshAll"] = "Tümünü yenile",
        ["sub.refreshOne"] = "Seçileni yenile",
        ["sub.remove"] = "Sil",

        ["settings.title"] = "Ayarlar",
        ["settings.appearance"] = "Görünüm",
        ["settings.language"] = "Dil",
        ["settings.theme"] = "Tema",
        ["settings.theme.light"] = "Açık",
        ["settings.theme.dark"] = "Koyu",
        ["settings.theme.system"] = "Sistem",
        ["settings.mode"] = "Mod",
        ["settings.mode.manual"] = "Manuel seçim",
        ["settings.mode.auto"] = "Oto (ping + yedek geçiş)",
        ["settings.tools"] = "İkili dosyalar",
        ["settings.toolsCaption"] = "xray-core, sing-box, hev, wintun, geo. .exe'ye gömülü; GitHub'dan güncellenebilir.",
        ["settings.toolsPrepare"] = "GitHub'dan güncelle",
        ["settings.folders"] = "Klasörler",
        ["settings.openData"] = "Klasörü aç",
        ["settings.openLog"] = "Günlüğü aç",
        ["settings.url"] = "URL şeması",
        ["settings.urlCaption"] = "Bağlantıların bu uygulamada açılması için x-rav:// protokolünü kaydeder.",
        ["settings.urlRegister"] = "x-rav:// kaydet",
        ["settings.urlRegistered"] = "Kaydedildi",
        ["settings.faq"] = "SSS",
        ["settings.about"] = "Hakkında",
        ["settings.version"] = "Sürüm",
        ["settings.website"] = "Web sitesi",
        ["settings.developers"] = "Geliştiriciler",
        ["settings.github"] = "GitHub",
        ["settings.license"] = "Lisans",
        ["settings.licenseText"] = "MIT",
        ["settings.autoHint"] = "Oto modunda uygulama en hızlı anahtarı seçer ve hata durumunda değiştirir.",

        ["log.title"] = "Süreç günlüğü",
        ["log.copy"] = "Kopyala",
        ["log.clear"] = "Temizle",
        ["log.empty"] = "Günlük boş. Güç düğmesine basın — xray / sing-box / hev çıktısı burada görünecek.",

        ["banner.update"] = "Güncellemeler mevcut: {0}. Ayarlar → \"GitHub'dan güncelle\".",

        ["auto.connectedTo"] = "Bağlandı: {0} · {1} ms",
        ["auto.searching"] = "En iyi anahtar aranıyor…",

        ["faq.q1"] = "Neden xray code -1 ile çıkıyor?",
        ["faq.a1"] = "Genelde Windows Defender xray.exe'yi engeller. %APPDATA%\\X-Rav klasörünü AV istisnalarına ekleyin.",
        ["faq.q2"] = "Yönetici hakları gerekli mi?",
        ["faq.a2"] = "Evet. Sistem TUN (Wintun) yönetici hakkı ister. .bat dosyaları UAC isteğini otomatik açar.",
        ["faq.q3"] = "Oto mod nedir?",
        ["faq.a3"] = "Uygulama tüm anahtarları her 30 saniyede pingler ve en hızlısına bağlanır. Hata durumunda sıradakine geçer.",
        ["faq.q4"] = "Tüm sistem tünellenir mi?",
        ["faq.a4"] = "Evet. Wintun (xray+hev için) ve sing-box'un dahili TUN'u tüm IP trafiğini yakalar. Yerel adresler (192.168.*, 10.*, 127.*) atlar.",
        ["faq.q5"] = "Hangi protokoller destekleniyor?",
        ["faq.a5"] = "VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / TUIC + ham xray JSON. REALITY ve uTLS parmak izi çalışır."
    };
}
