using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using MusicoWindows.Converters;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class SleepTimerDialog : UserControl
{
    private static readonly int[] Presets = [5, 10, 15, 30, 45, 60, 90];

    private readonly DispatcherTimer _countdownTimer;
    private AppServices? _services;
    private int _customMinutes = 20;

    public SleepTimerDialog()
    {
        InitializeComponent();
        BuildPresets();

        _countdownTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _countdownTimer.Tick += (_, _) => RefreshStatus();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    private void BuildPresets()
    {
        PresetsGrid.Children.Clear();
        foreach (int mins in Presets)
        {
            int captured = mins;
            var btn = new Button
            {
                Content = $"{mins} min",
                Margin = new Thickness(0, 0, 6, 6),
                Style = (Style)FindResource("Win10ButtonStyle")
            };
            btn.Click += (_, _) => StartMinutes(captured);
            PresetsGrid.Children.Add(btn);
        }
    }

    public void Show(AppServices services)
    {
        _services = services;
        RefreshStatus();
        _countdownTimer.Start();
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void Close()
    {
        _countdownTimer.Stop();
        Visibility = Visibility.Collapsed;
    }

    private void RefreshStatus()
    {
        if (_services is null) return;
        var state = _services.Player.SleepState;
        if (state.Kind == SleepModeKind.Off)
        {
            ActiveBanner.Visibility = Visibility.Collapsed;
            return;
        }

        ActiveBanner.Visibility = Visibility.Visible;
        if (state.Kind == SleepModeKind.EndOfTrack)
        {
            ActiveStatusText.Text = "Stops at the end of this song";
        }
        else if (state.UntilUtc is DateTimeOffset until)
        {
            double remaining = Math.Max(0, (until - DateTimeOffset.UtcNow).TotalSeconds);
            ActiveStatusText.Text = $"Stops in {TimeFormatter.Format(remaining)}";
        }
    }

    private void StartMinutes(int minutes)
    {
        _services?.Player.SetSleep(SleepState.Until(DateTimeOffset.UtcNow.AddMinutes(minutes)));
        Close();
    }

    private void DecreaseCustom_Click(object sender, RoutedEventArgs e)
    {
        _customMinutes = Math.Max(1, _customMinutes - 5);
        CustomMinutesText.Text = $"{_customMinutes} minutes";
    }

    private void IncreaseCustom_Click(object sender, RoutedEventArgs e)
    {
        _customMinutes = Math.Min(240, _customMinutes + 5);
        CustomMinutesText.Text = $"{_customMinutes} minutes";
    }

    private void StartCustom_Click(object sender, RoutedEventArgs e) => StartMinutes(_customMinutes);

    private void EndOfTrack_Click(object sender, RoutedEventArgs e)
    {
        _services?.Player.SetSleep(SleepState.EndOfTrack);
        Close();
    }

    private void CancelTimer_Click(object sender, RoutedEventArgs e)
    {
        _services?.Player.SetSleep(SleepState.Off);
        RefreshStatus();
    }

    private void Done_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
