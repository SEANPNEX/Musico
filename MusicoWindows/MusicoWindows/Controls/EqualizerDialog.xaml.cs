using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class EqualizerDialog : UserControl
{
    private static readonly string[] BandLabels = ["31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"];

    private readonly List<VerticalEqSlider> _sliders = new();
    private readonly List<TextBlock> _gainLabels = new();
    private AppServices? _services;
    private EqSettings _working = new();
    private bool _syncing;

    public EqualizerDialog()
    {
        InitializeComponent();
        BuildBands();
        BuildPresetItems();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    private void BuildBands()
    {
        BandsGrid.ColumnDefinitions.Clear();
        BandsGrid.Children.Clear();
        _sliders.Clear();
        _gainLabels.Clear();

        for (int i = 0; i < BandLabels.Length; i++)
        {
            BandsGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var col = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };

            var gainText = new TextBlock
            {
                Text = "0",
                FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 0, 0, 6)
            };
            gainText.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

            int bandIndex = i;
            var slider = new VerticalEqSlider
            {
                Minimum = EqSettings.MinGainDb,
                Maximum = EqSettings.MaxGainDb,
                Value = 0
            };
            slider.ValueChangedByUser += (_, val) => OnBandChanged(bandIndex, (float)val);

            var freqText = new TextBlock
            {
                Text = BandLabels[i],
                FontFamily = (System.Windows.Media.FontFamily)FindResource("Win10Font"),
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 6, 0, 0)
            };
            freqText.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

            col.Children.Add(gainText);
            col.Children.Add(slider);
            col.Children.Add(freqText);
            Grid.SetColumn(col, i);
            BandsGrid.Children.Add(col);

            _gainLabels.Add(gainText);
            _sliders.Add(slider);
        }
    }

    private void BuildPresetItems()
    {
        PresetCombo.Items.Clear();
        foreach (var preset in EqPreset.All)
        {
            PresetCombo.Items.Add(preset.Name);
        }
        PresetCombo.Items.Add("Custom");
    }

    public void Show(AppServices services)
    {
        _services = services;
        _working = services.Player.Eq.Clone();
        SyncUiFromWorking();
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
    }

    private void SyncUiFromWorking()
    {
        _syncing = true;
        EnabledToggle.IsChecked = _working.Enabled;

        for (int i = 0; i < BandLabels.Length; i++)
        {
            float g = i < _working.Gains.Length ? _working.Gains[i] : 0f;
            _sliders[i].Value = g;
            _gainLabels[i].Text = FormatGain(g);
        }

        PreampSlider.Value = _working.PreampDb;
        PreampLabel.Text = $"Preamp {FormatGain(_working.PreampDb)} dB";

        PresetCombo.SelectedItem = _working.PresetName is { Length: > 0 } name && PresetCombo.Items.Contains(name)
            ? name
            : "Custom";

        BandsContainer.IsEnabled = _working.Enabled;
        BandsContainer.Opacity = _working.Enabled ? 1.0 : 0.42;
        PreampPanel.IsEnabled = _working.Enabled;
        PreampPanel.Opacity = _working.Enabled ? 1.0 : 0.42;
        _syncing = false;
    }

    private void PushEq()
    {
        _services?.Player.ApplyEq(_working);
    }

    private void OnBandChanged(int index, float newGain)
    {
        if (_syncing) return;
        _working.Gains[index] = newGain;
        _working.PresetName = null;
        _gainLabels[index].Text = FormatGain(newGain);
        _syncing = true;
        PresetCombo.SelectedItem = "Custom";
        _syncing = false;
        PushEq();
    }

    private void EnabledToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_syncing) return;
        _working.Enabled = EnabledToggle.IsChecked == true;
        SyncUiFromWorking();
        PushEq();
    }

    private void PresetCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing) return;
        if (PresetCombo.SelectedItem is not string name || name == "Custom") return;

        foreach (var preset in EqPreset.All)
        {
            if (preset.Name == name)
            {
                Array.Copy(preset.Gains, _working.Gains, Math.Min(preset.Gains.Length, _working.Gains.Length));
                _working.PresetName = preset.Name;
                SyncUiFromWorking();
                PushEq();
                break;
            }
        }
    }

    private void PreampSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_syncing) return;
        _working.PreampDb = (float)e.NewValue;
        PreampLabel.Text = $"Preamp {FormatGain(_working.PreampDb)} dB";
        PushEq();
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        var flat = EqPreset.All[0];
        Array.Copy(flat.Gains, _working.Gains, Math.Min(flat.Gains.Length, _working.Gains.Length));
        _working.PreampDb = 0f;
        _working.PresetName = flat.Name;
        SyncUiFromWorking();
        PushEq();
    }

    private void Done_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;

    private static string FormatGain(float gain)
    {
        if (Math.Abs(gain) < 0.05f) return "0";
        return gain > 0 ? $"+{gain:0.#}" : $"{gain:0.#}";
    }
}
