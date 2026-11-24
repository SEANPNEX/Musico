using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class CloudLyricsDialog : UserControl
{
    private AppServices? _services;
    private Track? _track;
    private string? _audioPath;
    private CloudLyricsSourceSelection _selectedSource = CloudLyricsSourceSelection.Auto;
    private List<CloudLyricsCandidate> _candidates = new();
    private CloudLyricsCandidate? _selectedCandidate;
    private CancellationTokenSource? _searchCts;
    private bool _isSearching;

    public CloudLyricsDialog()
    {
        InitializeComponent();
        LocalizationManager.LanguageChanged += _ =>
        {
            if (IsOpen)
            {
                BuildSourceChips();
                RenderCandidates();
                RenderPreview();
            }
        };
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services, Track track)
    {
        _searchCts?.Cancel();
        _services = services;
        _track = track;
        _audioPath = services.Library.GetFilePath(track);
        _selectedSource = CloudLyricsSourceSelection.Auto;
        _candidates = new();
        _selectedCandidate = null;
        _isSearching = false;

        TitleBox.Text = track.Title;
        ArtistBox.Text = string.Equals(track.Artist, "Unknown Artist", StringComparison.OrdinalIgnoreCase)
            ? string.Empty
            : track.Artist;

        bool canExport = services.Lyrics.CanExportSidecar(_audioPath);
        IncludeTranslationCheck.IsChecked = true;
        ExportSidecarCheck.IsChecked = canExport;
        ExportSidecarCheck.Visibility = canExport
            ? Visibility.Visible
            : Visibility.Collapsed;

        SaveErrorText.Visibility = Visibility.Collapsed;
        SaveErrorText.Text = string.Empty;
        PartialWarningText.Visibility = Visibility.Collapsed;

        BuildSourceChips();
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() => TitleBox.Focus());

        _ = ExecuteSearchAsync();
    }

    public void Close()
    {
        _searchCts?.Cancel();
        _searchCts = null;
        _isSearching = false;
        Visibility = Visibility.Collapsed;
    }

    private void BuildSourceChips()
    {
        SourceChipsPanel.Children.Clear();
        if (_services is null) return;

        var activeExts = _services.Extensions.GetActiveLyricsExtensions();
        var options = CloudLyricsService.BuildSourceOptions(activeExts, L.Get("CloudLyrics_SourceAuto"));

        foreach (var opt in options)
        {
            bool isSelected = opt.Selection == _selectedSource;
            var btn = new Button
            {
                Style = (Style)FindResource(isSelected ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle"),
                Content = opt.Label,
                Padding = new Thickness(12, 4, 12, 4),
                Margin = new Thickness(0, 0, 8, 0),
                FontSize = 12
            };
            var capturedSelection = opt.Selection;
            btn.Click += (_, _) =>
            {
                if (_selectedSource != capturedSelection)
                {
                    _selectedSource = capturedSelection;
                    BuildSourceChips();
                    _ = ExecuteSearchAsync();
                }
            };
            SourceChipsPanel.Children.Add(btn);
        }
    }

    private async Task ExecuteSearchAsync()
    {
        if (_services is null || _track is null) return;

        _searchCts?.Cancel();
        var cts = new CancellationTokenSource();
        _searchCts = cts;

        var query = new CloudLyricsQuery(
            TitleBox.Text,
            ArtistBox.Text,
            string.IsNullOrWhiteSpace(_track.Album) || string.Equals(_track.Album, "Unknown Album", StringComparison.OrdinalIgnoreCase)
                ? null
                : _track.Album,
            _track.DurationSeconds > 0 ? _track.DurationSeconds : null);

        if (query.IsEmpty)
        {
            _candidates.Clear();
            _selectedCandidate = null;
            ShowStatus(L.Get("CloudLyrics_EmptyTitle"), L.Get("CloudLyrics_EmptyDesc"), showProgress: false, showRetry: false);
            RenderPreview();
            UseLyricsButton.IsEnabled = false;
            return;
        }

        _isSearching = true;
        SearchButton.IsEnabled = false;
        UseLyricsButton.IsEnabled = false;
        PartialWarningText.Visibility = Visibility.Collapsed;
        CandidatesPanel.Children.Clear();
        ShowStatus(L.Get("CloudLyrics_Searching"), string.Empty, showProgress: true, showRetry: false);

        try
        {
            var activeExts = _services.Extensions.GetActiveLyricsExtensions();
            var response = await _services.CloudLyrics.SearchAsync(
                query,
                _selectedSource,
                activeExts,
                proxyUrl: null,
                ct: cts.Token);

            if (cts.IsCancellationRequested || _searchCts != cts) return;

            _candidates = response.Candidates.ToList();
            if (response.Failures.Count > 0 && _candidates.Count > 0)
            {
                PartialWarningText.Text = L.Get("CloudLyrics_PartialFailureHint");
                PartialWarningText.Visibility = Visibility.Visible;
            }

            if (_selectedCandidate is not null)
            {
                _selectedCandidate = _candidates.FirstOrDefault(c => c.Id == _selectedCandidate.Id)
                                     ?? _candidates.FirstOrDefault();
            }
            else
            {
                _selectedCandidate = _candidates.FirstOrDefault();
            }

            if (_candidates.Count == 0)
            {
                ShowStatus(L.Get("CloudLyrics_EmptyTitle"), L.Get("CloudLyrics_EmptyDesc"), showProgress: false, showRetry: false);
            }
            else
            {
                StatusOverlayPanel.Visibility = Visibility.Collapsed;
                RenderCandidates();
            }
            RenderPreview();
        }
        catch (OperationCanceledException) when (cts.IsCancellationRequested)
        {
            return;
        }
        catch (Exception ex)
        {
            if (cts.IsCancellationRequested || _searchCts != cts) return;
            _candidates.Clear();
            _selectedCandidate = null;
            ShowStatus(L.Get("Lyrics_Title"), ex.Message, showProgress: false, showRetry: true);
            RenderPreview();
        }
        finally
        {
            if (_searchCts == cts)
            {
                _isSearching = false;
                SearchButton.IsEnabled = true;
                UseLyricsButton.IsEnabled = _selectedCandidate is not null;
            }
        }
    }

    private void ShowStatus(string title, string desc, bool showProgress, bool showRetry)
    {
        StatusOverlayPanel.Visibility = Visibility.Visible;
        SearchProgress.Visibility = showProgress ? Visibility.Visible : Visibility.Collapsed;
        StatusTitleText.Text = title;
        StatusDescText.Text = desc;
        StatusDescText.Visibility = string.IsNullOrWhiteSpace(desc) ? Visibility.Collapsed : Visibility.Visible;
        RetryButton.Content = L.Get("CloudLyrics_Retry");
        RetryButton.Visibility = showRetry ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RenderCandidates()
    {
        CandidatesPanel.Children.Clear();
        double? targetDur = _track?.DurationSeconds is > 0 ? _track.DurationSeconds : null;

        foreach (var cand in _candidates)
        {
            bool isSelected = _selectedCandidate?.Id == cand.Id;
            var card = new Border
            {
                Padding = new Thickness(12, 10, 12, 10),
                BorderThickness = new Thickness(0, 0, 0, 1),
                Cursor = Cursors.Hand
            };
            card.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");
            card.SetResourceReference(
                Border.BackgroundProperty,
                isSelected ? "AccentSubtleBrush" : "SubtleFillBrush");

            var stack = new StackPanel();
            var titleBlock = new TextBlock
            {
                Text = string.IsNullOrWhiteSpace(cand.Title) ? (_track?.Title ?? string.Empty) : cand.Title,
                FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            titleBlock.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            stack.Children.Add(titleBlock);

            string sub = string.Join(" · ", new[] { cand.Artist, cand.Album }.Where(s => !string.IsNullOrWhiteSpace(s)));
            if (!string.IsNullOrEmpty(sub))
            {
                var subBlock = new TextBlock
                {
                    Text = sub,
                    FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                    FontSize = 11,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    Margin = new Thickness(0, 2, 0, 4)
                };
                subBlock.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                stack.Children.Add(subBlock);
            }

            var metaRow = new WrapPanel();
            AddBadge(metaRow, cand.SourceName);
            AddBadge(metaRow, FormatKindText(cand));
            if (cand.HasTranslation)
            {
                AddBadge(metaRow, L.Get("CloudLyrics_TranslatedBadge"));
            }

            string? dur = CloudLyricsFormatter.FormatDurationAndDelta(cand.DurationSec, targetDur);
            if (!string.IsNullOrEmpty(dur))
            {
                var durBlock = new TextBlock
                {
                    Text = dur,
                    FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(6, 0, 0, 0)
                };
                durBlock.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                metaRow.Children.Add(durBlock);
            }

            stack.Children.Add(metaRow);
            card.Child = stack;

            var captured = cand;
            card.MouseLeftButtonUp += (_, _) =>
            {
                _selectedCandidate = captured;
                RenderCandidates();
                RenderPreview();
                UseLyricsButton.IsEnabled = !_isSearching;
            };
            CandidatesPanel.Children.Add(card);
        }
    }

    private void AddBadge(Panel parent, string text)
    {
        var border = new Border
        {
            Padding = new Thickness(6, 2, 6, 2),
            Margin = new Thickness(0, 2, 6, 2),
            CornerRadius = new CornerRadius(2)
        };
        border.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
        var tb = new TextBlock
        {
            Text = text,
            FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        tb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
        border.Child = tb;
        parent.Children.Add(border);
    }

    private static string FormatKindText(CloudLyricsCandidate c)
    {
        if (c.IsTimed) return L.Get("CloudLyrics_FormatSynced");
        if (c.IsInstrumental) return L.Get("CloudLyrics_FormatInstrumental");
        return L.Get("CloudLyrics_FormatPlain");
    }

    private void RenderPreview()
    {
        if (_selectedCandidate is null)
        {
            PreviewHeaderText.Text = string.Empty;
            IncludeTranslationCheck.Visibility = Visibility.Collapsed;
            PreviewBodyText.Text = L.Get("CloudLyrics_SelectPreview");
            return;
        }

        PreviewHeaderText.Text = $"{_selectedCandidate.SourceName} · {FormatKindText(_selectedCandidate)}";
        IncludeTranslationCheck.Visibility = _selectedCandidate.HasTranslation
            ? Visibility.Visible
            : Visibility.Collapsed;

        bool includeTrans = IncludeTranslationCheck.IsChecked == true;
        string raw = _selectedCandidate.FormattedText(includeTrans);
        Lyrics parsed = LrcParser.Parse(raw);
        if (parsed.Lines.Count == 0)
        {
            PreviewBodyText.Text = raw;
        }
        else
        {
            PreviewBodyText.Text = string.Join(
                "\n",
                parsed.Lines.Select(l => string.IsNullOrEmpty(l.Text) ? "♪" : l.Text));
        }
    }

    private void IncludeTranslation_Changed(object sender, RoutedEventArgs e) => RenderPreview();

    private void Search_Click(object sender, RoutedEventArgs e) => _ = ExecuteSearchAsync();

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            _ = ExecuteSearchAsync();
            e.Handled = true;
        }
    }

    private void Confirm_Click(object sender, RoutedEventArgs e)
    {
        if (_services is null || _track is null || _selectedCandidate is null) return;
        SaveErrorText.Visibility = Visibility.Collapsed;
        bool includeTrans = IncludeTranslationCheck.IsChecked == true;
        string text = _selectedCandidate.FormattedText(includeTrans);
        try
        {
            _services.Lyrics.SaveImportedLyrics(text, _track.Id);
            if (ExportSidecarCheck.IsChecked == true && !string.IsNullOrWhiteSpace(_audioPath))
            {
                _services.Lyrics.ExportSidecarLyrics(text, _audioPath);
            }
            Close();
        }
        catch (Exception ex)
        {
            SaveErrorText.Text = ex.Message;
            SaveErrorText.Visibility = Visibility.Visible;
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
