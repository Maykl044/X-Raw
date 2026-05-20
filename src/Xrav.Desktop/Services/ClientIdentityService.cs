using System;
using System.IO;
using System.Text.RegularExpressions;
using Xrav.Desktop.Storage;

namespace Xrav.Desktop.Services;

/// <summary>
/// Стабильный идентификатор клиента (UUIDv4), который X-Rav привязывает к
/// конкретной установке. UID генерируется при первом запуске и хранится в
/// <c>%APPDATA%\X-Rav\client-uid.txt</c>. На последующих запусках читается
/// из файла. Используется панелями VPN (например, Remnawave) чтобы
/// узнавать клиента в обращениях к подписке / API.
/// </summary>
public sealed class ClientIdentityService
{
    /// <summary>RFC-4122 UUIDv4 (вариант 1, версия 4) — формат с дефисами.</summary>
    private static readonly Regex UuidV4Regex = new(
        @"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        RegexOptions.Compiled);

    private static readonly string FilePath = Path.Combine(AppDataPaths.XravRoot, "client-uid.txt");

    private string _cached;

    public ClientIdentityService()
    {
        _cached = LoadOrCreate();
    }

    /// <summary>Текущий UID клиента. Не null, не пустой, валидный UUIDv4.</summary>
    public string Uid => _cached;

    /// <summary>
    /// Маскирует UID для лога: показывает только первые 8 символов, остальное скрывает.
    /// Пример: <c>9e8d7c6b-****-****-****-************</c>.
    /// </summary>
    public static string Mask(string? uid)
    {
        if (string.IsNullOrEmpty(uid) || uid!.Length < 9) return "****";
        return uid[..8] + "-****-****-****-************";
    }

    /// <summary>Проверка строки: ли это валидный UUIDv4 в каноническом формате.</summary>
    public static bool IsValid(string? uid) =>
        !string.IsNullOrWhiteSpace(uid) && UuidV4Regex.IsMatch(uid!);

    /// <summary>
    /// Пересоздаёт UID. Используется только по явному запросу пользователя
    /// («Сменить UID» в Настройках). После смены панель перестанет узнавать
    /// клиента — нужна повторная активация / новая подписка.
    /// </summary>
    public string Regenerate()
    {
        var fresh = Guid.NewGuid().ToString("D");
        TryWrite(fresh);
        _cached = fresh;
        return fresh;
    }

    private static string LoadOrCreate()
    {
        try
        {
            Directory.CreateDirectory(AppDataPaths.XravRoot);
            if (File.Exists(FilePath))
            {
                var raw = File.ReadAllText(FilePath).Trim();
                if (IsValid(raw)) return raw;
            }
        }
        catch { /* любые I/O — генерируем заново */ }

        var generated = Guid.NewGuid().ToString("D");
        TryWrite(generated);
        return generated;
    }

    private static void TryWrite(string uid)
    {
        try
        {
            Directory.CreateDirectory(AppDataPaths.XravRoot);
            File.WriteAllText(FilePath, uid);
        }
        catch
        {
            // Если не удалось — UID живёт только в памяти процесса.
            // Это допустимая деградация: панель просто не узнает клиента
            // между перезапусками.
        }
    }
}
