using System;
using System.IO;
using MusicoWindows.Models;
using MusicoWindows.Theme;

namespace MusicoWindows.Core;

/// <summary>
/// Persists <see cref="AppearanceSettings"/> atomically to appearance.json,
/// migrates legacy theme.txt preferences on first load, and manages the
/// copied background image inside the Appearance subfolder.
/// </summary>
public sealed class AppearanceStore
{
    public const string SettingsFileName = "appearance.json";
    public const string LegacyThemeFileName = "theme.txt";
    public const string AppearanceFolderName = "Appearance";

    private static readonly HashSet<string> AllowedImageExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp"
    };

    private readonly string _legacyThemePath;

    public AppearanceStore(string? stateDirectory = null, string? legacyThemeFilePath = null)
    {
        StateDirectory = string.IsNullOrWhiteSpace(stateDirectory)
            ? Persistence.AppDataDirectory
            : stateDirectory;
        _legacyThemePath = string.IsNullOrWhiteSpace(legacyThemeFilePath)
            ? Path.Combine(StateDirectory, LegacyThemeFileName)
            : legacyThemeFilePath;
    }

    public string StateDirectory { get; }
    public string SettingsPath => Path.Combine(StateDirectory, SettingsFileName);
    public string AppearanceDirectory => Path.Combine(StateDirectory, AppearanceFolderName);

    public AppearanceSettings Load()
    {
        if (File.Exists(SettingsPath))
        {
            AppearanceSettings? loaded = Persistence.Load<AppearanceSettings>(SettingsPath);
            if (loaded is not null)
            {
                return loaded.Normalize();
            }
        }

        if (File.Exists(_legacyThemePath))
        {
            try
            {
                string raw = File.ReadAllText(_legacyThemePath).Trim();
                if (Enum.TryParse<AppThemeMode>(raw, ignoreCase: true, out var legacyMode))
                {
                    var migrated = new AppearanceSettings { ThemeMode = legacyMode }.Normalize();
                    try
                    {
                        Persistence.Save(migrated, SettingsPath);
                    }
                    catch
                    {
                        // Best-effort migration persistence; return migrated settings regardless.
                    }
                    return migrated;
                }
            }
            catch
            {
                // Ignore legacy read error and fall back to defaults.
            }
        }

        return new AppearanceSettings();
    }

    public AppearanceSettings Save(AppearanceSettings settings)
    {
        AppearanceSettings normalized = settings.Normalize();
        Persistence.Save(normalized, SettingsPath);
        return normalized;
    }

    public string ImportBackgroundImage(string sourceFilePath)
    {
        if (string.IsNullOrWhiteSpace(sourceFilePath))
            throw new ArgumentException("Source file path must not be empty.", nameof(sourceFilePath));
        if (!File.Exists(sourceFilePath))
            throw new FileNotFoundException("Background image file not found.", sourceFilePath);

        byte[] bytes = File.ReadAllBytes(sourceFilePath);
        string ext = Path.GetExtension(sourceFilePath);
        return ImportBackgroundImage(bytes, ext);
    }

    public string ImportBackgroundImage(byte[] imageBytes, string? fileExtension)
    {
        if (imageBytes is null || imageBytes.Length == 0)
            throw new ArgumentException("Background image data must not be empty.", nameof(imageBytes));

        string ext = NormalizeImageExtension(fileExtension);
        Directory.CreateDirectory(AppearanceDirectory);

        string targetFileName = $"background{ext}";
        string targetPath = Path.Combine(AppearanceDirectory, targetFileName);
        string tempPath = $"{targetPath}.{Guid.NewGuid():N}.tmp";

        try
        {
            File.WriteAllBytes(tempPath, imageBytes);
            DeleteExistingBackgroundFiles(exceptPath: tempPath);
            File.Move(tempPath, targetPath, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best-effort cleanup */ }
            throw;
        }

        return targetFileName;
    }

    public void ClearBackgroundImage()
    {
        DeleteExistingBackgroundFiles(exceptPath: null);
    }

    public string? ResolveBackgroundImagePath(AppearanceSettings settings) =>
        ResolveBackgroundImagePath(settings.BackgroundImageFileName);

    public string? ResolveBackgroundImagePath(string? fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName))
            return null;

        string safeName = Path.GetFileName(fileName.Trim());
        if (string.IsNullOrWhiteSpace(safeName))
            return null;

        string fullPath = Path.Combine(AppearanceDirectory, safeName);
        return File.Exists(fullPath) ? fullPath : null;
    }

    private void DeleteExistingBackgroundFiles(string? exceptPath)
    {
        if (!Directory.Exists(AppearanceDirectory))
            return;

        foreach (string file in Directory.EnumerateFiles(AppearanceDirectory, "background.*"))
        {
            if (exceptPath is not null && string.Equals(file, exceptPath, StringComparison.OrdinalIgnoreCase))
                continue;

            try
            {
                File.Delete(file);
            }
            catch
            {
                // Best-effort cleanup of superseded background image files.
            }
        }
    }

    private static string NormalizeImageExtension(string? ext)
    {
        if (string.IsNullOrWhiteSpace(ext))
            return ".png";

        string trimmed = ext.Trim().ToLowerInvariant();
        if (!trimmed.StartsWith('.'))
            trimmed = "." + trimmed;

        return AllowedImageExtensions.Contains(trimmed) ? trimmed : ".png";
    }
}
