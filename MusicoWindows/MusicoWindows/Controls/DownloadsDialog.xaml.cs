using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class DownloadsDialog : UserControl
{
    private AppServices? _services;
    private Action<Guid>? _openJobDetails;

    public DownloadsDialog()
    {
        InitializeComponent();
        Unloaded += (_, _) => Detach();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services, Action<Guid>? openJobDetails = null)
    {
        Detach();
        _services = services;
        _openJobDetails = openJobDetails;
        _services.Downloads.Changed += OnDownloadsChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;

        Refresh();
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void Close()
    {
        Detach();
        Visibility = Visibility.Collapsed;
    }

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_services is not null)
        {
            _services.Downloads.Changed -= OnDownloadsChanged;
        }
    }

    private void OnLanguageChanged(ResolvedLanguage _) => Refresh();

    private void OnDownloadsChanged()
    {
        Dispatcher.BeginInvoke(Refresh);
    }

    private void Refresh()
    {
        if (_services is null) return;
        var jobs = _services.Downloads.Jobs;

        JobsStack.Children.Clear();
        bool hasFinished = jobs.Any(j => j.IsFinished);
        ClearFinishedButton.IsEnabled = hasFinished;

        if (jobs.Count == 0)
        {
            EmptyStateText.Visibility = Visibility.Visible;
            return;
        }

        EmptyStateText.Visibility = Visibility.Collapsed;
        foreach (var job in jobs)
        {
            JobsStack.Children.Add(BuildJobCard(job));
        }
    }

    private Border BuildJobCard(DownloadJob job)
    {
        var card = new Border
        {
            Padding = new Thickness(12, 10, 12, 10),
            Margin = new Thickness(0, 0, 0, 8),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0)
        };
        card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

        var root = new StackPanel();

        // Top row: title + action buttons
        var header = new Grid();
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var titleStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 0) };
        var titleTb = new TextBlock
        {
            Text = job.Title,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis
        };
        titleTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
        titleStack.Children.Add(titleTb);

        string statusLine = BuildJobStatusLine(job);
        if (!string.IsNullOrWhiteSpace(statusLine))
        {
            var statusTb = new TextBlock
            {
                Text = statusLine,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 2, 0, 0)
            };
            statusTb.SetResourceReference(
                TextBlock.ForegroundProperty,
                job.Status == DownloadJobStatus.Failed ? "DangerBrush" : "TextSecondaryBrush");
            titleStack.Children.Add(statusTb);
        }
        Grid.SetColumn(titleStack, 0);

        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        if (_openJobDetails is not null)
        {
            var detailsBtn = new Button
            {
                Style = (Style)FindResource("Win10IconButtonStyle"),
                Content = "\uE946",
                ToolTip = job.Title,
                Margin = new Thickness(0, 0, 4, 0)
            };
            Guid capturedId = job.Id;
            detailsBtn.Click += (_, e) =>
            {
                e.Handled = true;
                Close();
                _openJobDetails(capturedId);
            };
            actions.Children.Add(detailsBtn);
        }

        if (job.IsActive)
        {
            var cancelBtn = new Button
            {
                Style = (Style)FindResource("Win10ButtonStyle"),
                Content = L.Get("Common_Cancel"),
                Padding = new Thickness(10, 4, 10, 4),
                MinHeight = 26
            };
            Guid capturedId = job.Id;
            cancelBtn.Click += (_, e) =>
            {
                e.Handled = true;
                cancelBtn.IsEnabled = false;
                _services?.Downloads.Cancel(capturedId);
            };
            actions.Children.Add(cancelBtn);
        }
        else
        {
            if (job.Status == DownloadJobStatus.Completed &&
                ((!string.IsNullOrWhiteSpace(job.SavedFilePath) && File.Exists(job.SavedFilePath)) ||
                 (!string.IsNullOrWhiteSpace(job.DestinationPath) && Directory.Exists(job.DestinationPath))))
            {
                var openBtn = new Button
                {
                    Style = (Style)FindResource("Win10IconButtonStyle"),
                    Content = "\uE838",
                    ToolTip = L.Get("SaveCloud_OpenFolder"),
                    Margin = new Thickness(0, 0, 4, 0)
                };
                openBtn.Click += (_, e) =>
                {
                    e.Handled = true;
                    OpenTargetFolder(job);
                };
                actions.Children.Add(openBtn);
            }

            var dismissBtn = new Button
            {
                Style = (Style)FindResource("Win10IconButtonStyle"),
                Content = "\uE711",
                ToolTip = L.Get("Downloads_DismissTooltip")
            };
            Guid capturedId = job.Id;
            dismissBtn.Click += (_, e) =>
            {
                e.Handled = true;
                _services?.Downloads.Dismiss(capturedId);
            };
            actions.Children.Add(dismissBtn);
        }
        Grid.SetColumn(actions, 1);

        header.Children.Add(titleStack);
        header.Children.Add(actions);
        root.Children.Add(header);

        if (job.IsActive)
        {
            var pb = new ProgressBar
            {
                Style = (Style)FindResource("Win10ProgressBarStyle"),
                Minimum = 0,
                Maximum = 1,
                Margin = new Thickness(0, 8, 0, 5)
            };
            if (job.OverallFraction is double frac)
            {
                pb.IsIndeterminate = false;
                pb.Value = frac;
            }
            else
            {
                pb.IsIndeterminate = true;
                pb.Value = 0;
            }
            root.Children.Add(pb);

            var metricsGrid = new Grid();
            metricsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            metricsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            string bytesText = BuildBytesText(job);
            string pctText = DownloadManager.FormatPercentage(job.OverallFraction);
            string leftMetrics = string.IsNullOrEmpty(bytesText)
                ? pctText
                : string.IsNullOrEmpty(pctText) ? bytesText : $"{bytesText} · {pctText}";

            var leftTb = new TextBlock
            {
                Text = leftMetrics,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11
            };
            leftTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            Grid.SetColumn(leftTb, 0);

            var speedTb = new TextBlock
            {
                Text = job.BytesPerSecond > 0 ? DownloadManager.FormatSpeed(job.BytesPerSecond) : string.Empty,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11
            };
            speedTb.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            Grid.SetColumn(speedTb, 1);

            metricsGrid.Children.Add(leftTb);
            metricsGrid.Children.Add(speedTb);
            root.Children.Add(metricsGrid);
        }

        card.Child = root;
        return card;
    }

    private static string BuildJobStatusLine(DownloadJob job)
    {
        if (job.Status == DownloadJobStatus.Running)
        {
            if (job.TotalItems > 1)
            {
                return string.IsNullOrWhiteSpace(job.CurrentItemTitle)
                    ? $"{job.CompletedItems} / {job.TotalItems}"
                    : $"{job.CompletedItems} / {job.TotalItems} · {job.CurrentItemTitle}";
            }
            if (!string.IsNullOrWhiteSpace(job.CurrentItemTitle))
            {
                return job.Kind == DownloadJobKind.FolderUpdate
                    ? L.Format("SaveCloud_SyncingItem", job.CurrentItemTitle)
                    : L.Format("SaveCloud_SavingItem", job.CurrentItemTitle);
            }
            return job.Kind == DownloadJobKind.FolderUpdate
                ? L.Get("SaveCloud_CheckingPlaylist")
                : L.Get("SaveCloud_Preparing");
        }

        if (job.Status == DownloadJobStatus.Completed)
        {
            return job.Kind switch
            {
                DownloadJobKind.SingleTrackExport => L.Get("SaveCloud_SavedSingleTrack"),
                DownloadJobKind.FolderUpdate => job.AddedItems == 0 && job.FailedItems == 0
                    ? L.Format("SaveCloud_AlreadyUpToDate", L.SongsCount(job.SkippedItems))
                    : job.FailedItems == 0
                        ? L.Format("SaveCloud_UpdateDownloaded", L.SongsCount(job.AddedItems), job.SkippedItems)
                        : L.Format("SaveCloud_UpdateWithFailures", job.AddedItems, job.SkippedItems, job.FailedItems),
                _ => job.FailedItems == 0
                    ? L.Format("SaveCloud_ExportSuccess", L.SongsCount(job.CompletedItems))
                    : L.Format("SaveCloud_ExportWithFailures", L.SongsCount(job.CompletedItems), job.FailedItems)
            };
        }

        if (job.Status == DownloadJobStatus.Cancelled)
        {
            return job.ErrorMessage ?? L.Get("Downloads_StatusCancelled");
        }

        return job.ErrorMessage ?? L.Get("Downloads_StatusFailed");
    }

    private static string BuildBytesText(DownloadJob job)
    {
        if (job.TotalBytes is long total && total > 0)
        {
            return L.Format("SaveCloud_BytesOfTotal",
                DownloadManager.FormatBytes(job.BytesDownloaded),
                DownloadManager.FormatBytes(total));
        }
        if (job.BytesDownloaded > 0)
        {
            return DownloadManager.FormatBytes(job.BytesDownloaded);
        }
        return string.Empty;
    }

    private static void OpenTargetFolder(DownloadJob job)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(job.SavedFilePath) && File.Exists(job.SavedFilePath))
            {
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{job.SavedFilePath}\"")
                {
                    UseShellExecute = true
                });
            }
            else if (!string.IsNullOrWhiteSpace(job.DestinationPath) && Directory.Exists(job.DestinationPath))
            {
                Process.Start(new ProcessStartInfo(job.DestinationPath)
                {
                    UseShellExecute = true
                });
            }
        }
        catch
        {
            // Best-effort folder open.
        }
    }

    private void ClearFinished_Click(object sender, RoutedEventArgs e) => _services?.Downloads.ClearFinished();

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
