using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using MeNoter.Audio;
using MeNoter.Core;
using NAudio.CoreAudioApi;

namespace MeNoter;

public partial class MainWindow : Window
{
    private readonly Settings _settings = Settings.Load();
    private readonly DispatcherTimer _uiTimer = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private List<Note> _allNotes = new();
    private Note? _current;
    private bool _loadingDevices;

    // Registrazione in corso.
    private CallRecorder? _recorder;
    private Note? _recordingNote;
    private float _micLevel, _sysLevel;
    private LevelMonitor? _monitor;

    // Trascrizione in corso (una alla volta).
    private Note? _busyNote;
    private CancellationTokenSource? _work;

    public MainWindow()
    {
        InitializeComponent();
        foreach (var r in ClaudeRequest.All)
        {
            var b = new Button { Content = r.Label, Tag = r };
            b.Click += Claude_Click;
            ClaudeButtons.Children.Add(b);
        }
        _uiTimer.Tick += (_, _) => OnUiTick();
        _uiTimer.Start();
        LoadDevices();
        LoadNotes();
    }

    // ---------------- Dispositivi audio ----------------

    private void LoadDevices()
    {
        _loadingDevices = true;
        try
        {
            var mics = AudioDevices.List(DataFlow.Capture);
            var outs = AudioDevices.List(DataFlow.Render);
            MicBox.ItemsSource = mics;
            OutBox.ItemsSource = outs;
            MicBox.SelectedItem = mics.FirstOrDefault(d => d.Id == _settings.MicId) ?? mics.FirstOrDefault();
            OutBox.SelectedItem = outs.FirstOrDefault(d => d.Id == _settings.OutputId) ?? outs.FirstOrDefault();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a leggere i dispositivi audio:\n" + ex.Message, "MeNoter");
        }
        finally
        {
            _loadingDevices = false;
        }
    }

    private void Device_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingDevices) return;
        _settings.MicId = (MicBox.SelectedItem as AudioDevice)?.Id;
        _settings.OutputId = (OutBox.SelectedItem as AudioDevice)?.Id;
        _settings.Save();
        if (_monitor != null)
        {
            // Si riascolta subito con i nuovi dispositivi.
            StopMonitor();
            StartMonitor();
        }
    }

    private void RefreshDevices_Click(object sender, RoutedEventArgs e)
    {
        if (_recorder != null) return;
        bool wasTesting = _monitor != null;
        StopMonitor();
        LoadDevices();
        if (wasTesting) StartMonitor();
    }

    private void Test_Click(object sender, RoutedEventArgs e)
    {
        if (_monitor != null) StopMonitor();
        else StartMonitor();
    }

    private void StartMonitor()
    {
        try
        {
            _monitor = new LevelMonitor(
                AudioDevices.Get(_settings.MicId, DataFlow.Capture),
                AudioDevices.Get(_settings.OutputId, DataFlow.Render));
            TestButton.Content = "Ferma prova";
        }
        catch (Exception ex)
        {
            _monitor = null;
            MessageBox.Show(this, "Non riesco ad ascoltare questi dispositivi:\n" + ex.Message, "MeNoter");
        }
    }

    private void StopMonitor()
    {
        _monitor?.Dispose();
        _monitor = null;
        TestButton.Content = "Prova audio";
        MicLevel.Value = 0;
        OutLevel.Value = 0;
    }

    private void OnUiTick()
    {
        if (_recorder != null)
        {
            TimerText.Text = NoteStore.FormatTime(_recorder.Elapsed);
            MicLevel.Value = Smooth(MicLevel.Value, _micLevel);
            OutLevel.Value = Smooth(OutLevel.Value, _sysLevel);
        }
        else if (_monitor != null)
        {
            var (mic, sys) = _monitor.Take();
            MicLevel.Value = Smooth(MicLevel.Value, mic);
            OutLevel.Value = Smooth(OutLevel.Value, sys);
        }
    }

    // Indicatore che sale subito e scende piano; scala "a orecchio" per vedere anche le voci basse.
    private static double Smooth(double current, float peak)
    {
        double target = Math.Min(1, Math.Sqrt(peak) * 1.2);
        return target > current ? target : current * 0.85;
    }

    // ---------------- Registrazione ----------------

    private void Record_Click(object sender, RoutedEventArgs e)
    {
        if (_recorder != null) return;
        StopMonitor();
        MMDevice mic, output;
        try
        {
            mic = AudioDevices.Get(_settings.MicId, DataFlow.Capture);
            output = AudioDevices.Get(_settings.OutputId, DataFlow.Render);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Nessun dispositivo audio disponibile:\n" + ex.Message, "MeNoter");
            return;
        }

        var title = string.IsNullOrWhiteSpace(NewTitleBox.Text)
            ? "Riunione " + DateTime.Now.ToString("d MMM HH:mm", NoteStore.Italian)
            : NewTitleBox.Text.Trim();
        Note note;
        try
        {
            note = NoteStore.Create(_settings.ResolveNotesFolder(), title);
            note.Meta.Status = "registrazione";
            note.Meta.MicName = mic.FriendlyName;
            note.Meta.OutputName = output.FriendlyName;
            note.Meta.TwoTracks = true;
            note.Save();
            _recorder = new CallRecorder(mic, output, note.MicPath, note.SysPath);
        }
        catch (Exception ex)
        {
            _recorder = null;
            MessageBox.Show(this, "Non riesco a iniziare la registrazione:\n" + ex.Message, "MeNoter");
            return;
        }

        _recordingNote = note;
        _recorder.Levels += (m, s) =>
        {
            _micLevel = m;
            _sysLevel = s;
        };
        _recorder.DeviceError += msg => Dispatcher.InvokeAsync(() =>
            MessageBox.Show(this, msg + "\n\nPremi Fine per salvare quello che è stato registrato finora.", "MeNoter"));
        try
        {
            _recorder.Start();
        }
        catch (Exception ex)
        {
            _recorder.Dispose();
            _recorder = null;
            MessageBox.Show(this, "Non riesco a iniziare la registrazione:\n" + ex.Message, "MeNoter");
            return;
        }
        KeepAwake(true);
        RecordButton.IsEnabled = false;
        PauseButton.IsEnabled = true;
        StopButton.IsEnabled = true;
        MicBox.IsEnabled = OutBox.IsEnabled = TestButton.IsEnabled = false;
        NewTitleBox.Text = "";
        LoadNotes(select: note);
    }

    private void Pause_Click(object sender, RoutedEventArgs e)
    {
        if (_recorder == null) return;
        if (_recorder.Paused)
        {
            _recorder.Resume();
            PauseButton.Content = "❚❚  Pausa";
        }
        else
        {
            _recorder.Pause();
            PauseButton.Content = "▶  Riprendi";
        }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e) => await StopRecordingAsync();

    private async Task StopRecordingAsync()
    {
        if (_recorder == null || _recordingNote == null) return;
        var recorder = _recorder;
        var note = _recordingNote;
        _recorder = null;
        _recordingNote = null;
        var duration = recorder.Stop();
        recorder.Dispose();
        KeepAwake(false);
        RecordButton.IsEnabled = true;
        PauseButton.IsEnabled = false;
        PauseButton.Content = "❚❚  Pausa";
        StopButton.IsEnabled = false;
        MicBox.IsEnabled = OutBox.IsEnabled = TestButton.IsEnabled = true;
        MicLevel.Value = OutLevel.Value = 0;
        TimerText.Text = "00:00";

        note.Meta.DurationSec = duration.TotalSeconds;
        note.Meta.Status = "da trascrivere";
        note.Save();
        try
        {
            // Le due tracce mescolate: per riascoltare e per l'app Android.
            await Task.Run(() => Wav.Mix(note.MicPath, note.SysPath, note.AudioPath));
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Registrazione salvata, ma non riesco a unire le tracce:\n" + ex.Message, "MeNoter");
        }
        LoadNotes(select: note);
        if (_settings.AutoTranscribe) await TranscribeAsync(note);
    }

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    /// <summary>Durante la registrazione il PC non va in sospensione.</summary>
    private static void KeepAwake(bool on) =>
        SetThreadExecutionState(on ? 0x80000000u | 0x00000001u : 0x80000000u);

    // ---------------- Importazione ----------------

    private async void Import_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Importa una registrazione",
            Filter = "Audio e video|*.mp3;*.m4a;*.wav;*.mp4;*.aac;*.wma;*.ogg;*.opus;*.flac;*.mov|Tutti i file|*.*",
        };
        if (dlg.ShowDialog(this) != true) return;
        var note = NoteStore.Create(_settings.ResolveNotesFolder(), Path.GetFileNameWithoutExtension(dlg.FileName));
        note.Meta.Status = "importazione";
        note.Save();
        LoadNotes(select: note);
        try
        {
            var duration = await Task.Run(() => Wav.Convert(dlg.FileName, note.AudioPath));
            note.Meta.DurationSec = duration.TotalSeconds;
            note.Meta.Status = "da trascrivere";
            note.Save();
        }
        catch (Exception ex)
        {
            note.Meta.Status = "errore";
            note.Save();
            LoadNotes(select: note);
            MessageBox.Show(this, "Non riesco a leggere questo file audio:\n" + ex.Message, "MeNoter");
            return;
        }
        LoadNotes(select: note);
        await TranscribeAsync(note);
    }

    // ---------------- Trascrizione ----------------

    private async void Transcribe_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        if (_busyNote == _current)
        {
            _work?.Cancel();
            return;
        }
        if (_current.HasTranscript &&
            MessageBox.Show(this, "Rifare la trascrizione? Quella attuale verrà sostituita (le risposte di Claude restano).",
                "MeNoter", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        await TranscribeAsync(_current);
    }

    private async Task TranscribeAsync(Note note)
    {
        if (_busyNote != null)
        {
            MessageBox.Show(this, "C'è già una trascrizione in corso: aspetta che finisca.", "MeNoter");
            return;
        }
        var tracks = note.Meta.TwoTracks && File.Exists(note.MicPath) && File.Exists(note.SysPath)
            ? new List<(string, string)> { (note.MicPath, "Io"), (note.SysPath, "Altri") }
            : new List<(string, string)> { (note.AudioPath, "") };
        if (!File.Exists(tracks[0].Item1))
        {
            MessageBox.Show(this, "Manca il file audio di questa nota.", "MeNoter");
            return;
        }

        var model = WhisperModel.ByKey(_settings.Model);
        _busyNote = note;
        _work = new CancellationTokenSource();
        var ct = _work.Token;
        try
        {
            if (!model.Downloaded)
            {
                if (MessageBox.Show(this,
                        $"Per trascrivere serve il modello \"{model.Label}\" ({model.SizeMb} MB). Lo scarico adesso? Serve una volta sola.",
                        "MeNoter", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
                ShowProgress(note, "Scarico il modello di trascrizione…", 0);
                await model.DownloadAsync(new Progress<double>(p => ShowProgress(note, "Scarico il modello di trascrizione…", p)), ct);
            }

            note.Meta.Status = "trascrizione";
            note.Save();
            RefreshListItem(note);
            var sw = Stopwatch.StartNew();
            var label = tracks.Count == 2 ? "Trascrivo la tua voce e quella degli altri…" : "Trascrivo…";
            ShowProgress(note, label, 0);
            var progress = new Progress<double>(p =>
            {
                var eta = p > 0.03 ? TimeSpan.FromSeconds(sw.Elapsed.TotalSeconds * (1 - p) / p) : (TimeSpan?)null;
                ShowProgress(note, label + (eta != null ? $"  circa {Math.Ceiling(eta.Value.TotalMinutes)} min" : ""), p);
            });
            var lang = _settings.Language;
            var segments = await Task.Run(() => Transcriber.RunAsync(model, lang, tracks, progress, ct), ct);
            await File.WriteAllTextAsync(note.TranscriptPath, Transcriber.Format(segments), ct);
            note.Meta.Status = segments.Count == 0 ? "nessun parlato trovato" : "pronta";
            note.Save();
        }
        catch (OperationCanceledException)
        {
            note.Meta.Status = note.HasTranscript ? "pronta" : "da trascrivere";
            note.Save();
        }
        catch (Exception ex)
        {
            note.Meta.Status = "errore";
            note.Save();
            MessageBox.Show(this, "La trascrizione non è riuscita:\n" + ex.Message, "MeNoter");
        }
        finally
        {
            _busyNote = null;
            _work = null;
            HideProgress();
            RefreshListItem(note);
            if (_current == note) ShowNote(note);
        }
    }

    private void ShowProgress(Note note, string text, double value)
    {
        if (_current != note) return;
        ProgressPanel.Visibility = Visibility.Visible;
        ProgressText.Text = text;
        TaskProgress.Value = value;
        TranscribeButton.Content = "Annulla";
    }

    private void HideProgress()
    {
        ProgressPanel.Visibility = Visibility.Collapsed;
        TranscribeButton.Content = "Trascrivi";
    }

    // ---------------- Claude ----------------

    private void Claude_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ClaudeRequest request }) SendToClaude(request);
    }

    private void Ask_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(AskBox.Text))
        {
            AskBox.Focus();
            return;
        }
        SendToClaude(ClaudeRequest.Ask(AskBox.Text));
        AskBox.Text = "";
    }

    private void SendToClaude(ClaudeRequest request)
    {
        if (_current == null) return;
        if (!_current.HasTranscript)
        {
            MessageBox.Show(this, "La trascrizione non è ancora pronta.", "MeNoter");
            return;
        }
        try
        {
            var file = ClaudeBridge.PrepareFile(_current, request);
            _current.Meta.LastRequest = request.Label;
            _current.Save();
            ClaudeBridge.Send(file, _settings.OpenClaudeDesktop);
            ClaudeHint.Text = $"«{request.Label}»: il file è negli Appunti. In Claude premi Ctrl+V e Invio. " +
                              "Quando risponde, copia la risposta e premi «Incolla risposta di Claude».";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a preparare la richiesta:\n" + ex.Message, "MeNoter");
        }
    }

    private void Paste_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        if (!Clipboard.ContainsText())
        {
            MessageBox.Show(this, "Negli Appunti non c'è testo: in Claude usa il pulsante Copia sotto la risposta.", "MeNoter");
            return;
        }
        var text = Clipboard.GetText().Trim();
        if (text.Length == 0) return;
        var label = _current.Meta.LastRequest ?? "Risposta";
        var path = _current.SaveResponse(label, text);

        // Il riassunto comincia con "# Titolo": se la nota ha ancora il nome automatico, lo prende.
        var first = text.Split('\n').FirstOrDefault()?.Trim() ?? "";
        if (label == "Riassunto" && first.StartsWith("# ") && _current.Meta.Title.StartsWith("Riunione "))
        {
            _current.Meta.Title = first[2..].Trim();
            _current.Save();
            RefreshListItem(_current);
        }
        ShowNote(_current, selectFile: path);
        ClaudeHint.Text = $"Risposta salvata come «{label}».";
    }

    // ---------------- Elenco e dettaglio ----------------

    private void LoadNotes(Note? select = null)
    {
        var keep = select?.Folder ?? _current?.Folder;
        _allNotes = NoteStore.List(_settings.ResolveNotesFolder());
        ApplyFilter();
        var match = _allNotes.FirstOrDefault(n => n.Folder == keep);
        if (match != null) NotesList.SelectedItem = match;
    }

    private void ApplyFilter()
    {
        var q = SearchBox.Text.Trim();
        var list = q.Length == 0
            ? _allNotes
            : _allNotes.Where(n =>
                n.Meta.Title.Contains(q, StringComparison.CurrentCultureIgnoreCase) ||
                n.Transcript.Contains(q, StringComparison.CurrentCultureIgnoreCase)).ToList();
        NotesList.ItemsSource = list;
    }

    private void Search_Changed(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void RefreshListItem(Note note)
    {
        note.Reload();
        NotesList.Items.Refresh();
    }

    private void Notes_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (NotesList.SelectedItem is Note n) ShowNote(n);
    }

    private void ShowNote(Note note, string? selectFile = null)
    {
        _current = note;
        EmptyText.Visibility = Visibility.Collapsed;
        DetailPanel.Visibility = Visibility.Visible;
        TitleBox.Text = note.Meta.Title;
        var info = note.Subtitle;
        if (note.Meta.TwoTracks && note.Meta.MicName != null)
            info += $"   ·   🎙 {note.Meta.MicName}   🎧 {note.Meta.OutputName}";
        SubtitleText.Text = info;
        if (_busyNote == note) ProgressPanel.Visibility = Visibility.Visible;
        else HideProgress();
        TranscribeButton.IsEnabled = note.Meta.Status != "registrazione";

        ContentTabs.Items.Clear();
        var search = SearchBox.Text.Trim();
        ContentTabs.Items.Add(new TabItem
        {
            Header = "Trascrizione",
            Content = TextView(note.HasTranscript ? note.Transcript
                : note.Meta.Status == "registrazione" ? "Registrazione in corso…"
                : "La trascrizione comparirà qui.", search),
        });
        TabItem? toSelect = null;
        foreach (var file in note.Responses())
        {
            var name = Path.GetFileNameWithoutExtension(file);
            // Il nome del file è "Etichetta aaaa-mm-gg hh.mm.ss": nella scheda resta l'etichetta.
            var header = name.Length > 20 ? name[..^20].Trim() : name;
            var tab = new TabItem { Header = header, ToolTip = name, Content = ResponseView(note, file) };
            ContentTabs.Items.Add(tab);
            if (file == selectFile) toSelect = tab;
        }
        ContentTabs.SelectedItem = toSelect ?? ContentTabs.Items[0];
    }

    private static TextBox TextView(string text, string search = "")
    {
        var box = new TextBox
        {
            Text = text,
            IsReadOnly = true,
            TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(4, 10, 4, 4),
            FontSize = 14.5,
        };
        if (search.Length > 0)
        {
            // Si parte dalla prima parola cercata.
            box.Loaded += (_, _) =>
            {
                int i = text.IndexOf(search, StringComparison.CurrentCultureIgnoreCase);
                if (i < 0) return;
                box.Focus();
                box.Select(i, search.Length);
                box.ScrollToLine(Math.Max(0, box.GetLineIndexFromCharacterIndex(i) - 2));
            };
        }
        return box;
    }

    private UIElement ResponseView(Note note, string file)
    {
        var panel = new DockPanel();
        var bar = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
        DockPanel.SetDock(bar, Dock.Top);
        var copy = new Button { Content = "Copia" };
        copy.Click += (_, _) => Clipboard.SetText(File.ReadAllText(file));
        var open = new Button { Content = "Apri il file" };
        open.Click += (_, _) => Process.Start(new ProcessStartInfo(file) { UseShellExecute = true });
        var delete = new Button { Content = "Elimina" };
        delete.Click += (_, _) =>
        {
            if (MessageBox.Show(this, "Eliminare questa risposta di Claude?", "MeNoter", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            File.Delete(file);
            ShowNote(note);
        };
        bar.Children.Add(copy);
        bar.Children.Add(open);
        bar.Children.Add(delete);
        panel.Children.Add(bar);
        panel.Children.Add(TextView(File.ReadAllText(file)));
        return panel;
    }

    private void Title_LostFocus(object sender, RoutedEventArgs e) => SaveTitle();

    private void Title_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) SaveTitle();
    }

    private void SaveTitle()
    {
        if (_current == null) return;
        var t = TitleBox.Text.Trim();
        if (t.Length == 0 || t == _current.Meta.Title) return;
        _current.Meta.Title = t;
        _current.Save();
        RefreshListItem(_current);
    }

    private void Play_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        var audio = File.Exists(_current.AudioPath) ? _current.AudioPath : _current.SysPath;
        if (!File.Exists(audio))
        {
            MessageBox.Show(this, "L'audio non è ancora pronto.", "MeNoter");
            return;
        }
        Process.Start(new ProcessStartInfo(audio) { UseShellExecute = true });
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        var note = (NotesList.SelectedItem as Note) ?? _current;
        if (note != null) Process.Start(new ProcessStartInfo("explorer.exe", $"\"{note.Folder}\"") { UseShellExecute = true });
    }

    private void OpenRoot_Click(object sender, RoutedEventArgs e)
    {
        var root = _settings.ResolveNotesFolder();
        Directory.CreateDirectory(root);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{root}\"") { UseShellExecute = true });
    }

    /// <summary>Non cancella nulla: sposta la nota nella cartella "_cestino" dentro la cartella delle note.</summary>
    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (NotesList.SelectedItem is not Note note) return;
        if (note == _recordingNote || note == _busyNote)
        {
            MessageBox.Show(this, "Questa nota è in uso: aspetta la fine della registrazione o della trascrizione.", "MeNoter");
            return;
        }
        if (MessageBox.Show(this, $"Spostare «{note.Meta.Title}» nel cestino di MeNoter?", "MeNoter",
                MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
        try
        {
            var bin = Path.Combine(_settings.ResolveNotesFolder(), "_cestino");
            Directory.CreateDirectory(bin);
            var target = Path.Combine(bin, Path.GetFileName(note.Folder));
            if (Directory.Exists(target)) target += " " + DateTime.Now.ToString("HHmmss");
            Directory.Move(note.Folder, target);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a spostare la nota:\n" + ex.Message, "MeNoter");
            return;
        }
        _current = null;
        DetailPanel.Visibility = Visibility.Collapsed;
        EmptyText.Visibility = Visibility.Visible;
        LoadNotes();
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        var w = new SettingsWindow(_settings) { Owner = this };
        if (w.ShowDialog() == true) LoadNotes();
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (_recorder != null)
        {
            if (MessageBox.Show(this, "C'è una registrazione in corso. Chiudere salvando quello che è stato registrato?",
                    "MeNoter", MessageBoxButton.YesNo) != MessageBoxResult.Yes)
            {
                e.Cancel = true;
                return;
            }
            var note = _recordingNote;
            var d = _recorder.Stop();
            _recorder.Dispose();
            _recorder = null;
            if (note != null)
            {
                note.Meta.DurationSec = d.TotalSeconds;
                note.Meta.Status = "da trascrivere";
                note.Save();
                try { Wav.Mix(note.MicPath, note.SysPath, note.AudioPath); } catch { }
            }
            KeepAwake(false);
        }
        _work?.Cancel();
        StopMonitor();
    }
}
