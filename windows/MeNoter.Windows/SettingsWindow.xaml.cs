using System.Windows;
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
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Cartella delle note di MeNoter", InitialDirectory = FolderBox.Text };
        if (dlg.ShowDialog(this) == true) FolderBox.Text = dlg.FolderName;
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
        _settings.NotesFolder = folder;
        _settings.Model = ((WhisperModel)ModelBox.SelectedItem).Key;
        _settings.Language = Languages[Math.Max(0, LanguageBox.SelectedIndex)].Code;
        _settings.AutoTranscribe = AutoBox.IsChecked == true;
        _settings.SkipSilence = SkipBox.IsChecked == true;
        _settings.OpenClaudeDesktop = DesktopBox.IsChecked == true;
        _settings.AutoPaste = PasteBox.IsChecked == true;
        _settings.Save();
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
