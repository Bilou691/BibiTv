using System.Windows;
using System.Windows.Threading;

namespace BibiTv;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // Charge les bibliothèques natives de LibVLC (dossier libvlc\win-x64).
        LibVLCSharp.Shared.Core.Initialize();
        DispatcherUnhandledException += OnUnhandledException;
        base.OnStartup(e);
    }

    private static void OnUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        MessageBox.Show(e.Exception.Message, "BibiTV", MessageBoxButton.OK, MessageBoxImage.Warning);
        e.Handled = true;
    }
}
