using System.Windows;
using System.Windows.Controls;
using MeNoter.Core;

namespace MeNoter;

public partial class SettingsWindow : Window
{
    private static readonly (string Code, string Label)[] Languages =
    {
        ("it", "Italiano"), ("en", "Inglese"), ("fr", "Francese"), ("es", "Spagnolo"), ("de", "Tedesco"),
        ("auto", "Riconosci da sola (più lento)"),
    };

    private readonly Settings _settings;
    private readonly Dictionary<string, (CheckBox Check, TextBox Folder)> _sync = new();

    public SettingsWindow(Settings settings)
    {
        InitializeComponent();
        _settings = settings;
        FolderBox.Text = settings.ResolveNotesFolder();
        ModelBox.ItemsSource = WhisperModel.All;
        ModelBox.SelectedItem = WhisperModel.ByKey(settings.Model);
        LanguageBox.ItemsSource = Languages.Select(l => l.Label).ToList();
        LanguageBox.SelectedIndex = Math.Max(0, Array.FindIndex(Languages, l => l.Code == settings.Language));
        AutoBox.IsChecked = settings.AutoTranscribe;
        SkipBox.IsChecked = settings.SkipSilence;
        DesktopBox.IsChecked = settings.OpenClaudeDesktop;
        PasteBox.IsChecked = settings.AutoPaste;

        foreach (var svc in CloudSync.Services)
        {
            var detected = svc.Detect();
            var chosen = settings.SyncFolders.TryGetValue(svc.Key, out var f) && !string.IsNullOrWhiteSpace(f) ? f : detected;
            var check = new CheckBox
            {
                Content = svc.Name,
                FontWeight = FontWeights.SemiBold,
                IsChecked = settings.SyncEnabled.Contains(svc.Key),
                Margin = new Thickness(0, 0, 0, 6),
            };
            var folder = new TextBox { Text = chosen ?? "", Style = (Style)FindResource("Field") };
            var browse = new Button { Content = "Sfoglia…", Margin = new Thickness(8, 0, 0, 0) };
            browse.Click += (_, _) =>
            {
                var dlg = new Microsoft.Win32.OpenFolderDialog { Title = $"Cartella di {svc.Name} sul PC" };
                if (Directory.Exists(folder.Text)) dlg.InitialDirectory = folder.Text;
                if (dlg.ShowDialog(this) == true) folder.Text = dlg.FolderName;
            };
            var row = new DockPanel { Margin = new Thickness(24, 0, 0, 2) };
            DockPanel.SetDock(browse, Dock.Right);
            row.Children.Add(browse);
            row.Children.Add(folder);
            var hint = new TextBlock
            {
                Style = (Style)FindResource("Hint"),
                Margin = new Thickness(24, 0, 0, 14),
                Text = detected != null
                    ? $"Trovata sul PC. Le note andranno in {Path.Combine(chosen ?? detected, "MeNoter")}."
                    : $"Non trovata: installa {svc.App} e accedi, oppure scegli la cartella a mano.",
            };
            _sync[svc.Key] = (check, folder);
            SyncPanel.Children.Add(check);
            SyncPanel.Children.Add(row);
            SyncPanel.Children.Add(hint);
        }
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Cartella delle note di MeNoter", InitialDirectory = FolderBox.Text };
        if (dlg.ShowDialog(this) == true) FolderBox.Text = dlg.FolderName;
    }

    private void Shortcuts_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Shortcuts.Create();
            MessageBox.Show(this, "Fatto: MeNoter ora è sul Desktop e nel menu Start.", "MeNoter");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a creare i collegamenti:\n" + ex.Message, "MeNoter");
        }
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var folder = FolderBox.Text.Trim();
        try
        {
            Directory.CreateDirectory(folder);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a usare questa cartella:\n" + ex.Message, "MeNoter");
            return;
        }
        var enabled = new List<string>();
        var folders = new Dictionary<string, string>();
        foreach (var (key, (check, box)) in _sync)
        {
            var path = box.Text.Trim();
            if (path.Length > 0) folders[key] = path;
            if (check.IsChecked != true) continue;
            if (path.Length == 0 || !Directory.Exists(path))
            {
                MessageBox.Show(this, $"Per {check.Content} scegli una cartella che esiste sul PC.", "MeNoter");
                return;
            }
            enabled.Add(key);
        }
        _settings.NotesFolder = folder;
        _settings.Model = ((WhisperModel)ModelBox.SelectedItem).Key;
        _settings.Language = Languages[Math.Max(0, LanguageBox.SelectedIndex)].Code;
        _settings.AutoTranscribe = AutoBox.IsChecked == true;
        _settings.SkipSilence = SkipBox.IsChecked == true;
        _settings.OpenClaudeDesktop = DesktopBox.IsChecked == true;
        _settings.AutoPaste = PasteBox.IsChecked == true;
        _settings.SyncEnabled = enabled;
        _settings.SyncFolders = folders;
        _settings.Save();
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
