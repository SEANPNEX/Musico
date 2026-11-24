using MusicoWindows.Audio;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Update;

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
        CloudCache = new CloudAudioCache(Path.Combine(StateDirectory, "CloudCache"));
        YouTube = new YouTubeClient();
        Bilibili = new BilibiliClient();
        GoogleDrive = new GoogleDriveClient();
        CloudProviders = new CloudProviderRegistry(YouTube, Bilibili, GoogleDrive);
        Extensions = new ExtensionStore(
            stateDirectory: StateDirectory,
            appVersion: UpdateService.GetCurrentAppVersion().ToString());
        Extensions.BindRegistry(CloudProviders);
        Thumbnails = new CloudThumbnailCache();

        CloudExporter = new CloudExporter(CloudCache, YouTube, Bilibili, artwork, GoogleDrive, CloudProviders, Extensions, Thumbnails);

        Library = new LibraryStore(
            stateDirectory: StateDirectory,
            musicDirectory: MusicDirectory,
            reader: new TagLibMetadataReader(),
            artwork: artwork,
            appFolderRemovable: true,
            youtubeClient: YouTube,
            bilibiliClient: Bilibili,
            cloudCache: CloudCache,
            googleDriveClient: GoogleDrive,
            providerRegistry: CloudProviders);

        Downloads = new DownloadManager(CloudExporter, Library);
        Queues = new QueueStore(StateDirectory);
        History = new PlayHistoryStore(StateDirectory);
        Lyrics = new LyricsStore(Path.Combine(StateDirectory, "Lyrics"));
        CloudLyrics = new CloudLyricsService(
            customHandler: null,
            appVersion: UpdateService.GetCurrentAppVersion().ToString(),
            extensionSearch: (extId, query, ct) => Extensions.SearchLyricsAsync(extId, query, proxyUrl: null, ct: ct));
        AudioEngine = new AudioEngine();
        Smtc = new SmtcBridge();
        Update = new UpdateService(stateDirectory: StateDirectory);
        Player = new PlayerController(
            library: Library,
            queues: Queues,
            history: History,
            engine: AudioEngine,
            settingsPath: Path.Combine(StateDirectory, "settings.json"),
            smtc: Smtc,
            cloudCache: CloudCache,
            youtubeClient: YouTube,
            bilibiliClient: Bilibili,
            googleDriveClient: GoogleDrive,
            providerRegistry: CloudProviders,
            extensions: Extensions);
    }

    public string StateDirectory { get; }
    public string MusicDirectory { get; }
    public CloudAudioCache CloudCache { get; }
    public CloudThumbnailCache Thumbnails { get; }
    public YouTubeClient YouTube { get; }
    public BilibiliClient Bilibili { get; }
    public GoogleDriveClient GoogleDrive { get; }
    public CloudProviderRegistry CloudProviders { get; }
    public ExtensionStore Extensions { get; }
    public CloudExporter CloudExporter { get; }
    public DownloadManager Downloads { get; }
    public LibraryStore Library { get; }
    public QueueStore Queues { get; }
    public PlayHistoryStore History { get; }
    public LyricsStore Lyrics { get; }
    public CloudLyricsService CloudLyrics { get; }
    public AudioEngine AudioEngine { get; }
    public SmtcBridge Smtc { get; }
    public UpdateService Update { get; }
    public PlayerController Player { get; }

    public async Task InitializeAsync()
    {
        Library.PruneEphemeralTracks(Queues.GetAllReferencedTrackIds());
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

        Downloads.CancelAll();
        Player.PersistPosition(flush: true);
        Player.Shutdown();
        Smtc.Dispose();
        AudioEngine.Dispose();
        Update.Dispose();
    }
}
