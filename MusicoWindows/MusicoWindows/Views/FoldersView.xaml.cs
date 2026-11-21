using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using MusicoWindows.Controls;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class FoldersView : UserControl
{
    private ViewContext? _ctx;
    private Guid? _currentRootId;
    private string _currentPath = string.Empty;

    public FoldersView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        Refresh();
    }

    public void ResetToRoots()
    {
        _currentRootId = null;
        _currentPath = string.Empty;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e) => Refresh();

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
        {
            if (_currentRootId != null) Refresh();
        }
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        // Scan banner
        if (lib.IsScanning)
        {
            ScanBanner.Visibility = Visibility.Visible;
            if (lib.ScanTotal > 0)
            {
                ScanStatusText.Text = $"Reading {lib.ScanDone} of {lib.ScanTotal} songs...";
                ScanProgressBar.IsIndeterminate = false;
                ScanProgressBar.Maximum = lib.ScanTotal;
                ScanProgressBar.Value = lib.ScanDone;
            }
            else
            {
                ScanStatusText.Text = "Looking for music...";
                ScanProgressBar.IsIndeterminate = true;
            }
        }
        else
        {
            ScanBanner.Visibility = Visibility.Collapsed;
        }
        RescanButton.IsEnabled = !lib.IsScanning;

        // If drilled root was removed, jump back to roots
        if (_currentRootId is Guid rid && lib.GetRoot(rid) is null)
        {
            _currentRootId = null;
            _currentPath = string.Empty;
        }

        if (_currentRootId is Guid activeRootId)
        {
            RenderFolderContents(activeRootId);
        }
        else
        {
            RenderRoots();
        }
    }

    private void RenderRoots()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        TitleText.Text = "Folders";
        SubtitleText.Text = "Your Music folder is included by default. Add any folder with music, or remove one you don't want.";
        DrillBar.Visibility = Visibility.Collapsed;
        ContentStack.Children.Clear();

        foreach (var root in lib.Roots)
        {
            bool available = lib.IsAvailable(root);
            int count = lib.Tracks.Count(t => t.RootId == root.Id);
            Guid rootId = root.Id;

            var card = new Border
            {
                Margin = new Thickness(0, 0, 0, 8),
                Padding = new Thickness(16, 14, 16, 14),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(0),
                Cursor = Cursors.Hand
            };
            card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new TextBlock
            {
                Text = root.Kind == RootKind.AppFolder ? "\uE8D6" : "\uE8B7",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 20,
                VerticalAlignment = VerticalAlignment.Center
            };
            icon.SetResourceReference(TextBlock.ForegroundProperty, available ? "AccentBrush" : "WarningTextBrush");
            Grid.SetColumn(icon, 0);

            var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 12, 0) };
            var nameTb = new TextBlock
            {
                Text = root.Name,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 15,
                FontWeight = FontWeights.SemiBold
            };
            nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");

            var detailTb = new TextBlock
            {
                Text = available
                    ? $"{count} {(count == 1 ? "song" : "songs")} · {root.Path}"
                    : "Unavailable — folder moved or disconnected",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 3, 0, 0)
            };
            detailTb.SetResourceReference(TextBlock.ForegroundProperty, available ? "TextSecondaryBrush" : "WarningTextBrush");

            info.Children.Add(nameTb);
            info.Children.Add(detailTb);
            Grid.SetColumn(info, 1);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            if (!available && root.Kind == RootKind.Picked)
            {
                var locateBtn = new Button
                {
                    Content = "Locate Again...",
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    Margin = new Thickness(0, 0, 8, 0)
                };
                locateBtn.Click += async (_, e) =>
                {
                    e.Handled = true;
                    await PickAndRelocateAsync(rootId);
                };
                actions.Children.Add(locateBtn);
            }

            var removeBtn = new Button
            {
                Content = "\uE74D",
                ToolTip = "Remove from Library",
                Style = (Style)FindResource("Win10IconButtonStyle")
            };
            removeBtn.Click += (_, e) =>
            {
                e.Handled = true;
                ConfirmRemoveRoot(root);
            };
            actions.Children.Add(removeBtn);
            Grid.SetColumn(actions, 2);

            grid.Children.Add(icon);
            grid.Children.Add(info);
            grid.Children.Add(actions);
            card.Child = grid;

            card.MouseEnter += (_, _) =>
            {
                card.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
                card.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
            };
            card.MouseLeave += (_, _) =>
            {
                card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
                card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");
            };
            card.MouseLeftButtonUp += (_, _) =>
            {
                _currentRootId = rootId;
                _currentPath = string.Empty;
                Refresh();
            };

            var rootTrackIds = lib.GetTracksUnder(rootId, string.Empty).Select(t => t.Id).ToList();
            card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                rootTrackIds,
                null,
                _ctx.OpenTextPrompt,
                menu =>
                {
                    menu.Items.Add(new Separator());
                    if (root.Kind == RootKind.Picked)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem("Locate Again...", "\uE8DA", async () => await PickAndRelocateAsync(rootId)));
                    }
                    menu.Items.Add(TrackMenuHelper.CreateItem("Remove from Library", "\uE74D", () => ConfirmRemoveRoot(root)));
                });

            ContentStack.Children.Add(card);
        }

        if (lib.AppFolderRemoved)
        {
            var restoreBtn = new Button
            {
                Style = (Style)FindResource("Win10ButtonStyle"),
                HorizontalAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0, 8, 0, 0)
            };
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(new TextBlock
            {
                Text = "\uE8D6",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 14,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 8, 0)
            });
            sp.Children.Add(new TextBlock { Text = "Restore Music Folder", VerticalAlignment = VerticalAlignment.Center });
            restoreBtn.Content = sp;
            restoreBtn.Click += async (_, _) => await lib.RestoreAppFolderAsync();
            ContentStack.Children.Add(restoreBtn);
        }
    }

    private void RenderFolderContents(Guid rootId)
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        var root = lib.GetRoot(rootId);
        if (root is null) return;

        string leafName = string.IsNullOrEmpty(_currentPath)
            ? root.Name
            : _currentPath.Split('/').Last();
        TitleText.Text = leafName;
        SubtitleText.Text = root.Path ?? string.Empty;
        DrillBar.Visibility = Visibility.Visible;

        BuildBreadcrumbs(root);

        var (subfolders, directTracks) = lib.GetFolderContents(rootId, _currentPath);
        var allTracksUnder = lib.GetTracksUnder(rootId, _currentPath);

        PlayFolderButton.IsEnabled = allTracksUnder.Count > 0;
        ShuffleFolderButton.IsEnabled = allTracksUnder.Count > 0;

        ContentStack.Children.Clear();

        if (allTracksUnder.Count == 0 && subfolders.Count == 0)
        {
            var empty = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 48, 0, 0) };
            empty.Children.Add(new TextBlock
            {
                Text = "\uE8B7",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 36,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 10)
            });
            var t1 = new TextBlock
            {
                Text = "No Music Here",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                HorizontalAlignment = HorizontalAlignment.Center
            };
            t1.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            var t2 = new TextBlock
            {
                Text = "Songs you add to this folder appear after a rescan.",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 13,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 4, 0, 0)
            };
            t2.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            empty.Children.Add(t1);
            empty.Children.Add(t2);
            ContentStack.Children.Add(empty);
            return;
        }

        // Subfolders
        foreach (string folderName in subfolders)
        {
            string childPath = string.IsNullOrEmpty(_currentPath) ? folderName : $"{_currentPath}/{folderName}";
            var folderTracks = lib.GetTracksUnder(rootId, childPath);

            var row = new Border
            {
                Background = Brushes.Transparent,
                BorderBrush = Brushes.Transparent,
                BorderThickness = new Thickness(1),
                Padding = new Thickness(12, 10, 12, 10),
                Margin = new Thickness(0, 0, 0, 2),
                Cursor = Cursors.Hand
            };

            var rowGrid = new Grid();
            rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(32) });
            rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var icon = new TextBlock
            {
                Text = "\uE8B7",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 16,
                VerticalAlignment = VerticalAlignment.Center
            };
            icon.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");

            var nameTb = new TextBlock
            {
                Text = folderName,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 14,
                VerticalAlignment = VerticalAlignment.Center
            };
            nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            Grid.SetColumn(nameTb, 1);

            var countTb = new TextBlock
            {
                Text = $"{folderTracks.Count} {(folderTracks.Count == 1 ? "song" : "songs")}",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                VerticalAlignment = VerticalAlignment.Center
            };
            countTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            Grid.SetColumn(countTb, 2);

            rowGrid.Children.Add(icon);
            rowGrid.Children.Add(nameTb);
            rowGrid.Children.Add(countTb);
            row.Child = rowGrid;

            row.MouseEnter += (_, _) =>
            {
                row.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
                row.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
            };
            row.MouseLeave += (_, _) =>
            {
                row.Background = Brushes.Transparent;
                row.BorderBrush = Brushes.Transparent;
            };
            row.MouseLeftButtonUp += (_, _) =>
            {
                _currentPath = childPath;
                Refresh();
            };

            row.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                folderTracks.Select(t => t.Id).ToList(),
                null,
                _ctx.OpenTextPrompt);

            ContentStack.Children.Add(row);
        }

        // Direct tracks in this folder
        var directIds = directTracks.Select(t => t.Id).ToList();
        for (int i = 0; i < directTracks.Count; i++)
        {
            int idx = i;
            var track = directTracks[i];
            bool isCurrent = _ctx.Services.Player.CurrentTrack?.Id == track.Id;
            bool isPlaying = isCurrent && _ctx.Services.Player.IsPlaying;
            bool isAvail = lib.IsAvailable(track);

            var row = new TrackRowControl();
            row.Bind(
                _ctx.Services,
                track,
                track.FileName,
                isCurrent,
                isPlaying,
                isAvail,
                () => _ctx.Services.Player.Play(directIds, idx),
                _ctx.OpenTextPrompt);
            ContentStack.Children.Add(row);
        }
    }

    private void BuildBreadcrumbs(LibraryRoot root)
    {
        BreadcrumbPanel.Children.Clear();

        void AddCrumb(string text, Action onClick, bool isLast)
        {
            if (isLast)
            {
                var tb = new TextBlock
                {
                    Text = text,
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 13,
                    FontWeight = FontWeights.SemiBold,
                    VerticalAlignment = VerticalAlignment.Center,
                    Padding = new Thickness(4, 2, 4, 2)
                };
                tb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
                BreadcrumbPanel.Children.Add(tb);
            }
            else
            {
                var btn = new Button
                {
                    Content = text,
                    Style = (Style)FindResource("Win10FlatButtonStyle"),
                    Padding = new Thickness(6, 3, 6, 3),
                    FontSize = 13
                };
                btn.Click += (_, _) => onClick();
                BreadcrumbPanel.Children.Add(btn);

                var sep = new TextBlock
                {
                    Text = "\uE76C",
                    FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                    FontSize = 10,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(2, 0, 2, 0)
                };
                sep.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
                BreadcrumbPanel.Children.Add(sep);
            }
        }

        string[] segments = string.IsNullOrEmpty(_currentPath) ? Array.Empty<string>() : _currentPath.Split('/');
        AddCrumb("Folders", ResetToRoots, false);
        AddCrumb(root.Name, () => { _currentPath = string.Empty; Refresh(); }, segments.Length == 0);

        string accum = string.Empty;
        for (int i = 0; i < segments.Length; i++)
        {
            accum = i == 0 ? segments[i] : $"{accum}/{segments[i]}";
            string target = accum;
            AddCrumb(segments[i], () => { _currentPath = target; Refresh(); }, i == segments.Length - 1);
        }
    }

    private async void AddFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var dlg = new OpenFolderDialog
        {
            Title = "Select Music Folder"
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            await _ctx.Services.Library.AddRootAsync(dlg.FolderName);
        }
        catch (Exception ex)
        {
            _ctx.OpenConfirm("Couldn't Add Folder", ex.Message, "OK", null, false);
        }
    }

    private async System.Threading.Tasks.Task PickAndRelocateAsync(Guid rootId)
    {
        if (_ctx is null) return;
        var dlg = new OpenFolderDialog
        {
            Title = "Locate Music Folder"
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            await _ctx.Services.Library.RelocateRootAsync(rootId, dlg.FolderName);
        }
        catch (Exception ex)
        {
            _ctx.OpenConfirm("Couldn't Locate Folder", ex.Message, "OK", null, false);
        }
    }

    private void ConfirmRemoveRoot(LibraryRoot root)
    {
        if (_ctx is null) return;
        _ctx.OpenConfirm(
            $"Remove \"{root.Name}\" from your library?",
            "Its songs leave your library, playlists and queues. The files themselves are not deleted.",
            "Remove from Library",
            () => _ctx.Services.Library.RemoveRoot(root.Id),
            true);
    }

    private async void Rescan_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        await _ctx.Services.Library.RescanAsync();
    }

    private void PlayFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var ids = _ctx.Services.Library.GetTracksUnder(rid, _currentPath).Select(t => t.Id).ToList();
        if (ids.Count == 0) return;
        if (_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, 0);
    }

    private void ShuffleFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var ids = _ctx.Services.Library.GetTracksUnder(rid, _currentPath).Select(t => t.Id).ToList();
        if (ids.Count == 0) return;
        if (!_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, Random.Shared.Next(ids.Count));
    }
}
