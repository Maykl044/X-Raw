using System.Globalization;
using Xrav.Desktop.Storage;

namespace Xrav.Desktop.Logging;

public static class FileLogger
{
    private static readonly object _lock = new();
    private static string LogDir => Path.Combine(AppDataPaths.XravRoot, "logs");
    private static string LogFile => Path.Combine(LogDir, $"xrav-{DateTime.Now:yyyyMMdd}.log");

    public static void Log(string source, string message)
    {
        try
        {
            Directory.CreateDirectory(LogDir);
            lock (_lock)
            {
                File.AppendAllText(
                    LogFile,
                    $"{DateTime.Now.ToString("HH:mm:ss.fff", CultureInfo.InvariantCulture)} [{source}] {message}{Environment.NewLine}");
            }
        }
        catch
        {
            // Ignore logging failures — never crash the app.
        }
    }

    public static void Error(string source, Exception ex) =>
        Log(source, $"ERROR: {ex.GetType().Name}: {ex.Message}{Environment.NewLine}{ex.StackTrace}");

    public static string GetLogFile() => LogFile;
}
