using System.Diagnostics;

namespace MeNoter.Core;

/// <summary>Collegamenti con l'icona di MeNoter sul Desktop e nel menu Start (l'app si estrae da uno zip, senza installazione).</summary>
public static class Shortcuts
{
    public static void Create()
    {
        var exe = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName;
        var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        var start = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
        foreach (var folder in new[] { desktop, start })
        {
            var type = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("WScript.Shell non disponibile");
            dynamic shell = Activator.CreateInstance(type)!;
            dynamic link = shell.CreateShortcut(Path.Combine(folder, "MeNoter.lnk"));
            link.TargetPath = exe;
            link.WorkingDirectory = Path.GetDirectoryName(exe);
            link.IconLocation = exe + ",0";
            link.Description = "MeNoter – registra e trascrivi le chiamate";
            link.Save();
        }
    }
}
