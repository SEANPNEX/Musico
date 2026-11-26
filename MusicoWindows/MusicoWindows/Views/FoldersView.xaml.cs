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
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Views;

public partial class FoldersView : UserControl
{
    private ViewContext? _ctx;
    private Guid? _currentRootId;
    private string _currentPath = string.Empty;
    private bool _suppressSortSelection;

    public FoldersView()
    {
        InitializeComponent();
        RebuildSortCombo(selectedIndex: 1);
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

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    public void ResetToRoots()
    {
        bool wasDrilled = _currentRootId is not null;
        _currentRootId = null;
        _currentPath = string.Empty;
        Refresh();
        if (wasDrilled)
        {
            Win10Animations.AnimateSlideX(ContentStack, -24);
        }
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        RebuildSortCombo();
        Refresh();
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
                ScanStatusText.Text = L.Format("Folders_ScanningProgress", lib.ScanDone, lib.ScanTotal);
                ScanProgressBar.IsIndeterminate = false;
                ScanProgressBar.Maximum = lib.ScanTotal;
                ScanProgressBar.Value = lib.ScanDone;
            }
            else
            {
                ScanStatusText.Text = L.Get("Folders_ScanningDefault");
                ScanProgressBar.IsIndeterminate = true;
            }
        }
        else if (_currentRootId is Guid activeSyncRootId &&
                 lib.GetCloudSyncProgress(activeSyncRootId) is { IsCompleted: false } activeSync)
        {
            ScanBanner.Visibility = Visibility.Visible;
            if (activeSync.FoldersTotal > 0)
            {
                ScanStatusText.Text = L.Format(
                    "Folders_CloudSyncProgress",
                    activeSync.FoldersDone,
                    activeSync.FoldersTotal,
                    L.SongsCount(activeSync.TracksDiscovered));
                ScanProgressBar.IsIndeterminate = false;
                ScanProgressBar.Maximum = activeSync.FoldersTotal;
                ScanProgressBar.Value = activeSync.FoldersDone;
            }
            else
            {
                ScanStatusText.Text = L.Get("Folders_CloudSyncLoadingTitle");
                ScanProgressBar.IsIndeterminate = true;
            }
        }
        else
        {
            ScanBanner.Visibility = Visibility.Collapsed;
        }
        RescanButton.IsEnabled = !lib.IsScanning;

        // If drilled root was removed, jump back to roots
        if (_currentRootId is Guid rid)
        {
            var activeRoot = lib.GetRoot(rid);
            if (activeRoot is null)
            {
                _currentRootId = null;
                _currentPath = string.Empty;
            }
            else
            {
                while (!string.IsNullOrEmpty(_currentPath) && activeRoot.IsExcluded(_currentPath))
                {
                    int slash = _currentPath.LastIndexOf('/');
                    _currentPath = slash >= 0 ? _currentPath[..slash] : string.Empty;
                }
            }
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

        TitleText.Text = L.Get("Folders_Title");
        SubtitleText.Text = L.Get("Folders_RootsSubtitle");
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
                CornerRadius = new CornerRadius(4),
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
                Text = root.Kind == RootKind.AppFolder
                    ? "\uE8D6"
                    : root.IsCloud
                        ? "\uE753"
                        : "\uE8B7",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 20,
                VerticalAlignment = VerticalAlignment.Center
            };
            icon.SetResourceReference(TextBlock.ForegroundProperty, available ? "AccentBrush" : "WarningTextBrush");
            Grid.SetColumn(icon, 0);

            var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 12, 0) };
            var titleRow = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            var nameTb = new TextBlock
            {
                Text = L.DisplayRootName(root),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 15,
                FontWeight = FontWeights.SemiBold,
                VerticalAlignment = VerticalAlignment.Center
            };
            nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            titleRow.Children.Add(nameTb);

            if (root.IsCloud || root.IsCloudTrackedLocal)
            {
                titleRow.Children.Add(CreateCloudBadge(root.EffectiveProviderId, root.EffectiveCloudKind ?? root.Kind, isLocal: !root.IsCloud));
            }

            string locationDisplay = root.IsCloud
                ? (root.SourceUrl ?? root.Path ?? string.Empty)
                : root.IsCloudTrackedLocal && !string.IsNullOrWhiteSpace(root.SourceUrl)
                    ? $"{root.Path ?? string.Empty} · {root.SourceUrl}"
                    : (root.Path ?? string.Empty);
            var sync = root.IsCloud ? lib.GetCloudSyncProgress(root.Id) : null;
            string? syncSuffix = sync is { IsCompleted: false }
                ? L.Format("Folders_CloudSyncCompact", sync.FoldersDone, sync.FoldersTotal, L.SongsCount(sync.TracksDiscovered))
                : null;
            var detailTb = new TextBlock
            {
                Text = available
                    ? (syncSuffix is not null
                        ? $"{L.SongsCount(count)} · {syncSuffix} · {locationDisplay}"
                        : $"{L.SongsCount(count)} · {locationDisplay}")
                    : root.Kind == RootKind.CloudExtension
                        ? L.Get("Folders_ExtensionUnavailable")
                        : L.Get("Folders_Unavailable"),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 3, 0, 0)
            };
            detailTb.SetResourceReference(TextBlock.ForegroundProperty, available ? "TextSecondaryBrush" : "WarningTextBrush");

            info.Children.Add(titleRow);
            info.Children.Add(detailTb);
            Grid.SetColumn(info, 1);

            var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            if (!available && root.Kind == RootKind.Picked)
            {
                var locateBtn = new Button
                {
                    Content = L.Get("Folders_LocateAgain"),
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
            else if (!available && root.Kind == RootKind.CloudExtension)
            {
                var recoveryMatches = lib.GetCompatibleRecoveryProviders(root);
                if (recoveryMatches.Count > 0)
                {
                    var bestMatchId = recoveryMatches[0].ProviderId;
                    var switchBtn = new Button
                    {
                        Content = L.Get("Folders_SwitchSource"),
                        Style = (Style)FindResource("Win10PrimaryButtonStyle"),
                        Margin = new Thickness(0, 0, 8, 0)
                    };
                    switchBtn.Click += (_, e) =>
                    {
                        e.Handled = true;
                        PromptEditCloudSource(root, string.Empty, bestMatchId);
                    };
                    actions.Children.Add(switchBtn);
                }

                var extBtn = new Button
                {
                    Content = L.Get("Folders_ManageExtensions"),
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    Margin = new Thickness(0, 0, 8, 0)
                };
                extBtn.Click += (_, e) =>
                {
                    e.Handled = true;
                    _ctx.OpenExtensions?.Invoke();
                };
                actions.Children.Add(extBtn);
            }

            if (root.IsCloud)
            {
                var saveBtn = new Button
                {
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    ToolTip = L.Get("Folders_SaveCloudTooltip"),
                    Margin = new Thickness(0, 0, 8, 0),
                    IsEnabled = count > 0 && available
                };
                var saveContent = new StackPanel { Orientation = Orientation.Horizontal };
                saveContent.Children.Add(new TextBlock
                {
                    Text = "\uE896",
                    FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                    FontSize = 12,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 6, 0)
                });
                saveContent.Children.Add(new TextBlock
                {
                    Text = L.Get("Folders_SaveToLocal"),
                    VerticalAlignment = VerticalAlignment.Center
                });
                saveBtn.Content = saveContent;
                saveBtn.Click += (_, e) =>
                {
                    e.Handled = true;
                    var tracksToSave = lib.GetTracksUnder(rootId, string.Empty);
                    _ctx.OpenSaveCloudPlaylist?.Invoke(root, tracksToSave);
                };
                actions.Children.Add(saveBtn);
            }

            if (root.IsCloudTrackedLocal && available)
            {
                var updateBtn = new Button
                {
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    ToolTip = L.Get("Folders_UpdateFromCloud"),
                    Margin = new Thickness(0, 0, 8, 0)
                };
                var updateContent = new StackPanel { Orientation = Orientation.Horizontal };
                updateContent.Children.Add(new TextBlock
                {
                    Text = "\uE896",
                    FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                    FontSize = 12,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 6, 0)
                });
                updateContent.Children.Add(new TextBlock
                {
                    Text = L.Get("Folders_UpdateFromCloud"),
                    VerticalAlignment = VerticalAlignment.Center
                });
                updateBtn.Content = updateContent;
                updateBtn.Click += (_, e) =>
                {
                    e.Handled = true;
                    _ctx.OpenUpdateTrackedFolder?.Invoke(root);
                };
                actions.Children.Add(updateBtn);
            }

            var removeBtn = new Button
            {
                Style = (Style)FindResource("Win10ButtonStyle"),
                ToolTip = L.Get("Folders_RemoveFromLibraryTooltip")
            };
            var removeContent = new StackPanel { Orientation = Orientation.Horizontal };
            removeContent.Children.Add(new TextBlock
            {
                Text = "\uE74D",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 12,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 6, 0)
            });
            removeContent.Children.Add(new TextBlock
            {
                Text = L.Get("Folders_RemoveFolder"),
                VerticalAlignment = VerticalAlignment.Center
            });
            removeBtn.Content = removeContent;
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
                Win10Animations.AnimateSlideX(ContentStack, 24);
            };

            var rootTracks = lib.GetTracksUnder(rootId, string.Empty, _ctx.Services.History);
            var rootTrackIds = rootTracks.Select(t => t.Id).ToList();
            card.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                rootTrackIds,
                null,
                _ctx.OpenTextPrompt,
                menu =>
                {
                    if (rootTrackIds.Count > 0)
                    {
                        menu.Items.Insert(0, TrackMenuHelper.CreateItem(
                            L.Get("Common_Play"),
                            "\uE768",
                            () => _ctx.Services.Player.PlayInNamedQueue(L.DisplayRootName(root), rootTrackIds)));
                        menu.Items.Insert(1, new Separator());
                    }
                    menu.Items.Add(new Separator());
                    if (root.IsCloud)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_SaveToLocal"),
                            "\uE896",
                            () => _ctx.OpenSaveCloudPlaylist?.Invoke(root, rootTracks),
                            rootTracks.Count > 0 && available));
                        if (!available && root.Kind == RootKind.CloudExtension)
                        {
                            var recoveryMatches = lib.GetCompatibleRecoveryProviders(root);
                            if (recoveryMatches.Count > 0)
                            {
                                string bestMatchId = recoveryMatches[0].ProviderId;
                                menu.Items.Add(TrackMenuHelper.CreateItem(
                                    L.Get("Folders_SwitchSource"),
                                    "\uE8AB",
                                    () => PromptEditCloudSource(root, string.Empty, bestMatchId)));
                            }
                        }
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_EditCloudSource"),
                            "\uE70F",
                            () => PromptEditCloudSource(root, string.Empty)));
                    }
                    if (root.IsCloudTrackedLocal)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_UpdateFromCloud"),
                            "\uE896",
                            () => OpenTrackedUpdate(root, string.Empty),
                            available));
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_EditCloudSource"),
                            "\uE70F",
                            () => PromptEditCloudSource(root, string.Empty)));
                    }
                    if (root.Kind == RootKind.Picked)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Folders_LocateAgain"), "\uE8DA", async () => await PickAndRelocateAsync(rootId)));
                    }
                    int excludedCount = root.ExcludedPaths?.Count ?? 0;
                    if (excludedCount > 0)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Format("Folders_RestoreRemovedSubfolders", excludedCount),
                            "\uE7A7",
                            async () => await lib.RestoreExcludedFoldersAsync(rootId)));
                    }
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Folders_RemoveFolder"), "\uE74D", () => ConfirmRemoveRoot(root)));
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
            sp.Children.Add(new TextBlock { Text = L.Get("Folders_RestoreMusicFolder"), VerticalAlignment = VerticalAlignment.Center });
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

        bool isSyncing = root.IsCloud && lib.GetCloudSyncProgress(rootId) is { IsCompleted: false };
        if (isSyncing)
        {
            lib.PrioritizeCloudFolder(rootId, _currentPath);
        }

        var currentTracking = root.IsLocal ? lib.GetCloudTracking(rootId, _currentPath) : null;
        string displayFolderPath = string.IsNullOrEmpty(_currentPath)
            ? (root.Path ?? string.Empty)
            : FolderScanner.CombinePath(root.Path ?? string.Empty, _currentPath);

        string leafName = string.IsNullOrEmpty(_currentPath)
            ? L.DisplayRootName(root)
            : _currentPath.Split('/').Last();
        TitleText.Text = leafName;
        SubtitleText.Text = root.IsCloud
            ? (root.SourceUrl ?? root.Path ?? string.Empty)
            : currentTracking is not null && !string.IsNullOrWhiteSpace(currentTracking.SourceUrl)
                ? $"{displayFolderPath} · {currentTracking.SourceUrl}"
                : displayFolderPath;
        DrillBar.Visibility = Visibility.Visible;
        NewFolderButton.Visibility = root.IsLocal && lib.IsAvailable(root) ? Visibility.Visible : Visibility.Collapsed;
        SaveCloudFolderButton.Visibility = root.IsCloud ? Visibility.Visible : Visibility.Collapsed;
        UpdateTrackedFolderButton.Visibility = root.IsLocal && currentTracking is not null && lib.IsAvailable(root)
            ? Visibility.Visible
            : Visibility.Collapsed;
        EditCloudSourceButton.Visibility = (root.IsCloud && string.IsNullOrEmpty(_currentPath))
            || (root.IsLocal && currentTracking is not null)
            ? Visibility.Visible
            : Visibility.Collapsed;
        RemoveRootButton.Visibility = Visibility.Visible;

        BuildBreadcrumbs(root);

        var history = _ctx.Services.History;
        TrackSortMode sortMode = lib.GetFolderSortMode(rootId, _currentPath);
        int modeIndex = 0;
        for (int i = 0; i < TrackSorter.AllModes.Count; i++)
        {
            if (TrackSorter.AllModes[i] == sortMode)
            {
                modeIndex = i;
                break;
            }
        }
        _suppressSortSelection = true;
        SortCombo.SelectedIndex = modeIndex;
        _suppressSortSelection = false;

        var (subfolders, directTracks) = lib.GetFolderContents(rootId, _currentPath, history);
        var allTracksUnder = lib.GetTracksUnder(rootId, _currentPath, history);

        SortCombo.IsEnabled = directTracks.Count > 1;
        PlayFolderButton.IsEnabled = allTracksUnder.Count > 0;
        ShuffleFolderButton.IsEnabled = allTracksUnder.Count > 0;
        SaveCloudFolderButton.IsEnabled = root.IsCloud && lib.IsAvailable(root) && allTracksUnder.Count > 0;

        ContentStack.Children.Clear();

        if (root.Kind == RootKind.CloudExtension && !lib.IsAvailable(root))
        {
            ContentStack.Children.Add(BuildExtensionUnavailableBanner(root));
        }

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
                Text = isSyncing ? L.Get("Folders_CloudSyncLoadingTitle") : L.Get("Folders_EmptyTitle"),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                HorizontalAlignment = HorizontalAlignment.Center
            };
            t1.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            var t2 = new TextBlock
            {
                Text = isSyncing ? L.Get("Folders_CloudSyncLoadingDesc") : L.Get("Folders_EmptyDesc"),
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
            string capturedFolderName = folderName;
            var folderTracks = lib.GetTracksUnder(rootId, childPath, history);
            var subTracking = root.IsLocal ? lib.GetCloudTracking(rootId, childPath) : null;
            bool isTrackedSubfolder = subTracking is not null && subTracking.CloudKind.IsCloud();

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

            UIElement titleElement;
            if (isTrackedSubfolder)
            {
                var subTitlePanel = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    VerticalAlignment = VerticalAlignment.Center
                };
                subTitlePanel.Children.Add(nameTb);
                subTitlePanel.Children.Add(CreateCloudBadge(subTracking!.ResolvedProviderId, subTracking.CloudKind, isLocal: true));
                titleElement = subTitlePanel;
            }
            else
            {
                titleElement = nameTb;
            }
            Grid.SetColumn(titleElement, 1);

            var countTb = new TextBlock
            {
                Text = L.SongsCount(folderTracks.Count),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                VerticalAlignment = VerticalAlignment.Center
            };
            countTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            Grid.SetColumn(countTb, 2);

            var removeSubBtn = CreateCompactRowButton(
                "\uE74D",
                L.Get("Folders_RemoveFolder"),
                L.Get("Folders_RemoveFromLibraryTooltip"),
                new Thickness(isTrackedSubfolder ? 0 : 12, 0, 0, 0),
                (_, e) =>
                {
                    e.Handled = true;
                    ConfirmRemoveSubfolder(root, childPath, navigateUpOnConfirm: false);
                });

            UIElement actionElement;
            if (isTrackedSubfolder)
            {
                var actionsPanel = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(12, 0, 0, 0)
                };
                var updateSubBtn = CreateCompactRowButton(
                    "\uE896",
                    L.Get("Folders_UpdateFromCloud"),
                    L.Get("Folders_UpdateFromCloud"),
                    new Thickness(0, 0, 8, 0),
                    (_, e) =>
                    {
                        e.Handled = true;
                        OpenTrackedUpdate(root, childPath);
                    },
                    isEnabled: lib.IsAvailable(root));
                actionsPanel.Children.Add(updateSubBtn);
                actionsPanel.Children.Add(removeSubBtn);
                actionElement = actionsPanel;
            }
            else
            {
                actionElement = removeSubBtn;
            }
            Grid.SetColumn(actionElement, 3);

            rowGrid.Children.Add(icon);
            rowGrid.Children.Add(titleElement);
            rowGrid.Children.Add(countTb);
            rowGrid.Children.Add(actionElement);
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
                Win10Animations.AnimateSlideX(ContentStack, 24);
            };

            var folderTrackIds = folderTracks.Select(t => t.Id).ToList();
            row.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                folderTrackIds,
                null,
                _ctx.OpenTextPrompt,
                menu =>
                {
                    if (folderTrackIds.Count > 0)
                    {
                        menu.Items.Insert(0, TrackMenuHelper.CreateItem(
                            L.Get("Common_Play"),
                            "\uE768",
                            () => _ctx.Services.Player.PlayInNamedQueue(capturedFolderName, folderTrackIds)));
                        menu.Items.Insert(1, new Separator());
                    }
                    menu.Items.Add(new Separator());
                    if (isTrackedSubfolder)
                    {
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_UpdateFromCloud"),
                            "\uE896",
                            () => OpenTrackedUpdate(root, childPath),
                            lib.IsAvailable(root)));
                        menu.Items.Add(TrackMenuHelper.CreateItem(
                            L.Get("Folders_EditCloudSource"),
                            "\uE70F",
                            () => PromptEditCloudSource(root, childPath)));
                    }
                    menu.Items.Add(TrackMenuHelper.CreateItem(
                        L.Get("Folders_RemoveFolder"),
                        "\uE74D",
                        () => ConfirmRemoveSubfolder(root, childPath, navigateUpOnConfirm: false)));
                });

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
                track.IsCloud ? L.DisplayArtist(track.Artist) : track.FileName,
                isCurrent,
                isPlaying,
                isAvail,
                () => _ctx.Services.Player.PlayInNamedQueue(leafName, directIds, idx),
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo,
                openDeleteChoice: _ctx.OpenDeleteChoice,
                showToast: _ctx.ShowToast);

            row.EnableDragReorder($"folder:{rootId:D}:{_currentPath}", idx, directTracks.Count,
                (from, to) => lib.MoveFolderTrack(rootId, _currentPath, from, to, history));
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
        AddCrumb(L.Get("Folders_Title"), ResetToRoots, false);
        AddCrumb(L.DisplayRootName(root), () => { _currentPath = string.Empty; Refresh(); Win10Animations.AnimateSlideX(ContentStack, -24); }, segments.Length == 0);

        string accum = string.Empty;
        for (int i = 0; i < segments.Length; i++)
        {
            accum = i == 0 ? segments[i] : $"{accum}/{segments[i]}";
            string target = accum;
            AddCrumb(segments[i], () => { _currentPath = target; Refresh(); Win10Animations.AnimateSlideX(ContentStack, -24); }, i == segments.Length - 1);
        }
    }

    private async void AddFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var dlg = new OpenFolderDialog
        {
            Title = L.Get("Folders_SelectMusicFolderTitle")
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            await _ctx.Services.Library.AddRootAsync(dlg.FolderName);
        }
        catch (Exception ex)
        {
            _ctx.OpenConfirm(L.Get("Folders_CouldNotAddTitle"), ex.Message, L.Get("Common_OK"), null, false);
        }
    }

    private async System.Threading.Tasks.Task PickAndRelocateAsync(Guid rootId)
    {
        if (_ctx is null) return;
        var dlg = new OpenFolderDialog
        {
            Title = L.Get("Folders_LocateFolderTitle")
        };
        if (dlg.ShowDialog() != true) return;

        try
        {
            await _ctx.Services.Library.RelocateRootAsync(rootId, dlg.FolderName);
        }
        catch (Exception ex)
        {
            _ctx.OpenConfirm(L.Get("Folders_CouldNotLocateTitle"), ex.Message, L.Get("Common_OK"), null, false);
        }
    }

    private void ConfirmRemoveRoot(LibraryRoot root)
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        string displayName = L.DisplayRootName(root);
        var affectedTracks = lib.GetTracksUnder(root.Id, string.Empty);
        string? exactPath = root.IsCloud
            ? (root.SourceUrl ?? root.Path)
            : (lib.GetRootPath(root.Id) ?? root.Path);
        bool canRecycle = root.Kind == RootKind.Picked && lib.IsAvailable(root) && !string.IsNullOrWhiteSpace(exactPath);

        void OnRemoveFromLibrary()
        {
            lib.RemoveRoot(root.Id);
            _ctx.ShowToast?.Invoke(L.Format("Toast_RemovedFromLibrary", displayName), null, null);
        }

        void OnMoveToRecycleBin()
        {
            var ids = affectedTracks.Select(t => t.Id).ToList();
            try
            {
                lib.DeleteFolderFiles(root.Id, string.Empty);
                foreach (string id in ids)
                {
                    _ctx.Services.Lyrics.RemoveImported(id);
                }
                _ctx.ShowToast?.Invoke(L.Format("Toast_MovedToRecycleBin", displayName), null, null);
            }
            catch (Exception ex)
            {
                _ctx.OpenConfirm(L.Get("Delete_FailedTitle"), ex.Message, L.Get("Common_OK"), null, false);
            }
        }

        if (_ctx.OpenDeleteChoice is not null)
        {
            _ctx.OpenDeleteChoice(new DeleteChoiceRequest(
                Title: L.Format("Folders_RemoveConfirmTitle", displayName),
                Message: root.IsCloud
                    ? L.Get("Folders_RemoveConfirmCloudMessage")
                    : L.Get("Folders_RemoveConfirmLocalMessage"),
                ItemName: displayName,
                ExactPath: exactPath,
                SongCountText: L.SongsCount(affectedTracks.Count),
                RemoveFromLibraryLabel: L.Get("Delete_RemoveFromLibrary"),
                OnRemoveFromLibrary: OnRemoveFromLibrary,
                MoveToRecycleBinLabel: canRecycle ? L.Get("Delete_MoveToRecycleBin") : null,
                OnMoveToRecycleBin: canRecycle ? OnMoveToRecycleBin : null));
        }
        else
        {
            _ctx.OpenConfirm(
                L.Format("Folders_RemoveConfirmTitle", displayName),
                root.IsCloud
                    ? L.Get("Folders_RemoveConfirmCloudMessage")
                    : L.Get("Folders_RemoveConfirmLocalMessage"),
                L.Get("Folders_RemoveConfirmAction"),
                OnRemoveFromLibrary,
                true);
        }
    }

    private void ConfirmRemoveSubfolder(LibraryRoot root, string path, bool navigateUpOnConfirm)
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        string leaf = path.Split('/').LastOrDefault() ?? path;
        var affectedTracks = lib.GetTracksUnder(root.Id, path);
        string? rootDiskPath = lib.GetRootPath(root.Id) ?? root.Path;
        string? exactPath = root.IsCloud
            ? (root.SourceUrl ?? root.Path)
            : (!string.IsNullOrWhiteSpace(rootDiskPath) ? FolderScanner.CombinePath(rootDiskPath, path) : path);
        bool canRecycle = root.IsLocal && lib.IsAvailable(root) && !string.IsNullOrWhiteSpace(exactPath);

        void NavigateUpIfNeeded()
        {
            if (navigateUpOnConfirm)
            {
                int slash = _currentPath.LastIndexOf('/');
                _currentPath = slash >= 0 ? _currentPath[..slash] : string.Empty;
                Refresh();
                Win10Animations.AnimateSlideX(ContentStack, -24);
            }
        }

        void OnRemoveFromLibrary()
        {
            lib.RemoveSubfolder(root.Id, path);
            NavigateUpIfNeeded();
            _ctx.ShowToast?.Invoke(L.Format("Toast_RemovedFromLibrary", leaf), null, null);
        }

        void OnMoveToRecycleBin()
        {
            var ids = affectedTracks.Select(t => t.Id).ToList();
            try
            {
                lib.DeleteFolderFiles(root.Id, path);
                foreach (string id in ids)
                {
                    _ctx.Services.Lyrics.RemoveImported(id);
                }
                NavigateUpIfNeeded();
                _ctx.ShowToast?.Invoke(L.Format("Toast_MovedToRecycleBin", leaf), null, null);
            }
            catch (Exception ex)
            {
                _ctx.OpenConfirm(L.Get("Delete_FailedTitle"), ex.Message, L.Get("Common_OK"), null, false);
            }
        }

        if (_ctx.OpenDeleteChoice is not null)
        {
            _ctx.OpenDeleteChoice(new DeleteChoiceRequest(
                Title: L.Format("Folders_RemoveConfirmTitle", leaf),
                Message: root.IsCloud
                    ? L.Get("Folders_RemoveConfirmCloudMessage")
                    : L.Get("Folders_RemoveConfirmLocalMessage"),
                ItemName: leaf,
                ExactPath: exactPath,
                SongCountText: L.SongsCount(affectedTracks.Count),
                RemoveFromLibraryLabel: L.Get("Delete_RemoveFromLibrary"),
                OnRemoveFromLibrary: OnRemoveFromLibrary,
                MoveToRecycleBinLabel: canRecycle ? L.Get("Delete_MoveToRecycleBin") : null,
                OnMoveToRecycleBin: canRecycle ? OnMoveToRecycleBin : null));
        }
        else
        {
            _ctx.OpenConfirm(
                L.Format("Folders_RemoveConfirmTitle", leaf),
                root.IsCloud
                    ? L.Get("Folders_RemoveConfirmCloudMessage")
                    : L.Get("Folders_RemoveConfirmLocalMessage"),
                L.Get("Folders_RemoveConfirmAction"),
                OnRemoveFromLibrary,
                true);
        }
    }

    private void AddCloudFolder_Click(object sender, RoutedEventArgs e)
    {
        _ctx?.OpenAddCloudFolder?.Invoke();
    }

    private void SaveCloudFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null || !root.IsCloud) return;
        var tracks = _ctx.Services.Library.GetTracksUnder(rid, _currentPath);
        if (tracks.Count == 0) return;
        _ctx.OpenSaveCloudPlaylist?.Invoke(root, tracks);
    }

    private void UpdateTrackedFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null || !root.IsLocal) return;
        if (_ctx.Services.Library.GetCloudTracking(rid, _currentPath) is null) return;
        OpenTrackedUpdate(root, _currentPath);
    }

    private void EditCloudSource_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null) return;
        if (root.IsCloud && string.IsNullOrEmpty(_currentPath))
        {
            PromptEditCloudSource(root, string.Empty);
            return;
        }
        if (!root.IsLocal || _ctx.Services.Library.GetCloudTracking(rid, _currentPath) is null) return;
        PromptEditCloudSource(root, _currentPath);
    }

    private void OpenTrackedUpdate(LibraryRoot root, string relativePath)
    {
        if (_ctx is null) return;
        string norm = LibraryRoot.NormalizeFolderPath(relativePath);
        if (norm.Length == 0)
        {
            _ctx.OpenUpdateTrackedFolder?.Invoke(root);
        }
        else
        {
            _ctx.OpenUpdateTrackedSubfolder?.Invoke(root, norm);
        }
    }

    private void PromptEditCloudSource(LibraryRoot root, string relativePath, string? preselectedProviderId = null)
    {
        if (_ctx is null) return;
        _ctx.OpenEditCloudSource?.Invoke(root, relativePath, preselectedProviderId);
    }

    private Border BuildExtensionUnavailableBanner(LibraryRoot root)
    {
        string providerLabel = CloudProviderRegistry.ResolveDisplayLabel(
            _ctx?.Services.CloudProviders,
            root.EffectiveProviderId,
            root.Kind);
        var recoveryMatches = _ctx?.Services.Library.GetCompatibleRecoveryProviders(root)
            ?? Array.Empty<ICloudSourceProvider>();

        var banner = new Border
        {
            Margin = new Thickness(0, 0, 0, 14),
            Padding = new Thickness(14, 12, 14, 12),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0)
        };
        banner.SetResourceReference(Border.BackgroundProperty, "WarningBannerBrush");
        banner.SetResourceReference(Border.BorderBrushProperty, "WarningTextBrush");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var warnGlyph = new TextBlock
        {
            Text = "\uE7BA",
            FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
            FontSize = 17,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 2, 0, 0)
        };
        warnGlyph.SetResourceReference(TextBlock.ForegroundProperty, "WarningTextBrush");
        Grid.SetColumn(warnGlyph, 0);

        var textPanel = new StackPanel { Margin = new Thickness(6, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
        var titleTb = new TextBlock
        {
            Text = L.Format("Folders_ExtensionBannerTitle", providerLabel),
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 13,
            FontWeight = FontWeights.SemiBold
        };
        titleTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
        var descTb = new TextBlock
        {
            Text = recoveryMatches.Count > 0
                ? L.Format("Folders_ExtensionRecoveryBannerDesc", recoveryMatches[0].DisplayName)
                : L.Get("Folders_ExtensionBannerDesc"),
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 2, 0, 0)
        };
        descTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        textPanel.Children.Add(titleTb);
        textPanel.Children.Add(descTb);
        Grid.SetColumn(textPanel, 1);

        var actionPanel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center
        };
        if (recoveryMatches.Count > 0)
        {
            string bestMatchId = recoveryMatches[0].ProviderId;
            var switchBtn = new Button
            {
                Style = (Style)FindResource("Win10PrimaryButtonStyle"),
                Content = L.Get("Folders_SwitchSource"),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            switchBtn.Click += (_, _) => PromptEditCloudSource(root, string.Empty, bestMatchId);
            actionPanel.Children.Add(switchBtn);
        }

        var manageBtn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            Content = L.Get("Folders_ManageExtensions"),
            VerticalAlignment = VerticalAlignment.Center
        };
        manageBtn.Click += (_, _) => _ctx?.OpenExtensions?.Invoke();
        actionPanel.Children.Add(manageBtn);
        Grid.SetColumn(actionPanel, 2);

        grid.Children.Add(warnGlyph);
        grid.Children.Add(textPanel);
        grid.Children.Add(actionPanel);
        banner.Child = grid;
        return banner;
    }

    private Border CreateCloudBadge(string? providerId, RootKind cloudKind, bool isLocal)
    {
        var badge = new Border
        {
            Margin = new Thickness(8, 0, 0, 0),
            Padding = new Thickness(6, 2, 6, 2),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0),
            VerticalAlignment = VerticalAlignment.Center
        };
        badge.SetResourceReference(Border.BackgroundProperty, "AccentSubtleBrush");
        badge.SetResourceReference(Border.BorderBrushProperty, "AccentBrush");

        string cloudName = CloudProviderRegistry.ResolveDisplayLabel(
            _ctx?.Services.CloudProviders,
            providerId,
            cloudKind);
        var badgeText = new TextBlock
        {
            Text = isLocal ? L.Format("Folders_CloudLocalBadge", cloudName) : cloudName,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        badgeText.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
        badge.Child = badgeText;
        return badge;
    }

    private Button CreateCompactRowButton(
        string glyph,
        string label,
        string tooltip,
        Thickness margin,
        RoutedEventHandler onClick,
        bool isEnabled = true)
    {
        var btn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            ToolTip = tooltip,
            Margin = margin,
            Padding = new Thickness(8, 4, 8, 4),
            IsEnabled = isEnabled
        };
        var content = new StackPanel { Orientation = Orientation.Horizontal };
        content.Children.Add(new TextBlock
        {
            Text = glyph,
            FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
            FontSize = 11,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 5, 0)
        });
        content.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center
        });
        btn.Content = content;
        btn.Click += onClick;
        return btn;
    }

    private void RemoveRoot_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null) return;
        if (string.IsNullOrEmpty(_currentPath))
        {
            ConfirmRemoveRoot(root);
        }
        else
        {
            ConfirmRemoveSubfolder(root, _currentPath, navigateUpOnConfirm: true);
        }
    }

    private async void Rescan_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        await _ctx.Services.Library.RescanAsync();
    }

    private void SortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSortSelection || _ctx is null || _currentRootId is not Guid rid) return;
        int idx = SortCombo.SelectedIndex;
        if (idx < 0 || idx >= TrackSorter.AllModes.Count) return;
        _ctx.Services.Library.SetFolderSortMode(rid, _currentPath, TrackSorter.AllModes[idx]);
    }

    private void NewFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        _ctx.OpenTextPrompt(
            L.Get("Folders_NewFolder"),
            L.Get("Folders_NewFolderPrompt"),
            string.Empty,
            L.Get("Common_Create"),
            async name =>
            {
                try
                {
                    await _ctx.Services.Library.CreateSubfolderAsync(rid, _currentPath, name);
                }
                catch (Exception ex)
                {
                    _ctx.OpenConfirm(L.Get("Folders_CouldNotCreateTitle"), ex.Message, L.Get("Common_OK"), null, false);
                }
            });
    }

    private void PlayFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null) return;
        string leafName = string.IsNullOrEmpty(_currentPath) ? root.Name : _currentPath.Split('/').Last();
        var ids = _ctx.Services.Library.GetTracksUnder(rid, _currentPath, _ctx.Services.History).Select(t => t.Id).ToList();
        if (ids.Count == 0) return;
        _ctx.Services.Player.PlayInNamedQueue(leafName, ids, startAt: 0, shuffle: false);
    }

    private void ShuffleFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _currentRootId is not Guid rid) return;
        var root = _ctx.Services.Library.GetRoot(rid);
        if (root is null) return;
        string leafName = string.IsNullOrEmpty(_currentPath) ? root.Name : _currentPath.Split('/').Last();
        var ids = _ctx.Services.Library.GetTracksUnder(rid, _currentPath, _ctx.Services.History).Select(t => t.Id).ToList();
        if (ids.Count == 0) return;
        _ctx.Services.Player.PlayInNamedQueue(leafName, ids, startAt: Random.Shared.Next(ids.Count), shuffle: true);
    }
}
