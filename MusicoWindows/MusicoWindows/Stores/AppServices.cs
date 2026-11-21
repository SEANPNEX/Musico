using MusicoWindows.Audio;
using MusicoWindows.Core;

namespace MusicoWindows.Stores;

/// <summary>
/// Owns all long-lived stores, audio engine, and Windows 10 SMTC integration for the running app.
/// </summary>
public sealed class AppServices : IDisposable
{
    private bool _disposed;

    public AppServices(string? stateDirectory = null, string? musicDirectory = null)
    {
        StateDirectory = stateDirectory ?? Persistence.AppDataDirectory;
        Directory.CreateDirectory(StateDirectory);

        MusicDirectory = musicDirectory ?? Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);

        var artwork = new ArtworkCache(Path.Combine(StateDirectory, "Artwork"));
        Library = new LibraryStore(
            stateDirectory: StateDirectory,
            musicDirectory: MusicDirectory,
            reader: new TagLibMetadataReader(),
            artwork: artwork,
            appFolderRemovable: true);

        Queues = new QueueStore(StateDirectory);
        History = new PlayHistoryStore(StateDirectory);
        Lyrics = new LyricsStore(Path.Combine(StateDirectory, "Lyrics"));
        AudioEngine = new AudioEngine();
        Smtc = new SmtcBridge();
        Player = new PlayerController(
            library: Library,
            queues: Queues,
            history: History,
            engine: AudioEngine,
            settingsPath: Path.Combine(StateDirectory, "settings.json"),
            smtc: Smtc);
    }

    public string StateDirectory { get; }
    public string MusicDirectory { get; }
    public LibraryStore Library { get; }
    public QueueStore Queues { get; }
    public PlayHistoryStore History { get; }
    public LyricsStore Lyrics { get; }
    public AudioEngine AudioEngine { get; }
    public SmtcBridge Smtc { get; }
    public PlayerController Player { get; }

    public async Task InitializeAsync()
    {
        Player.Restore();
        await Library.RescanAsync();
        if (Player.CurrentTrack is null)
        {
            Player.Restore();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        Player.PersistPosition(flush: true);
        Player.Shutdown();
        Smtc.Dispose();
        AudioEngine.Dispose();
    }
}
