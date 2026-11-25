using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

namespace MusicoWindows.Core;

/// <summary>
/// Writes structured startup and unhandled exception diagnostics to <c>crash.log</c>
/// so Windows startup failures never disappear silently.
/// </summary>
public static class CrashLogger
{
    private static readonly object WriteLock = new();

    public static string FormatReport(
        Exception exception,
        string context,
        bool? isPortable = null,
        string? baseDirectory = null)
    {
        ArgumentNullException.ThrowIfNull(exception);

        string resolvedContext = string.IsNullOrWhiteSpace(context) ? "UnhandledException" : context.Trim();
        string resolvedBaseDir = string.IsNullOrWhiteSpace(baseDirectory)
            ? AppContext.BaseDirectory
            : baseDirectory;
        bool portable = isPortable ?? SafeIsPortable(resolvedBaseDir);
        string appVersion = ResolveAppVersion();

        var sb = new StringBuilder();
        sb.AppendLine("============================================================");
        sb.AppendLine($"Timestamp (UTC): {DateTimeOffset.UtcNow:yyyy-MM-dd HH:mm:ss.fff}Z");
        sb.AppendLine($"Context:         {resolvedContext}");
        sb.AppendLine($"App Version:     {appVersion}");
        sb.AppendLine($"Mode:            {(portable ? "Portable" : "Installed")}");
        sb.AppendLine($"Base Directory:  {resolvedBaseDir}");
        sb.AppendLine($"OS:              {RuntimeInformation.OSDescription}");
        sb.AppendLine($"OS Architecture: {RuntimeInformation.OSArchitecture}");
        sb.AppendLine($"Process Arch:    {RuntimeInformation.ProcessArchitecture}");
        sb.AppendLine($"Runtime:         {RuntimeInformation.FrameworkDescription}");
        sb.AppendLine("Exception:");
        sb.AppendLine(exception.ToString());
        sb.AppendLine();
        return sb.ToString();
    }

    public static string? WriteCrashLog(
        Exception exception,
        string context,
        string? preferredDirectory = null,
        string? fallbackDirectory = null)
    {
        if (exception is null) return null;

        string report;
        try
        {
            report = FormatReport(exception, context);
        }
        catch
        {
            return null;
        }

        string? primaryDir = preferredDirectory ?? TryGetAppDataDirectory();
        if (!string.IsNullOrWhiteSpace(primaryDir))
        {
            string primaryPath = Path.Combine(primaryDir, "crash.log");
            if (TryAppend(primaryPath, report))
            {
                return primaryPath;
            }
        }

        string? tempDir = fallbackDirectory;
        if (string.IsNullOrWhiteSpace(tempDir))
        {
            try
            {
                tempDir = Path.GetTempPath();
            }
            catch
            {
                tempDir = null;
            }
        }

        if (!string.IsNullOrWhiteSpace(tempDir))
        {
            string fallbackPath = Path.Combine(tempDir, "MusicoWindows-crash.log");
            if (TryAppend(fallbackPath, report))
            {
                return fallbackPath;
            }
        }

        return null;
    }

    public static Exception UnwrapInnermost(Exception exception)
    {
        ArgumentNullException.ThrowIfNull(exception);
        Exception current = exception;
        while (current.InnerException is { } inner &&
               (current is TypeInitializationException
                || current is TargetInvocationException
                || current.GetType().FullName == "System.Windows.Markup.XamlParseException"))
        {
            current = inner;
        }
        return current;
    }

    private static bool TryAppend(string filePath, string content)
    {
        try
        {
            string? dir = Path.GetDirectoryName(filePath);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }

            lock (WriteLock)
            {
                File.AppendAllText(filePath, content, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            }
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string? TryGetAppDataDirectory()
    {
        try
        {
            return Persistence.AppDataDirectory;
        }
        catch
        {
            return null;
        }
    }

    private static bool SafeIsPortable(string baseDirectory)
    {
        try
        {
            return !string.IsNullOrWhiteSpace(baseDirectory) &&
                   File.Exists(Path.Combine(baseDirectory, "portable.txt"));
        }
        catch
        {
            return false;
        }
    }

    private static string ResolveAppVersion()
    {
        try
        {
            Assembly? asm = Assembly.GetEntryAssembly() ?? typeof(CrashLogger).Assembly;
            var infoAttr = asm.GetCustomAttribute<AssemblyInformationalVersionAttribute>();
            if (!string.IsNullOrWhiteSpace(infoAttr?.InformationalVersion))
            {
                return infoAttr.InformationalVersion;
            }
            return asm.GetName().Version?.ToString() ?? "unknown";
        }
        catch
        {
            return "unknown";
        }
    }
}
