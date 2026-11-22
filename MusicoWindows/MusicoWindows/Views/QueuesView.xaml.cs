using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class QueuesView : UserControl
{
    private ViewContext? _ctx;
    private bool _compact;

    public QueuesView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
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
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        if (_ctx is null) return;
        _ctx.Services.Queues.PropertyChanged -= OnStoreChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
        _ctx.Services.Library.PropertyChanged -= OnStoreChanged;
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

        SubtitleText.Text = $"{active.Name} · {active.TrackIds.Count} {(active.TrackIds.Count == 1 ? "song" : "songs")}";
        ClearButton.IsEnabled = active.TrackIds.Count > 0;
        DeleteButton.IsEnabled = services.Queues.Queues.Count > 1;

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

            string summary = "Empty";
            if (q.CurrentTrackId is string tid && services.Library.GetTrack(tid) is { } current)
            {
                double pos = isActive ? services.Player.CurrentTimeSeconds : q.PositionSeconds;
                summary = $"{q.TrackIds.Count} songs · {current.Title} ({TimeFormatter.Format(pos)})";
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
            menu.Items.Add(TrackMenuHelper.CreateItem("Rename...", "\uE70F", () => PromptRename(q)));
            if (services.Queues.Queues.Count > 1)
            {
                menu.Items.Add(TrackMenuHelper.CreateItem("Delete", "\uE74D", () => services.Player.DeleteQueue(qid)));
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
                    Text = "Missing song",
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
                track.Artist,
                isCurrent,
                isPlaying,
                isAvail,
                () => services.Player.Jump(index),
                _ctx!.OpenTextPrompt,
                menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem("Remove from Queue", "\uE74D",
                        () => services.Player.RemoveFromQueue(index)));
                },
                _ctx.OpenSaveCloudTrack);

            row.AddRowActionButton("\uE74A", "Move Up", () => services.Player.MoveInQueue(index, index - 1), index > 0);
            row.AddRowActionButton("\uE74B", "Move Down", () => services.Player.MoveInQueue(index, index + 1), index < active.TrackIds.Count - 1);
            row.AddRowActionButton("\uE711", "Remove from Queue", () => services.Player.RemoveFromQueue(index));

            TracksStack.Children.Add(row);
        }
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
            "Rename Queue",
            "Enter a new name for this queue.",
            queue.Name,
            "Rename",
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
            $"Clear \"{active.Name}\"?",
            $"Remove every song from \"{active.Name}\"? The queue itself will remain.",
            "Clear Queue",
            () => _ctx.Services.Player.ClearQueue(),
            true);
    }
}
