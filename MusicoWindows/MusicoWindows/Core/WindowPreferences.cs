using MusicoWindows.Update;

namespace MusicoWindows.Core;

public enum WindowCloseBehavior
{
    Exit,
    MinimizeToTray
}

public sealed class WindowPreferences
{
    private readonly string _path;
    private readonly string _currentVersion;
    private string? _lastConfirmedVersion;

    public WindowPreferences(string stateDirectory, string currentVersion)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(stateDirectory);
        if (!AppVersion.TryParse(currentVersion, out var version))
            throw new ArgumentException("A valid application version is required.", nameof(currentVersion));

        _path = Path.Combine(stateDirectory, "window_prefs.json");
        _currentVersion = version.ToString();

        SavedPreferences? saved;
        try
        {
            saved = Persistence.Load<SavedPreferences>(_path);
        }
        catch (UnauthorizedAccessException)
        {
            return;
        }

        if (saved?.Behavior is not { } behavior || !Enum.IsDefined(behavior)) return;

        Behavior = behavior;
        if (AppVersion.TryParse(saved.LastConfirmedVersion, out var confirmedVersion))
            _lastConfirmedVersion = confirmedVersion.ToString();
    }

    public WindowCloseBehavior Behavior { get; private set; } = WindowCloseBehavior.Exit;

    public bool RequiresConfirmation => _lastConfirmedVersion != _currentVersion;

    public void Save(WindowCloseBehavior behavior, bool confirmVersion)
    {
        if (!Enum.IsDefined(behavior)) throw new ArgumentOutOfRangeException(nameof(behavior));

        string? confirmedVersion = confirmVersion ? _currentVersion : _lastConfirmedVersion;
        Persistence.Save(new SavedPreferences(behavior, confirmedVersion), _path);
        Behavior = behavior;
        _lastConfirmedVersion = confirmedVersion;
    }

    private sealed record SavedPreferences(WindowCloseBehavior? Behavior, string? LastConfirmedVersion);
}
