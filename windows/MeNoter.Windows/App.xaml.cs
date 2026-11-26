using System.Windows;
using System.Windows.Threading;

namespace MeNoter;

public partial class App : Application
{
    private void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        MessageBox.Show("Si è verificato un errore:\n\n" + e.Exception.Message, "MeNoter",
            MessageBoxButton.OK, MessageBoxImage.Warning);
        e.Handled = true;
    }
}
