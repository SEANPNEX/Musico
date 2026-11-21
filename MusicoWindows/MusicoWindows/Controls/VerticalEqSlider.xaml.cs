using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace MusicoWindows.Controls;

public partial class VerticalEqSlider : UserControl
{
    public static readonly DependencyProperty ValueProperty =
        DependencyProperty.Register(
            nameof(Value),
            typeof(double),
            typeof(VerticalEqSlider),
            new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault, OnValueChanged));

    public static readonly DependencyProperty MinimumProperty =
        DependencyProperty.Register(nameof(Minimum), typeof(double), typeof(VerticalEqSlider), new PropertyMetadata(-12.0, OnValueChanged));

    public static readonly DependencyProperty MaximumProperty =
        DependencyProperty.Register(nameof(Maximum), typeof(double), typeof(VerticalEqSlider), new PropertyMetadata(12.0, OnValueChanged));

    private bool _dragging;

    public event EventHandler<double>? ValueChangedByUser;

    public VerticalEqSlider()
    {
        InitializeComponent();
    }

    public double Value
    {
        get => (double)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }

    public double Minimum
    {
        get => (double)GetValue(MinimumProperty);
        set => SetValue(MinimumProperty, value);
    }

    public double Maximum
    {
        get => (double)GetValue(MaximumProperty);
        set => SetValue(MaximumProperty, value);
    }

    private static void OnValueChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is VerticalEqSlider slider)
            slider.UpdateThumbPosition();
    }

    private void TrackArea_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateThumbPosition();

    private void UpdateThumbPosition()
    {
        double h = TrackArea.ActualHeight;
        double w = TrackArea.ActualWidth;
        if (h <= 0 || w <= 0) return;

        double range = Maximum - Minimum;
        if (range <= 0) return;

        double fraction = Math.Clamp((Value - Minimum) / range, 0, 1);
        double thumbH = Thumb.Height;
        double usableH = Math.Max(1, h - thumbH);
        double top = usableH * (1.0 - fraction);

        Canvas.SetLeft(Thumb, (w - Thumb.Width) / 2.0);
        Canvas.SetTop(Thumb, top);
    }

    private void SetFromPoint(Point pt)
    {
        double h = TrackArea.ActualHeight;
        if (h <= 0) return;

        double thumbH = Thumb.Height;
        double usableH = Math.Max(1, h - thumbH);
        double clampedY = Math.Clamp(pt.Y - thumbH / 2.0, 0, usableH);
        double fraction = 1.0 - (clampedY / usableH);
        double raw = Minimum + fraction * (Maximum - Minimum);
        double stepped = Math.Round(raw * 2.0) / 2.0; // 0.5 dB steps
        stepped = Math.Clamp(stepped, Minimum, Maximum);

        if (Math.Abs(Value - stepped) > 0.001)
        {
            Value = stepped;
            ValueChangedByUser?.Invoke(this, stepped);
        }
    }

    private void TrackArea_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _dragging = true;
        TrackArea.CaptureMouse();
        SetFromPoint(e.GetPosition(TrackArea));
        e.Handled = true;
    }

    private void TrackArea_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragging) return;
        SetFromPoint(e.GetPosition(TrackArea));
    }

    private void TrackArea_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_dragging) return;
        _dragging = false;
        TrackArea.ReleaseMouseCapture();
        e.Handled = true;
    }
}
