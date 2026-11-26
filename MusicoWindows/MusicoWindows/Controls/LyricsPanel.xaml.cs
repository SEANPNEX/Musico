using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
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
    private readonly DispatcherTimer _manualScrollGraceTimer;
    private AppServices? _services;
    private Lyrics? _lyrics;
    private LyricsSource? _source;
    private int? _activeLineIndex;
    private string? _loadedTrackId;
    private bool _embedded;
    private bool _isManualScrolling;
    private int _programmaticScrollDepth;

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
        var track = _services?.Player.CurrentTrack;
        HeaderTitleText.Text = track?.Title ?? L.Get("Lyrics_Title");
        UpdateSourceTagText();
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
            SourceTagText.Visibility = Visibility.Visible;
        }
        else
        {
            SourceTagText.Visibility = Visibility.Collapsed;
        }
    }

    public bool Embedded
    {
        get => _embedded;
        set
        {
            _embedded = value;
            CloseButton.Visibility = value ? Visibility.Collapsed : Visibility.Visible;
        }
    }

    public void Initialize(AppServices services)
    {
        if (_services != null)
        {
            _services.Player.PropertyChanged -= OnPlayerChanged;
            _services.Lyrics.LyricsChanged -= OnLyricsStoreChanged;
        }
        _services = services;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Lyrics.LyricsChanged += OnLyricsStoreChanged;
        _ = LoadLyricsAsync(forceReload: true);
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

        if (!forceReload && track?.Id == _loadedTrackId)
        {
            UpdateActiveLine();
            return;
        }

        _loadedTrackId = track?.Id;
        _activeLineIndex = null;
        EndManualScrollGracePeriod(scrollToActive: false);
        HeaderTitleText.Text = track?.Title ?? L.Get("Lyrics_Title");
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
        double baseSize = _lyrics.IsTimed ? timed : untimed;
        for (int i = 0; i < _lineBlocks.Count; i++)
        {
            var tb = _lineBlocks[i];
            bool isActive = _lyrics.IsTimed && _activeLineIndex == i;
            tb.FontSize = isActive ? baseSize + 2 : baseSize;
            tb.TextAlignment = align;
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
                FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                FontSize = fontSize,
                TextAlignment = align,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 0, 0, _lyrics.IsTimed ? 16 : 10)
            };

            if (_lyrics.IsTimed && line.TimeSeconds is double seekTime)
            {
                tb.Cursor = Cursors.Hand;
                tb.Opacity = 0.72;
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                tb.MouseLeftButtonUp += (_, _) =>
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

            LinesStack.Children.Add(tb);
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
            var block = _lineBlocks[i];
            if (nextIndex is int activeIdx)
            {
                int distance = Math.Abs(i - activeIdx);
                if (distance == 0)
                {
                    block.FontWeight = FontWeights.SemiBold;
                    block.FontSize = timedSize + 2;
                    block.Opacity = 1.0;
                    block.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
                }
                else
                {
                    block.FontWeight = FontWeights.Normal;
                    block.FontSize = timedSize;
                    block.Opacity = distance switch
                    {
                        1 => 0.72,
                        2 => 0.50,
                        _ => 0.34
                    };
                    block.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                }
            }
            else
            {
                block.FontWeight = FontWeights.Normal;
                block.FontSize = timedSize;
                block.Opacity = 0.78;
                block.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            }
        }

        if (nextIndex is int targetIdx && !_isManualScrolling)
        {
            ScrollToLine(targetIdx);
        }
    }

    private void ScrollToLine(int lineIndex)
    {
        if (lineIndex < 0 || lineIndex >= _lineBlocks.Count) return;
        var activeBlock = _lineBlocks[lineIndex];

        Dispatcher.BeginInvoke(() =>
        {
            if (LyricsScroll.ViewportHeight <= 0) return;
            try
            {
                var transform = activeBlock.TransformToAncestor(LinesStack);
                var point = transform.Transform(new Point(0, 0));
                double targetOffset = point.Y - (LyricsScroll.ViewportHeight / 2.0) + (activeBlock.ActualHeight / 2.0);
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
