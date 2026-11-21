using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Microsoft.Win32;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Controls;

public partial class LyricsPanel : UserControl
{
    private readonly List<TextBlock> _lineBlocks = new();
    private AppServices? _services;
    private Lyrics? _lyrics;
    private LyricsSource? _source;
    private int? _activeLineIndex;
    private string? _loadedTrackId;
    private bool _embedded;

    public event Action? CloseRequested;
    public event Action<string, string>? ErrorOccurred;

    public LyricsPanel()
    {
        InitializeComponent();
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
        }
        _services = services;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _ = LoadLyricsAsync(forceReload: true);
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
        HeaderTitleText.Text = track?.Title ?? "Lyrics";
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
            embedded);

        _lyrics = resolved?.Lyrics;
        _source = resolved?.Source;
        RenderLyrics();
    }

    private void RenderLyrics()
    {
        LinesStack.Children.Clear();
        _lineBlocks.Clear();

        bool hasTrack = _services?.Player.CurrentTrack != null;
        bool hasImported = hasTrack && _services!.Lyrics.HasImported(_services.Player.CurrentTrack!.Id);
        RemoveButton.Visibility = hasImported ? Visibility.Visible : Visibility.Collapsed;

        if (_source is LyricsSource src)
        {
            SourceTagText.Text = src switch
            {
                LyricsSource.Imported => "From imported file",
                LyricsSource.Sidecar => "From sidecar file next to song",
                LyricsSource.Embedded => "From song tags",
                _ => string.Empty
            };
            SourceTagText.Visibility = Visibility.Visible;
        }
        else
        {
            SourceTagText.Visibility = Visibility.Collapsed;
        }

        if (_lyrics is null || _lyrics.Lines.Count == 0)
        {
            LyricsScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        LyricsScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        foreach (var line in _lyrics.Lines)
        {
            var tb = new TextBlock
            {
                Text = string.IsNullOrEmpty(line.Text) ? "♪" : line.Text,
                FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                FontSize = _lyrics.IsTimed ? 16 : 14,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 0, 0, _lyrics.IsTimed ? 16 : 10)
            };

            if (_lyrics.IsTimed && line.TimeSeconds is double seekTime)
            {
                tb.Cursor = Cursors.Hand;
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                tb.MouseLeftButtonUp += (_, _) => _services?.Player.Seek(seekTime);
            }
            else
            {
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            }

            LinesStack.Children.Add(tb);
            _lineBlocks.Add(tb);
        }

        UpdateActiveLine();
    }

    private void UpdateActiveLine()
    {
        if (_services is null || _lyrics is null || !_lyrics.IsTimed) return;

        int? nextIndex = _lyrics.LineIndexAt(_services.Player.CurrentTimeSeconds);
        if (nextIndex == _activeLineIndex) return;

        if (_activeLineIndex is int oldIdx && oldIdx >= 0 && oldIdx < _lineBlocks.Count)
        {
            _lineBlocks[oldIdx].FontWeight = FontWeights.Normal;
            _lineBlocks[oldIdx].SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        }

        _activeLineIndex = nextIndex;

        if (nextIndex is int newIdx && newIdx >= 0 && newIdx < _lineBlocks.Count)
        {
            var activeBlock = _lineBlocks[newIdx];
            activeBlock.FontWeight = FontWeights.SemiBold;
            activeBlock.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");

            // Smoothly scroll the active lyric line toward the vertical center of the scroll viewport
            Dispatcher.BeginInvoke(() =>
            {
                if (LyricsScroll.ViewportHeight <= 0) return;
                try
                {
                    var transform = activeBlock.TransformToAncestor(LinesStack);
                    var point = transform.Transform(new Point(0, 0));
                    double targetOffset = point.Y - (LyricsScroll.ViewportHeight / 2.0) + (activeBlock.ActualHeight / 2.0);
                    LyricsScroll.ScrollToVerticalOffset(Math.Max(0, targetOffset));
                }
                catch
                {
                    // Element not yet measured
                }
            });
        }
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;

        var dlg = new OpenFileDialog
        {
            Title = "Import Lyrics",
            Filter = "Lyrics Files (*.lrc;*.txt)|*.lrc;*.txt|All Files (*.*)|*.*"
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            _services.Lyrics.ImportLyrics(dlg.FileName, track.Id);
            _ = LoadLyricsAsync(forceReload: true);
        }
        catch (Exception ex)
        {
            ErrorOccurred?.Invoke("Couldn't Import Lyrics", ex.Message);
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
