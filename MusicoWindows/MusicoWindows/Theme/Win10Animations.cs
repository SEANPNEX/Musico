using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace MusicoWindows.Theme;

/// <summary>
/// Standard WPF Fluent Design motion helpers (Storyboard + CubicEase/QuadraticEase)
/// targeting RenderTransform, Opacity, and Width with zero external dependencies.
/// </summary>
public static class Win10Animations
{
    private sealed class GridLengthAnimation : AnimationTimeline
    {
        public static readonly DependencyProperty FromProperty =
            DependencyProperty.Register(nameof(From), typeof(GridLength), typeof(GridLengthAnimation));

        public static readonly DependencyProperty ToProperty =
            DependencyProperty.Register(nameof(To), typeof(GridLength), typeof(GridLengthAnimation));

        public static readonly DependencyProperty EasingFunctionProperty =
            DependencyProperty.Register(nameof(EasingFunction), typeof(IEasingFunction), typeof(GridLengthAnimation));

        public GridLength From
        {
            get => (GridLength)GetValue(FromProperty);
            set => SetValue(FromProperty, value);
        }

        public GridLength To
        {
            get => (GridLength)GetValue(ToProperty);
            set => SetValue(ToProperty, value);
        }

        public IEasingFunction? EasingFunction
        {
            get => (IEasingFunction?)GetValue(EasingFunctionProperty);
            set => SetValue(EasingFunctionProperty, value);
        }

        public override Type TargetPropertyType => typeof(GridLength);

        protected override Freezable CreateInstanceCore() => new GridLengthAnimation();

        public override object GetCurrentValue(object defaultOriginValue, object defaultDestinationValue, AnimationClock animationClock)
        {
            double progress = animationClock.CurrentProgress ?? 0.0;
            if (EasingFunction is { } ease)
            {
                progress = ease.Ease(progress);
            }

            double fromVal = From.Value;
            double toVal = To.Value;
            double current = fromVal + ((toVal - fromVal) * progress);
            return new GridLength(Math.Max(0, current), GridUnitType.Pixel);
        }
    }

    public static void AnimatePageEntrance(UIElement? target)
    {
        if (target is null) return;

        var transform = EnsureTranslateTransform(target);
        transform.BeginAnimation(TranslateTransform.YProperty, null);
        transform.Y = 16;
        target.Opacity = 0;

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = TimeSpan.FromMilliseconds(220);

        var slideY = new DoubleAnimation(16, 0, duration) { EasingFunction = ease };
        var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(190))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        };

        var sb = new Storyboard();
        Storyboard.SetTarget(fade, target);
        Storyboard.SetTargetProperty(fade, new PropertyPath(UIElement.OpacityProperty));
        sb.Children.Add(fade);

        sb.Begin();
        transform.BeginAnimation(TranslateTransform.YProperty, slideY, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimateSlideX(UIElement? target, double fromOffset)
    {
        if (target is null) return;

        var transform = EnsureTranslateTransform(target);
        transform.BeginAnimation(TranslateTransform.XProperty, null);
        transform.X = fromOffset;
        target.Opacity = 0.35;

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = TimeSpan.FromMilliseconds(200);

        var slideX = new DoubleAnimation(fromOffset, 0, duration) { EasingFunction = ease };
        var fade = new DoubleAnimation(0.35, 1, TimeSpan.FromMilliseconds(180))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        };

        var sb = new Storyboard();
        Storyboard.SetTarget(fade, target);
        Storyboard.SetTargetProperty(fade, new PropertyPath(UIElement.OpacityProperty));
        sb.Children.Add(fade);

        sb.Begin();
        transform.BeginAnimation(TranslateTransform.XProperty, slideX, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimatePanelIn(UIElement? target, double fromX = 28)
    {
        if (target is null) return;

        var transform = EnsureTranslateTransform(target);
        transform.BeginAnimation(TranslateTransform.XProperty, null);
        transform.X = fromX;
        target.Opacity = 0;

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var slideX = new DoubleAnimation(fromX, 0, TimeSpan.FromMilliseconds(200)) { EasingFunction = ease };
        var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(170))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        };

        var sb = new Storyboard();
        Storyboard.SetTarget(fade, target);
        Storyboard.SetTargetProperty(fade, new PropertyPath(UIElement.OpacityProperty));
        sb.Children.Add(fade);

        sb.Begin();
        transform.BeginAnimation(TranslateTransform.XProperty, slideX, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimatePanelWidth(
        ColumnDefinition column,
        double fromWidth,
        double toWidth,
        int durationMs = 200,
        Action? onCompleted = null)
    {
        if (column is null) return;

        var anim = new GridLengthAnimation
        {
            From = new GridLength(fromWidth, GridUnitType.Pixel),
            To = new GridLength(toWidth, GridUnitType.Pixel),
            Duration = TimeSpan.FromMilliseconds(durationMs),
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };

        if (onCompleted is not null)
        {
            anim.Completed += (_, _) => onCompleted();
        }

        column.BeginAnimation(ColumnDefinition.WidthProperty, anim, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimatePanelWidth(
        FrameworkElement element,
        double fromWidth,
        double toWidth,
        int durationMs = 200,
        Action? onCompleted = null)
    {
        if (element is null) return;

        var anim = new DoubleAnimation(fromWidth, toWidth, TimeSpan.FromMilliseconds(durationMs))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };

        if (onCompleted is not null)
        {
            anim.Completed += (_, _) => onCompleted();
        }

        element.BeginAnimation(FrameworkElement.WidthProperty, anim, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimateModalOpen(UIElement? backdrop, UIElement? dialogCard)
    {
        if (backdrop is not null)
        {
            backdrop.Opacity = 0;
            var backdropFade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(170))
            {
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            };
            var sbBackdrop = new Storyboard();
            Storyboard.SetTarget(backdropFade, backdrop);
            Storyboard.SetTargetProperty(backdropFade, new PropertyPath(UIElement.OpacityProperty));
            sbBackdrop.Children.Add(backdropFade);
            sbBackdrop.Begin();
        }

        if (dialogCard is not null)
        {
            var scale = EnsureScaleTransform(dialogCard);
            scale.ScaleX = 0.95;
            scale.ScaleY = 0.95;
            dialogCard.Opacity = 0;

            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            var duration = TimeSpan.FromMilliseconds(210);

            var sx = new DoubleAnimation(0.95, 1.0, duration) { EasingFunction = ease };
            var sy = new DoubleAnimation(0.95, 1.0, duration) { EasingFunction = ease };
            var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180))
            {
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            };

            var sbCard = new Storyboard();
            Storyboard.SetTarget(fade, dialogCard);
            Storyboard.SetTargetProperty(fade, new PropertyPath(UIElement.OpacityProperty));
            sbCard.Children.Add(fade);
            sbCard.Begin();

            scale.BeginAnimation(ScaleTransform.ScaleXProperty, sx, HandoffBehavior.SnapshotAndReplace);
            scale.BeginAnimation(ScaleTransform.ScaleYProperty, sy, HandoffBehavior.SnapshotAndReplace);
        }
    }

    public static void AnimateScale(UIElement? target, double toScale, int durationMs = 260)
    {
        if (target is null) return;

        var scale = EnsureScaleTransform(target);
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = TimeSpan.FromMilliseconds(durationMs);

        var sx = new DoubleAnimation(toScale, duration) { EasingFunction = ease };
        var sy = new DoubleAnimation(toScale, duration) { EasingFunction = ease };

        scale.BeginAnimation(ScaleTransform.ScaleXProperty, sx, HandoffBehavior.SnapshotAndReplace);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, sy, HandoffBehavior.SnapshotAndReplace);
    }

    public static void AnimateFadeIn(UIElement? target, double fromOpacity = 0.35, int durationMs = 220)
    {
        if (target is null) return;

        var anim = new DoubleAnimation(fromOpacity, 1.0, TimeSpan.FromMilliseconds(durationMs))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        };
        var sb = new Storyboard();
        Storyboard.SetTarget(anim, target);
        Storyboard.SetTargetProperty(anim, new PropertyPath(UIElement.OpacityProperty));
        sb.Children.Add(anim);
        sb.Begin();
    }

    private static TranslateTransform EnsureTranslateTransform(UIElement target)
    {
        if (target.RenderTransform is TranslateTransform tt && !tt.IsFrozen)
        {
            return tt;
        }
        var created = new TranslateTransform();
        target.RenderTransform = created;
        return created;
    }

    private static ScaleTransform EnsureScaleTransform(UIElement target)
    {
        target.RenderTransformOrigin = new Point(0.5, 0.5);
        if (target.RenderTransform is ScaleTransform st && !st.IsFrozen)
        {
            return st;
        }
        var created = new ScaleTransform(1.0, 1.0);
        target.RenderTransform = created;
        return created;
    }
}
