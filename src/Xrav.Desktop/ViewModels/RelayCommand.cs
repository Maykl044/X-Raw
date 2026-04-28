using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;

namespace Xrav.Desktop.ViewModels;

public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> _execute;
    private readonly Func<object?, bool>? _canExecute;

    public RelayCommand(Action execute, Func<bool>? canExecute = null)
        : this(_ => execute(), canExecute is null ? null : _ => canExecute()) { }

    public RelayCommand(Action<object?> execute, Func<object?, bool>? canExecute = null)
    {
        _execute = execute;
        _canExecute = canExecute;
    }

    public event EventHandler? CanExecuteChanged;

    public bool CanExecute(object? parameter) => _canExecute?.Invoke(parameter) ?? true;

    public void Execute(object? parameter) => _execute(parameter);

    public void RaiseCanExecuteChanged()
    {
        var handler = CanExecuteChanged;
        if (handler is null) return;
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null || dispatcher.CheckAccess())
            handler(this, EventArgs.Empty);
        else
            dispatcher.BeginInvoke(DispatcherPriority.Normal, new Action(() => handler(this, EventArgs.Empty)));
    }
}
