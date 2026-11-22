using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class QueuesView : UserControl
{
    private ViewContext? _ctx;
    private bool _compact;
    private bool _suppressSortSelection;

    public QueuesView()
    {
        InitializeComponent();
        RebuildSortCombo(selectedIndex: 0);
        Unloaded += OnUnloaded;
    }

    private void RebuildSortCombo(int? selectedIndex = null)
    {
        int prev = selectedIndex ?? Math.Max(0, SortCombo.SelectedIndex);
        _suppressSortSelection = true;
        try
        {
            SortCombo.Items.Clear();
            foreach (var mode in TrackSorter.AllModes)
            {
                SortCombo.Items.Add(TrackSorter.Label(mode));
            }
            if (SortCombo.Items.Count > 0)
                SortCombo.SelectedIndex = Math.Clamp(prev, 0, SortCombo.Items.Count - 1);
        }
        finally
        {
            _suppressSortSelection = false;
        }
    }

    public bool Compact
    {
        get => _compact;
        set
        {
            _compact = value;
            HeaderBorder.Padding = value ? new Thickness(16, 12, 16, 10) : new Thickness(24, 20, 24, 14);
            TitleText.FontSize = value ? 16 : 24;
            QueuesSelectorPanel.Padding = value ? new Thickness(16, 10, 16, 10) : new Thickness(24, 12, 24, 12);
            TracksScroll.Padding = value ? new Thickness(10, 8, 10, 8) : new Thickness(20, 12, 20, 12);
        }
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Queues.PropertyChanged += OnStoreChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        _ctx.Services.Library.PropertyChanged += OnStoreChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Queues.PropertyChanged -= OnStoreChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
        _ctx.Services.Library.PropertyChanged -= OnStoreChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        RebuildSortCombo();
        Refresh();
    }

    private void OnStoreChanged(object? sender, PropertyChangedEventArgs e) => Refresh();

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack)
            or nameof(PlayerController.IsPlaying))
        {
            Refresh();
        }
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var services = _ctx.Services;
        var active = services.Queues.Active;

        SubtitleText.Text = $"{active.Name} · {L.SongsCount(active.TrackIds.Count)}";
        ClearButton.IsEnabled = active.TrackIds.Count > 0;
        DeleteButton.IsEnabled = services.Queues.Queues.Count > 1;
        SortCombo.IsEnabled = active.TrackIds.Count > 1;

        int modeIndex = 0;
        for (int i = 0; i < TrackSorter.AllModes.Count; i++)
        {
            if (TrackSorter.AllModes[i] == active.SortMode)
            {
                modeIndex = i;
                break;
            }
        }
        _suppressSortSelection = true;
        SortCombo.SelectedIndex = modeIndex;
        _suppressSortSelection = false;

        BuildQueueChips(services);
        BuildQueueTracks(services, active);
    }

    private void BuildQueueChips(AppServices services)
    {
        QueueCardsPanel.Children.Clear();
        foreach (var q in services.Queues.Queues)
        {
            bool isActive = q.Id == services.Queues.ActiveId;
            Guid qid = q.Id;

            string summary = L.Get("Queues_EmptyChip");
            if (q.CurrentTrackId is string tid && services.Library.GetTrack(tid) is { } current)
            {
                double pos = isActive ? services.Player.CurrentTimeSeconds : q.PositionSeconds;
                summary = $"{L.SongsCount(q.TrackIds.Count)} · {current.Title} ({TimeFormatter.Format(pos)})";
            }

            var chip = new Border
            {
                Margin = new Thickness(0, 0, 8, 6),
                Padding = new Thickness(10, 6, 10, 6),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(0),
                Cursor = Cursors.Hand,
                MaxWidth = _compact ? 290 : 340
            };
            chip.SetResourceReference(Border.BackgroundProperty, isActive ? "AccentSubtleBrush" : "ContentBackgroundBrush");
            chip.SetResourceReference(Border.BorderBrushProperty, isActive ? "AccentBrush" : "ControlBorderBrush");

            var stack = new StackPanel();
            var topRow = new StackPanel { Orientation = Orientation.Horizontal };
            if (isActive)
            {
                var check = new TextBlock
                {
                    Text = "\uE73E",
                    FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 6, 0)
                };
                check.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
                topRow.Children.Add(check);
            }

            var nameTb = new TextBlock
            {
                Text = q.Name,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 13,
                FontWeight = isActive ? FontWeights.SemiBold : FontWeights.Normal,
                TextTrimming = TextTrimming.CharacterEllipsis
            };
            nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            topRow.Children.Add(nameTb);

            var sumTb = new TextBlock
            {
                Text = summary,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 2, 0, 0)
            };
            sumTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

            stack.Children.Add(topRow);
            stack.Children.Add(sumTb);
            chip.Child = stack;

            chip.MouseLeftButtonUp += (_, _) => services.Player.SwitchQueue(qid);
            chip.MouseEnter += (_, _) =>
            {
                if (!isActive) chip.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
            };
            chip.MouseLeave += (_, _) =>
            {
                chip.SetResourceReference(Border.BorderBrushProperty, isActive ? "AccentBrush" : "ControlBorderBrush");
            };

            var menu = new ContextMenu();
            menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_RenameEllipsis"), "\uE70F", () => PromptRename(q)));
            if (services.Queues.Queues.Count > 1)
            {
                menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_Delete"), "\uE74D", () => services.Player.DeleteQueue(qid)));
            }
            chip.ContextMenu = menu;

            QueueCardsPanel.Children.Add(chip);
        }
    }

    private void BuildQueueTracks(AppServices services, PlayQueue active)
    {
        TracksStack.Children.Clear();
        if (active.TrackIds.Count == 0)
        {
            TracksScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        TracksScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        for (int i = 0; i < active.TrackIds.Count; i++)
        {
            int index = i;
            string id = active.TrackIds[i];
            var track = services.Library.GetTrack(id);
            if (track is null)
            {
                var missing = new TextBlock
                {
                    Text = L.Get("Queues_MissingSong"),
                    Margin = new Thickness(12, 8, 12, 8),
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 13
                };
                missing.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                TracksStack.Children.Add(missing);
                continue;
            }

            bool isCurrent = active.CurrentIndex == index;
            bool isPlaying = isCurrent && services.Player.IsPlaying;
            bool isAvail = services.Library.IsAvailable(track);

            var row = new TrackRowControl();
            row.Bind(
                services,
                track,
                L.DisplayArtist(track.Artist),
                isCurrent,
                isPlaying,
                isAvail,
                () => services.Player.Jump(index),
                _ctx!.OpenTextPrompt,
                menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Queues_RemoveFromQueue"), "\uE74D",
                        () => services.Player.RemoveFromQueue(index)));
                },
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);

            row.EnableDragReorder($"queue:{active.Id:D}", index, active.TrackIds.Count,
                (from, to) => services.Player.MoveInQueue(from, to));
            row.AddRowActionButton("\uE74A", L.Get("Common_MoveUp"), () => services.Player.MoveInQueue(index, index - 1), index > 0);
            row.AddRowActionButton("\uE74B", L.Get("Common_MoveDown"), () => services.Player.MoveInQueue(index, index + 1), index < active.TrackIds.Count - 1);
            row.AddRowActionButton("\uE711", L.Get("Queues_RemoveFromQueue"), () => services.Player.RemoveFromQueue(index));

            TracksStack.Children.Add(row);
        }
    }

    private void SortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSortSelection || _ctx is null) return;
        int idx = SortCombo.SelectedIndex;
        if (idx < 0 || idx >= TrackSorter.AllModes.Count) return;
        _ctx.Services.Player.SortQueue(TrackSorter.AllModes[idx]);
    }

    private void NewQueue_Click(object sender, RoutedEventArgs e)
    {
        _ctx?.Services.Queues.AddQueue(null);
    }

    private void RenameQueue_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        PromptRename(_ctx.Services.Queues.Active);
    }

    private void PromptRename(PlayQueue queue)
    {
        if (_ctx is null) return;
        Guid qid = queue.Id;
        _ctx.OpenTextPrompt(
            L.Get("Queues_RenameTitle"),
            L.Get("Queues_RenameMessage"),
            queue.Name,
            L.Get("Common_Rename"),
            name => _ctx.Services.Queues.Rename(qid, name));
    }

    private void DeleteQueue_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _ctx.Services.Queues.Queues.Count <= 1) return;
        var active = _ctx.Services.Queues.Active;
        _ctx.Services.Player.DeleteQueue(active.Id);
    }

    private void ClearQueue_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var active = _ctx.Services.Queues.Active;
        _ctx.OpenConfirm(
            L.Format("Queues_ClearConfirmTitle", active.Name),
            L.Format("Queues_ClearConfirmMessage", active.Name),
            L.Get("Queues_ClearConfirmAction"),
            () => _ctx.Services.Player.ClearQueue(),
            true);
    }
}
