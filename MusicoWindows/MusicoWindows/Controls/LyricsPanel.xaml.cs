using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using MusicoWindows.Cloud;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class LyricsPanel : UserControl
{
    private readonly List<TextBlock> _lineBlocks = new();
    private readonly List<Border> _lineContainers = new();
    private readonly DispatcherTimer _manualScrollGraceTimer;
    private AppServices? _services;
    private Lyrics? _lyrics;
    private LyricsSource? _source;
    private int? _activeLineIndex;
    private string? _loadedTrackId;
    private bool _embedded;
    private bool _isManualScrolling;
    private int _programmaticScrollDepth;
    private string? _sideAmbientSignature;
    private CancellationTokenSource? _sideAmbientCts;

    public event Action? CloseRequested;
    public event Action<Track>? SearchOnlineRequested;
    public event Action<string, string>? ErrorOccurred;
    public event Action<string, string>? InfoOccurred;

    public LyricsPanel()
    {
        InitializeComponent();
        _manualScrollGraceTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(3.5)
        };
        _manualScrollGraceTimer.Tick += (_, _) => EndManualScrollGracePeriod(scrollToActive: true);

        LocalizationManager.LanguageChanged += OnLanguageChanged;
        ThemeManager.AppearanceChanged += OnAppearanceChanged;
        Unloaded += (_, _) =>
        {
            _manualScrollGraceTimer.Stop();
            _sideAmbientCts?.Cancel();
            _sideAmbientCts?.Dispose();
            _sideAmbientCts = null;
            LocalizationManager.LanguageChanged -= OnLanguageChanged;
            ThemeManager.AppearanceChanged -= OnAppearanceChanged;
        };
    }

    private void OnLanguageChanged(ResolvedLanguage _) => RefreshLocalizedText();

    private void OnAppearanceChanged(AppearanceSettings _)
    {
        Dispatcher.BeginInvoke(ApplyLyricsTypography);
    }

    private void RefreshLocalizedText()
    {
        UpdateHeaderMetadata(_services?.Player.CurrentTrack);
        UpdateSourceTagText();
    }

    private void UpdateHeaderMetadata(Track? track)
    {
        HeaderTitleText.Text = track?.Title ?? L.Get("Lyrics_Title");
        if (track is not null)
        {
            string artistDisplay = L.DisplayArtist(track.Artist);
            HeaderArtistText.Text = artistDisplay;
            HeaderArtistText.Visibility = string.IsNullOrWhiteSpace(artistDisplay)
                ? Visibility.Collapsed
                : Visibility.Visible;
        }
        else
        {
            HeaderArtistText.Text = string.Empty;
            HeaderArtistText.Visibility = Visibility.Collapsed;
        }
        UpdateSourceTagBadgeMargin();
    }

    private void UpdateSourceTagBadgeMargin()
    {
        SourceTagBadge.Margin = HeaderArtistText.Visibility == Visibility.Visible
            ? new Thickness(8, 0, 0, 0)
            : new Thickness(0);
    }

    private void UpdateSourceTagText()
    {
        if (_source is LyricsSource src)
        {
            SourceTagText.Text = src switch
            {
                LyricsSource.Imported => L.Get("Lyrics_SourceImported"),
                LyricsSource.Sidecar => L.Get("Lyrics_SourceSidecar"),
                LyricsSource.FolderMatch => L.Get("Lyrics_SourceFolderMatch"),
                LyricsSource.Embedded => L.Get("Lyrics_SourceEmbedded"),
                _ => string.Empty
            };
            SourceTagBadge.Visibility = string.IsNullOrEmpty(SourceTagText.Text)
                ? Visibility.Collapsed
                : Visibility.Visible;
            UpdateSourceTagBadgeMargin();
        }
        else
        {
            SourceTagBadge.Visibility = Visibility.Collapsed;
        }
    }

    public bool Embedded
    {
        get => _embedded;
        set
        {
            _embedded = value;
            CloseButton.Visibility = value ? Visibility.Collapsed : Visibility.Visible;
            UpdateSideAmbientBackdrop(_services, _services?.Player.CurrentTrack);
        }
    }

    public void Initialize(AppServices services)
    {
        if (_services != null)
        {
            _services.Player.PropertyChanged -= OnPlayerChanged;
            _services.Lyrics.LyricsChanged -= OnLyricsStoreChanged;
            _services.Library.TrackUpdated -= OnLibraryTrackUpdated;
        }
        _services = services;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Lyrics.LyricsChanged += OnLyricsStoreChanged;
        _services.Library.TrackUpdated += OnLibraryTrackUpdated;
        _ = LoadLyricsAsync(forceReload: true);
    }

    private void OnLibraryTrackUpdated(Track updated)
    {
        Dispatcher.InvokeAsync(() =>
        {
            if (_services?.Player.CurrentTrack?.Id == updated.Id)
            {
                UpdateHeaderMetadata(updated);
                UpdateSideAmbientBackdrop(_services, updated);
            }
        });
    }

    private void OnLyricsStoreChanged(string trackId)
    {
        Dispatcher.InvokeAsync(() =>
        {
            if (_services?.Player.CurrentTrack?.Id == trackId)
            {
                _ = LoadLyricsAsync(forceReload: true);
            }
        });
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerController.CurrentTrack))
        {
            _ = LoadLyricsAsync(forceReload: false);
        }
        else if (e.PropertyName == nameof(PlayerController.CurrentTimeSeconds))
        {
            UpdateActiveLine();
        }
    }

    public async Task LoadLyricsAsync(bool forceReload = false)
    {
        if (_services is null) return;
        var track = _services.Player.CurrentTrack;

        UpdateSideAmbientBackdrop(_services, track);

        if (!forceReload && track?.Id == _loadedTrackId)
        {
            UpdateHeaderMetadata(track);
            UpdateActiveLine();
            return;
        }

        _loadedTrackId = track?.Id;
        _activeLineIndex = null;
        EndManualScrollGracePeriod(scrollToActive: false);
        UpdateHeaderMetadata(track);
        SearchOnlineButton.IsEnabled = track != null;
        EmptySearchOnlineButton.IsEnabled = track != null;
        ImportButton.IsEnabled = track != null;
        EmptyImportButton.IsEnabled = track != null;

        if (track is null)
        {
            _lyrics = null;
            _source = null;
            RenderLyrics();
            return;
        }

        string? embedded = await _services.Library.GetEmbeddedLyricsAsync(track);
        if (_services.Player.CurrentTrack?.Id != track.Id) return;

        var resolved = _services.Lyrics.Resolve(
            track.Id,
            _services.Library.GetFilePath(track),
            embedded,
            track.Title,
            track.Artist);

        _lyrics = resolved?.Lyrics;
        _source = resolved?.Source;
        RenderLyrics();
    }

    private (double TimedSize, double UntimedSize, TextAlignment Alignment) ResolveTypography()
    {
        AppearanceSettings settings = ThemeManager.CurrentSettings;
        double timed = settings.LyricsFontSize switch
        {
            LyricsFontSize.Compact => 14,
            LyricsFontSize.Large => 20,
            _ => 16
        };
        double untimed = settings.LyricsFontSize switch
        {
            LyricsFontSize.Compact => 12.5,
            LyricsFontSize.Large => 17,
            _ => 14
        };
        TextAlignment align = settings.LyricsAlignment == LyricsAlignment.Center
            ? TextAlignment.Center
            : TextAlignment.Left;
        return (timed, untimed, align);
    }

    private void ApplyLyricsTypography()
    {
        if (_lyrics is null || _lineBlocks.Count == 0) return;
        var (timed, untimed, align) = ResolveTypography();
        for (int i = 0; i < _lineBlocks.Count; i++)
        {
            var tb = _lineBlocks[i];
            tb.TextAlignment = align;
            if (_lyrics.IsTimed)
            {
                ApplyTimedLineVisual(tb, i, _activeLineIndex, timed);
            }
            else
            {
                tb.FontSize = untimed;
            }
        }
        if (!_isManualScrolling && _activeLineIndex is int activeIdx)
        {
            ScrollToLine(activeIdx);
        }
    }

    private void RenderLyrics()
    {
        LinesStack.Children.Clear();
        _lineBlocks.Clear();
        _lineContainers.Clear();
        EndManualScrollGracePeriod(scrollToActive: false);

        bool hasTrack = _services?.Player.CurrentTrack != null;
        bool hasImported = hasTrack && _services!.Lyrics.HasImported(_services.Player.CurrentTrack!.Id);
        string? audioPath = hasTrack ? _services!.Library.GetFilePath(_services.Player.CurrentTrack!) : null;
        bool canExport = hasTrack && _lyrics is { Lines.Count: > 0 } && _services!.Lyrics.CanExportSidecar(audioPath);
        RemoveButton.Visibility = hasImported ? Visibility.Visible : Visibility.Collapsed;
        ExportSidecarButton.Visibility = canExport ? Visibility.Visible : Visibility.Collapsed;

        UpdateSourceTagText();

        if (_lyrics is null || _lyrics.Lines.Count == 0)
        {
            LyricsScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        LyricsScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        var (timedSize, untimedSize, align) = ResolveTypography();
        double fontSize = _lyrics.IsTimed ? timedSize : untimedSize;

        foreach (var line in _lyrics.Lines)
        {
            var tb = new TextBlock
            {
                Text = string.IsNullOrEmpty(line.Text) ? "♪" : line.Text,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = fontSize,
                TextAlignment = align,
                TextWrapping = TextWrapping.Wrap
            };

            var container = new Border
            {
                Background = Brushes.Transparent,
                BorderBrush = Brushes.Transparent,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(10, 6, 10, 6),
                Margin = new Thickness(0, 0, 0, _lyrics.IsTimed ? 4 : 2),
                Child = tb
            };

            if (_lyrics.IsTimed && line.TimeSeconds is double seekTime)
            {
                container.Cursor = Cursors.Hand;
                tb.Opacity = 0.72;
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                container.MouseEnter += (_, _) =>
                {
                    container.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
                    container.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
                };
                container.MouseLeave += (_, _) =>
                {
                    container.Background = Brushes.Transparent;
                    container.BorderBrush = Brushes.Transparent;
                };
                container.MouseLeftButtonUp += (_, _) =>
                {
                    EndManualScrollGracePeriod(scrollToActive: false);
                    _services?.Player.Seek(seekTime);
                };
            }
            else
            {
                tb.Opacity = 1.0;
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            }

            LinesStack.Children.Add(container);
            _lineContainers.Add(container);
            _lineBlocks.Add(tb);
        }

        _activeLineIndex = null;
        UpdateActiveLine();
    }

    private void UpdateActiveLine()
    {
        if (_services is null || _lyrics is null || !_lyrics.IsTimed) return;

        int? nextIndex = _lyrics.LineIndexAt(_services.Player.CurrentTimeSeconds);
        if (nextIndex == _activeLineIndex) return;

        _activeLineIndex = nextIndex;
        var (timedSize, _, _) = ResolveTypography();

        for (int i = 0; i < _lineBlocks.Count; i++)
        {
            ApplyTimedLineVisual(_lineBlocks[i], i, nextIndex, timedSize);
        }

        if (nextIndex is int targetIdx && !_isManualScrolling)
        {
            ScrollToLine(targetIdx);
        }
    }

    private static void ApplyTimedLineVisual(TextBlock block, int index, int? activeIndex, double timedSize)
    {
        if (activeIndex is int activeIdx)
        {
            int distance = Math.Abs(index - activeIdx);
            if (distance == 0)
            {
                block.FontWeight = FontWeights.Bold;
                block.FontSize = timedSize + 3.0;
                block.Opacity = 1.0;
                block.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            }
            else
            {
                block.FontWeight = distance == 1 ? FontWeights.Medium : FontWeights.Normal;
                block.FontSize = distance >= 3 ? Math.Max(11.0, timedSize - 0.5) : timedSize;
                block.Opacity = distance switch
                {
                    1 => 0.72,
                    2 => 0.46,
                    _ => 0.28
                };
                block.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            }
        }
        else
        {
            block.FontWeight = FontWeights.Normal;
            block.FontSize = timedSize;
            block.Opacity = 0.75;
            block.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        }
    }

    private void ScrollToLine(int lineIndex)
    {
        if (lineIndex < 0 || lineIndex >= _lineContainers.Count) return;
        var activeContainer = _lineContainers[lineIndex];

        Dispatcher.BeginInvoke(() =>
        {
            if (LyricsScroll.ViewportHeight <= 0) return;
            try
            {
                var transform = activeContainer.TransformToAncestor(LinesStack);
                var point = transform.Transform(new Point(0, 0));
                double targetOffset = point.Y - (LyricsScroll.ViewportHeight / 2.0) + (activeContainer.ActualHeight / 2.0);
                _programmaticScrollDepth++;
                try
                {
                    LyricsScroll.ScrollToVerticalOffset(Math.Max(0, targetOffset));
                }
                finally
                {
                    Dispatcher.BeginInvoke(
                        () => _programmaticScrollDepth = Math.Max(0, _programmaticScrollDepth - 1),
                        DispatcherPriority.Background);
                }
            }
            catch
            {
                // Element not yet measured
            }
        });
    }

    private void UpdateSideAmbientBackdrop(AppServices? services, Track? track)
    {
        if (SideAmbientBackdropHost is null || SideAmbientBackdropImage is null || SideAmbientScrim is null)
            return;

        if (_embedded || services is null || track is null)
        {
            _sideAmbientSignature = null;
            _sideAmbientCts?.Cancel();
            _sideAmbientCts?.Dispose();
            _sideAmbientCts = null;
            SideAmbientBackdropImage.Source = null;
            SideAmbientBackdropHost.Visibility = Visibility.Collapsed;
            SideAmbientScrim.Visibility = Visibility.Collapsed;
            return;
        }

        string sig = $"{track.Id}|{track.ArtworkKey}|{track.RemoteArtworkUrl}";
        if (string.Equals(_sideAmbientSignature, sig, StringComparison.Ordinal))
            return;
        _sideAmbientSignature = sig;

        _sideAmbientCts?.Cancel();
        _sideAmbientCts?.Dispose();
        _sideAmbientCts = null;

        if (!string.IsNullOrWhiteSpace(track.ArtworkKey))
        {
            string? path = services.Library.Artwork.GetImagePath(track.ArtworkKey);
            if (!string.IsNullOrEmpty(path) && File.Exists(path))
            {
                try
                {
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.UriSource = new Uri(path, UriKind.Absolute);
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.DecodePixelWidth = 180;
                    bmp.EndInit();
                    bmp.Freeze();
                    ShowSideAmbientBitmap(bmp);
                    return;
                }
                catch
                {
                    // Fall through to remote or hide.
                }
            }
        }

        if (CloudThumbnailCache.IsAllowedHttpsUrl(track.RemoteArtworkUrl))
        {
            string remoteUrl = track.RemoteArtworkUrl!.Trim();
            if (services.Thumbnails.TryGetBitmap(remoteUrl, 180, out var cached) && cached is not null)
            {
                ShowSideAmbientBitmap(cached);
                return;
            }

            var cts = new CancellationTokenSource();
            _sideAmbientCts = cts;
            _ = LoadSideAmbientRemoteAsync(services.Thumbnails, remoteUrl, sig, cts.Token);
        }

        SideAmbientBackdropImage.Source = null;
        SideAmbientBackdropHost.Visibility = Visibility.Collapsed;
        SideAmbientScrim.Visibility = Visibility.Collapsed;
    }

    private async Task LoadSideAmbientRemoteAsync(
        CloudThumbnailCache cache,
        string url,
        string expectedSig,
        CancellationToken ct)
    {
        try
        {
            var bmp = await cache.GetBitmapAsync(url, 180, ct);
            if (ct.IsCancellationRequested || bmp is null || _embedded) return;
            if (!string.Equals(_sideAmbientSignature, expectedSig, StringComparison.Ordinal)) return;
            ShowSideAmbientBitmap(bmp);
        }
        catch
        {
            // Ignore remote ambient artwork errors.
        }
    }

    private void ShowSideAmbientBitmap(BitmapImage bmp)
    {
        if (_embedded) return;
        SideAmbientBackdropImage.Source = bmp;
        SideAmbientBackdropHost.Visibility = Visibility.Visible;
        SideAmbientScrim.Visibility = Visibility.Visible;
        Win10Animations.AnimateFadeIn(SideAmbientBackdropImage, 0.0, 260);
    }

    private void BeginManualScrollGracePeriod()
    {
        if (_lyrics is not { IsTimed: true } || _activeLineIndex is null) return;
        _isManualScrolling = true;
        SyncToCurrentButton.Visibility = Visibility.Visible;
        _manualScrollGraceTimer.Stop();
        _manualScrollGraceTimer.Start();
    }

    private void EndManualScrollGracePeriod(bool scrollToActive)
    {
        _manualScrollGraceTimer.Stop();
        _isManualScrolling = false;
        SyncToCurrentButton.Visibility = Visibility.Collapsed;
        if (scrollToActive && _activeLineIndex is int activeIdx)
        {
            ScrollToLine(activeIdx);
        }
    }

    private void LyricsScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        BeginManualScrollGracePeriod();
    }

    private void LyricsScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (_programmaticScrollDepth > 0 || Math.Abs(e.VerticalChange) < 0.5) return;
        BeginManualScrollGracePeriod();
    }

    private void SyncToCurrent_Click(object sender, RoutedEventArgs e)
    {
        EndManualScrollGracePeriod(scrollToActive: true);
    }

    private void Options_Click(object sender, RoutedEventArgs e)
    {
        AppearanceSettings current = ThemeManager.CurrentSettings;
        var menu = new ContextMenu();

        var fontSizeHeader = new MenuItem
        {
            Header = L.Get("Lyrics_FontSizeHeader"),
            IsEnabled = false
        };
        menu.Items.Add(fontSizeHeader);
        AddCheckableItem(menu, L.Get("Lyrics_SizeCompact"), current.LyricsFontSize == LyricsFontSize.Compact,
            () => ThemeManager.SetLyricsFontSize(Application.Current, LyricsFontSize.Compact));
        AddCheckableItem(menu, L.Get("Lyrics_SizeMedium"), current.LyricsFontSize == LyricsFontSize.Medium,
            () => ThemeManager.SetLyricsFontSize(Application.Current, LyricsFontSize.Medium));
        AddCheckableItem(menu, L.Get("Lyrics_SizeLarge"), current.LyricsFontSize == LyricsFontSize.Large,
            () => ThemeManager.SetLyricsFontSize(Application.Current, LyricsFontSize.Large));

        menu.Items.Add(new Separator());

        var alignHeader = new MenuItem
        {
            Header = L.Get("Lyrics_AlignmentHeader"),
            IsEnabled = false
        };
        menu.Items.Add(alignHeader);
        AddCheckableItem(menu, L.Get("Lyrics_AlignLeading"), current.LyricsAlignment == LyricsAlignment.Leading,
            () => ThemeManager.SetLyricsAlignment(Application.Current, LyricsAlignment.Leading));
        AddCheckableItem(menu, L.Get("Lyrics_AlignCenter"), current.LyricsAlignment == LyricsAlignment.Center,
            () => ThemeManager.SetLyricsAlignment(Application.Current, LyricsAlignment.Center));

        menu.PlacementTarget = OptionsButton;
        menu.IsOpen = true;
    }

    private static void AddCheckableItem(ContextMenu menu, string label, bool isSelected, Action onClick)
    {
        menu.Items.Add(TrackMenuHelper.CreateItem(label, isSelected ? "\uE73E" : null, onClick));
    }

    private void SearchOnline_Click(object sender, RoutedEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;
        SearchOnlineRequested?.Invoke(track);
    }

    private void ExportSidecar_Click(object sender, RoutedEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;
        string? audioPath = _services.Library.GetFilePath(track);
        if (string.IsNullOrWhiteSpace(audioPath)) return;

        string? lrcText = _services.Lyrics.ReadRawImportedText(track.Id);
        if (string.IsNullOrWhiteSpace(lrcText) && _lyrics is { Lines.Count: > 0 })
        {
            lrcText = string.Join("\n", _lyrics.Lines.Select(line =>
                line.TimeSeconds is double t
                    ? $"{CloudLyricsFormatter.FormatLrcTimestamp(t)}{line.Text}"
                    : line.Text));
        }
        if (string.IsNullOrWhiteSpace(lrcText)) return;

        try
        {
            _services.Lyrics.ExportSidecarLyrics(lrcText, audioPath);
            InfoOccurred?.Invoke(L.Get("Lyrics_ExportSuccessTitle"), L.Get("Lyrics_ExportSuccessMsg"));
        }
        catch (Exception ex)
        {
            ErrorOccurred?.Invoke(L.Get("Lyrics_CouldNotSaveTitle"), ex.Message);
        }
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;

        var dlg = new OpenFileDialog
        {
            Title = L.Get("Lyrics_ImportDialogTitle"),
            Filter = L.Get("Lyrics_ImportDialogFilter")
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            _services.Lyrics.ImportLyrics(dlg.FileName, track.Id);
            _ = LoadLyricsAsync(forceReload: true);
        }
        catch (Exception ex)
        {
            ErrorOccurred?.Invoke(L.Get("Lyrics_CouldNotImportTitle"), ex.Message);
        }
    }

    private void Remove_Click(object sender, RoutedEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;
        _services.Lyrics.RemoveImported(track.Id);
        _ = LoadLyricsAsync(forceReload: true);
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke();
}
