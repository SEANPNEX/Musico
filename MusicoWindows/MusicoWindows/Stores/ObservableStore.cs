using System.ComponentModel;
using System.Runtime.CompilerServices;
#if WINDOWS
using System.Windows;
#endif

namespace MusicoWindows.Stores;

/// <summary>
/// Base class for observable stores. Marshals PropertyChanged notifications to the WPF UI Dispatcher
/// or captured SynchronizationContext when available, and executes directly in headless unit tests.
/// </summary>
public abstract class ObservableStore : INotifyPropertyChanged
{
    private readonly SynchronizationContext? _syncContext = SynchronizationContext.Current;

    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool SetProperty<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    protected void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        var handler = PropertyChanged;
        if (handler is null) return;

        InvokeOnUiContext(() => handler(this, new PropertyChangedEventArgs(propertyName)));
    }

    protected void InvokeOnUiContext(Action action)
    {
#if WINDOWS
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is not null)
        {
            if (dispatcher.CheckAccess())
            {
                action();
            }
            else
            {
                dispatcher.BeginInvoke(action);
            }
            return;
        }
#endif

        if (_syncContext is not null && SynchronizationContext.Current != _syncContext)
        {
            _syncContext.Post(_ => action(), null);
            return;
        }

        action();
    }
}
