using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Animation;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

/// <summary>
/// Builds SystemParameters.ClientAreaAnimation-aware pulsing skeleton placeholder rows.
/// </summary>
public static class SkeletonHelper
{
    public static void PopulateSongSkeletonRows(Panel host, int count = 6)
    {
        host.Children.Clear();
        bool animate = Win10Animations.IsMotionEnabled;

        for (int i = 0; i < count; i++)
        {
            var row = new Border
            {
                BorderThickness = new Thickness(1),
                BorderBrush = System.Windows.Media.Brushes.Transparent,
                CornerRadius = new CornerRadius(4),
                Padding = new Thickness(10, 7, 10, 7),
                Margin = new Thickness(0, 0, 0, 4),
                Opacity = animate ? 0.35 : 0.55
            };
            row.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");

            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(42) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(56) });

            var thumb = new Border
            {
                Width = 40,
                Height = 40,
                CornerRadius = new CornerRadius(4),
                HorizontalAlignment = HorizontalAlignment.Left
            };
            thumb.SetResourceReference(Border.BackgroundProperty, "ControlFillBrush");
            Grid.SetColumn(thumb, 0);

            var textStack = new StackPanel
            {
                Margin = new Thickness(12, 0, 12, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            var titleBar = new Border
            {
                Height = 13,
                Width = i % 2 == 0 ? 210 : 165,
                HorizontalAlignment = HorizontalAlignment.Left,
                CornerRadius = new CornerRadius(4)
            };
            titleBar.SetResourceReference(Border.BackgroundProperty, "ControlFillBrush");

            var subBar = new Border
            {
                Height = 10,
                Width = i % 2 == 0 ? 130 : 150,
                HorizontalAlignment = HorizontalAlignment.Left,
                CornerRadius = new CornerRadius(4),
                Margin = new Thickness(0, 6, 0, 0)
            };
            subBar.SetResourceReference(Border.BackgroundProperty, "ControlFillBrush");

            textStack.Children.Add(titleBar);
            textStack.Children.Add(subBar);
            Grid.SetColumn(textStack, 1);

            var durationBar = new Border
            {
                Height = 10,
                Width = 34,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Center,
                CornerRadius = new CornerRadius(4)
            };
            durationBar.SetResourceReference(Border.BackgroundProperty, "ControlFillBrush");
            Grid.SetColumn(durationBar, 2);

            grid.Children.Add(thumb);
            grid.Children.Add(textStack);
            grid.Children.Add(durationBar);
            row.Child = grid;

            if (animate)
            {
                var pulse = new DoubleAnimation(0.35, 0.82, TimeSpan.FromMilliseconds(850))
                {
                    AutoReverse = true,
                    RepeatBehavior = RepeatBehavior.Forever,
                    BeginTime = TimeSpan.FromMilliseconds(i * 90),
                    EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
                };
                row.BeginAnimation(UIElement.OpacityProperty, pulse);
            }

            host.Children.Add(row);
        }
    }
}
