using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
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
    private string _busyText = "";
    private double _busyValue;
    private string? _lastClaudeFile;

    // Sincronizzazione con Google Drive / OneDrive / Box (facoltativa).
    private readonly DispatcherTimer _syncTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private bool _syncing;

    public MainWindow()
    {
        InitializeComponent();
        foreach (var r in ClaudeRequest.All)
        {
            var content = new StackPanel();
            content.Children.Add(new TextBlock { Text = r.Icon, FontSize = 20, HorizontalAlignment = HorizontalAlignment.Center });
            content.Children.Add(new TextBlock
            {
                Text = r.Label, HorizontalAlignment = HorizontalAlignment.Center, TextAlignment = TextAlignment.Center,
                TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 0),
            });
            var b = new Button { Content = content, Tag = r, Style = (Style)FindResource("Tile") };
            b.Click += Claude_Click;
            ClaudeButtons.Children.Add(b);
        }
        _syncTimer.Tick += async (_, _) =>
        {
            _syncTimer.Stop();
            await SyncAsync();
        };
        _uiTimer.Tick += (_, _) => OnUiTick();
        _uiTimer.Start();
        ApplyClaudeCollapsed();
        LoadDevices();
        LoadNotes();
        UpdateSyncButton();
        RequestSync();
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
        RequestSync();
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
            var skip = _settings.SkipSilence;
            var report = new Transcriber.Report();
            List<Segment> segments;
            try
            {
                segments = await Task.Run(() => Transcriber.RunAsync(model, lang, tracks, skip, report, progress, ct), ct);
            }
            finally
            {
                // Diagnostica: quanta voce ha trovato e quante frasi ha scartato, per capire eventuali buchi.
                try { await File.AppendAllTextAsync(note.LogPath, report + Environment.NewLine + Environment.NewLine); } catch { }
            }
            // La trascrizione precedente non si perde: resta in trascrizione-precedente.txt.
            if (note.HasTranscript) File.Copy(note.TranscriptPath, note.PreviousTranscriptPath, true);
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
            BusyPanel.Visibility = Visibility.Collapsed;
            HideProgress();
            RefreshListItem(note);
            if (_current == note) ShowNote(note);
            RequestSync();
        }
    }

    /// <summary>
    /// Avanzamento della trascrizione: sempre nel riquadro sopra l'elenco delle note, e nella nota
    /// quando è aperta (anche se ci si torna dopo essere passati ad altre note).
    /// </summary>
    private void ShowProgress(Note note, string text, double value)
    {
        _busyText = text;
        _busyValue = value;
        BusyPanel.Visibility = Visibility.Visible;
        BusyTitle.Text = "✎  " + note.Meta.Title;
        BusyText.Text = $"{text}  {value * 100:0}%";
        BusyProgress.Value = value;
        if (_current != note) return;
        ProgressPanel.Visibility = Visibility.Visible;
        ProgressText.Text = $"{text}  {value * 100:0}%";
        TaskProgress.Value = value;
        TranscribeButton.Content = "✕  Annulla trascrizione";
    }

    private void HideProgress()
    {
        ProgressPanel.Visibility = Visibility.Collapsed;
        TranscribeButton.Content = "✎  Trascrivi";
    }

    private void BusyPanel_Click(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (_busyNote == null) return;
        SearchBox.Text = "";
        LoadNotes(select: _busyNote);
    }

    // ---------------- Claude ----------------

    private async void Claude_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: ClaudeRequest request }) await SendToClaudeAsync(request);
    }

    private async void Ask_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(AskBox.Text))
        {
            AskBox.Focus();
            return;
        }
        var question = AskBox.Text;
        AskBox.Text = "";
        await SendToClaudeAsync(ClaudeRequest.Ask(question));
    }

    private async Task SendToClaudeAsync(ClaudeRequest request)
    {
        if (_current == null) return;
        if (!_current.HasTranscript)
        {
            MessageBox.Show(this, "La trascrizione non è ancora pronta.", "MeNoter");
            return;
        }
        try
        {
            var note = _current;
            var file = ClaudeBridge.PrepareFile(note, request);
            note.Meta.LastRequest = request.Label;
            note.Meta.Pending.RemoveAll(p => p.Label == request.Label);
            note.Meta.Pending.Add(new PendingRequest { Label = request.Label, SentAt = DateTime.Now });
            note.Save();
            UpdatePending(note);
            _lastClaudeFile = file;
            RevealButton.Visibility = Visibility.Visible;
            ClaudeHint.Text = $"«{request.Label}»: apro Claude…";
            bool pasted = await ClaudeBridge.SendAsync(note, request, _settings.OpenClaudeDesktop, _settings.AutoPaste);
            ClaudeHint.Text = pasted
                ? $"«{request.Label}»: in Claude c'è la trascrizione come allegato e il comando nel messaggio: controlla e premi Invio. " +
                  "Quando risponde, copia la risposta e premi «Incolla risposta» (o il riquadro qui sopra)."
                : $"«{request.Label}»: richiesta e trascrizione sono negli Appunti. In Claude premi Ctrl+V e Invio. " +
                  "Quando risponde, copia la risposta e premi «Incolla risposta» (o il riquadro qui sopra).";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a preparare la richiesta:\n" + ex.Message, "MeNoter");
        }
    }

    private void Reveal_Click(object sender, RoutedEventArgs e)
    {
        if (_lastClaudeFile != null && File.Exists(_lastClaudeFile)) ClaudeBridge.Reveal(_lastClaudeFile);
    }

    // ---------------- Incolla la risposta di Claude ----------------

    /// <summary>
    /// "Incolla risposta…": un menu che mostra cosa c'è negli Appunti (testo o immagine) e chiede a quale
    /// richiesta appartiene. In cima le richieste ancora in attesa, poi tutti i tipi.
    /// </summary>
    private void Paste_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        var note = _current;
        var menu = new ContextMenu { PlacementTarget = PasteButton, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
        var preview = ClipboardPreview();
        menu.Items.Add(new MenuItem
        {
            Header = preview == null ? "Negli Appunti non c'è niente: in Claude premi «Copia» sotto la risposta" : "Negli Appunti: " + preview,
            IsEnabled = false,
        });
        if (preview != null)
        {
            if (note.Meta.Pending.Count > 0)
            {
                menu.Items.Add(new Separator());
                menu.Items.Add(new MenuItem { Header = "In attesa da Claude", IsEnabled = false, FontWeight = FontWeights.SemiBold });
                foreach (var p in note.Meta.Pending.OrderByDescending(p => p.SentAt))
                    menu.Items.Add(PasteItem(note, $"⏳  {p.Label}   (chiesta alle {p.SentAt:HH:mm})", p.Label, bold: true));
            }
            menu.Items.Add(new Separator());
            menu.Items.Add(new MenuItem { Header = "Salva come", IsEnabled = false, FontWeight = FontWeights.SemiBold });
            foreach (var r in ClaudeRequest.All) menu.Items.Add(PasteItem(note, $"{r.Icon}  {r.Label}", r.Label));
            menu.Items.Add(PasteItem(note, "💬  Domanda", "Domanda"));
            menu.Items.Add(PasteItem(note, "📄  Altro", "Risposta"));
        }
        menu.IsOpen = true;
    }

    private MenuItem PasteItem(Note note, string header, string label, bool bold = false)
    {
        var item = new MenuItem { Header = header, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal };
        item.Click += (_, _) => PasteAs(note, label);
        return item;
    }

    /// <summary>Cosa c'è negli Appunti, in breve (null se niente di utile).</summary>
    private static string? ClipboardPreview()
    {
        try
        {
            if (Clipboard.ContainsFileDropList())
            {
                var images = Clipboard.GetFileDropList().Cast<string>().Where(Note.IsImage).ToList();
                if (images.Count > 0) return $"🖼  {images.Count} immagine/i ({Path.GetFileName(images[0])})";
            }
            if (Clipboard.GetData("PNG") is MemoryStream || Clipboard.ContainsImage()) return "🖼  un'immagine";
            if (Clipboard.ContainsText())
            {
                var t = Clipboard.GetText().Trim().Replace("\r", " ").Replace("\n", " ");
                if (t.Length == 0) return null;
                return "«" + (t.Length > 70 ? t[..70] + "…" : t) + "»";
            }
        }
        catch
        {
            // Appunti occupati da un'altra app: si riprova al prossimo clic.
        }
        return null;
    }

    private void PasteAs(Note note, string label)
    {
        string? saved;
        try
        {
            saved = PasteImage(note, label) ?? PasteText(note, label);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Non riesco a leggere gli Appunti:\n" + ex.Message, "MeNoter");
            return;
        }
        if (saved == null)
        {
            MessageBox.Show(this,
                "Negli Appunti non c'è niente da incollare. In Claude usa «Copia» sotto la risposta, " +
                "oppure tasto destro sull'immagine → «Copia immagine».", "MeNoter");
            return;
        }
        // Risposta arrivata: la richiesta non è più in attesa.
        var pending = note.Meta.Pending.FirstOrDefault(p => p.Label == label);
        if (pending != null) note.Meta.Pending.Remove(pending);
        note.Save();
        if (_current == note) ShowNote(note, selectFile: saved);
        ClaudeHint.Text = Note.IsImage(saved) ? $"Immagine salvata in «{label}»." : $"Risposta salvata come «{label}».";
        RequestSync();
    }

    /// <summary>Riquadri delle richieste in attesa: clic = incolla lì, tasto destro = togli.</summary>
    private void UpdatePending(Note note)
    {
        PendingPanel.Children.Clear();
        foreach (var p in note.Meta.Pending.OrderBy(p => p.SentAt))
        {
            var b = new Button
            {
                Content = $"⏳  {p.Label} · {p.SentAt:HH:mm}",
                ToolTip = "Clic: incolla qui quello che hai copiato da Claude. Tasto destro: togli dalla lista.",
                Background = (System.Windows.Media.Brush)FindResource("VioletSoft"),
            };
            var label = p.Label;
            b.Click += (_, _) =>
            {
                var preview = ClipboardPreview();
                if (preview == null)
                {
                    MessageBox.Show(this, "Negli Appunti non c'è niente: in Claude premi «Copia» sotto la risposta.", "MeNoter");
                    return;
                }
                if (MessageBox.Show(this, $"Salvare come «{label}»?\n\n{preview}", "MeNoter", MessageBoxButton.YesNo) == MessageBoxResult.Yes)
                    PasteAs(note, label);
            };
            var remove = new MenuItem { Header = "Togli dalla lista (non serve più)" };
            remove.Click += (_, _) =>
            {
                note.Meta.Pending.RemoveAll(x => x.Label == label);
                note.Save();
                UpdatePending(note);
            };
            b.ContextMenu = new ContextMenu();
            b.ContextMenu.Items.Add(remove);
            PendingPanel.Children.Add(b);
        }
        PendingBox.Visibility = note.Meta.Pending.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---------------- Spazio per le risposte ----------------

    /// <summary>
    /// La nota occupa almeno tutta l'altezza disponibile, e le schede delle risposte hanno sempre almeno
    /// 460 pixel: se la finestra è piccola, si scorre.
    /// </summary>
    private void DetailLayout_Changed(object sender, SizeChangedEventArgs e)
    {
        var min = DetailHeader.ActualHeight + 460;
        var target = Math.Max(DetailPanel.ViewportHeight, min);
        if (Math.Abs(DetailGrid.Height - target) > 1 || double.IsNaN(DetailGrid.Height)) DetailGrid.Height = target;
    }

    private void ClaudeToggle_Click(object sender, RoutedEventArgs e)
    {
        _settings.ClaudeCollapsed = !_settings.ClaudeCollapsed;
        _settings.Save();
        ApplyClaudeCollapsed();
    }

    private void ApplyClaudeCollapsed()
    {
        ClaudeBody.Visibility = _settings.ClaudeCollapsed ? Visibility.Collapsed : Visibility.Visible;
        ClaudeToggle.Content = _settings.ClaudeCollapsed ? "Mostra comandi ▾" : "Nascondi comandi ▴";
    }

    private string? PasteText(Note note, string label)
    {
        if (!Clipboard.ContainsText()) return null;
        var text = Clipboard.GetText().Trim();
        if (text.Length == 0) return null;
        var path = note.SaveResponse(label, text);
        // Il riassunto comincia con "# Titolo": se la nota ha ancora il nome automatico, lo prende.
        var first = text.Split('\n').FirstOrDefault()?.Trim() ?? "";
        if (label == "Riassunto" && first.StartsWith("# ") && note.Meta.Title.StartsWith("Riunione "))
        {
            note.Meta.Title = first[2..].Trim();
            note.Save();
            RefreshListItem(note);
        }
        return path;
    }

    /// <summary>Immagine copiata (dal browser o dall'app Claude) o file immagine copiato in Esplora file.</summary>
    private static string? PasteImage(Note note, string label)
    {
        if (Clipboard.ContainsFileDropList())
        {
            var files = Clipboard.GetFileDropList().Cast<string>().Where(Note.IsImage).ToList();
            string? last = null;
            foreach (var f in files)
            {
                last = note.NewResponsePath(label, Path.GetExtension(f).ToLowerInvariant());
                File.Copy(f, last, true);
            }
            if (last != null) return last;
        }
        // Chrome ed Electron mettono anche il formato "PNG", più fedele (trasparenze) di quello standard di Windows.
        if (Clipboard.GetData("PNG") is MemoryStream png)
        {
            var path = note.NewResponsePath(label, ".png");
            File.WriteAllBytes(path, png.ToArray());
            return path;
        }
        if (Clipboard.ContainsImage() && Clipboard.GetImage() is { } bitmap)
        {
            var path = note.NewResponsePath(label, ".png");
            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using var fs = File.Create(path);
            encoder.Save(fs);
            return path;
        }
        return null;
    }

    // ---------------- Elenco e dettaglio ----------------

    private void LoadNotes(Note? select = null)
    {
        var keep = select?.Folder ?? _current?.Folder;
        _allNotes = NoteStore.List(_settings.ResolveNotesFolder());
        foreach (var n in _allNotes) RecoverInterrupted(n);
        ApplyFilter();
        var match = _allNotes.FirstOrDefault(n => n.Folder == keep);
        if (match != null) NotesList.SelectedItem = match;
    }

    /// <summary>
    /// Registrazione rimasta a metà (PC spento, app chiusa di colpo): i file sono leggibili fino all'ultimo
    /// salvataggio (ogni 10 secondi), quindi la nota diventa "da trascrivere".
    /// </summary>
    private void RecoverInterrupted(Note n)
    {
        if (n.Meta.Status != "registrazione" || n.Folder == _recordingNote?.Folder) return;
        try
        {
            using var reader = new NAudio.Wave.WaveFileReader(File.Exists(n.SysPath) ? n.SysPath : n.MicPath);
            n.Meta.DurationSec = reader.TotalTime.TotalSeconds;
        }
        catch
        {
            // File illeggibile: si lascia la durata a zero.
        }
        n.Meta.Status = "da trascrivere (registrazione interrotta)";
        n.Save();
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
        if (_busyNote == note) ShowProgress(note, _busyText, _busyValue);
        else HideProgress();
        UpdatePending(note);
        TranscribeButton.IsEnabled = note.Meta.Status != "registrazione";

        ContentTabs.Items.Clear();
        var search = SearchBox.Text.Trim();
        ContentTabs.Items.Add(new TabItem
        {
            Header = "Trascrizione",
            Content = note.HasTranscript
                ? EditableView(note.TranscriptPath, search,
                    "Puoi correggere la trascrizione (nomi, parole sbagliate): si salva da sola. " +
                    (File.Exists(note.PreviousTranscriptPath) ? "La versione precedente è in trascrizione-precedente.txt." : ""))
                : TextView(note.Meta.Status == "registrazione" ? "Registrazione in corso…" : "La trascrizione comparirà qui.", search),
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

    private const double BaseFontSize = 14.5;

    private TextBox TextView(string text, string search = "")
    {
        var box = new TextBox
        {
            Text = text,
            IsReadOnly = true,
            TextWrapping = TextWrapping.Wrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(4, 10, 4, 4),
            FontSize = BaseFontSize,
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
        var image = Note.IsImage(file);
        var panel = new DockPanel();
        var bar = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
        DockPanel.SetDock(bar, Dock.Top);
        var copy = new Button { Content = image ? "Copia immagine" : "Copia" };
        copy.Click += (_, _) =>
        {
            if (image) Clipboard.SetImage(LoadImage(file));
            else Clipboard.SetText(File.ReadAllText(file));
        };
        var open = new Button { Content = "Apri il file" };
        open.Click += (_, _) => Process.Start(new ProcessStartInfo(file) { UseShellExecute = true });
        var delete = new Button { Content = "Elimina" };
        delete.Click += (_, _) =>
        {
            if (MessageBox.Show(this, image ? "Eliminare questa immagine?" : "Eliminare questa risposta di Claude?", "MeNoter",
                    MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            File.Delete(file);
            ShowNote(note);
        };
        bar.Children.Add(copy);
        bar.Children.Add(open);
        bar.Children.Add(delete);
        panel.Children.Add(bar);
        if (image)
        {
            var source = LoadImage(file);
            var img = new Image { Source = source, Stretch = System.Windows.Media.Stretch.Uniform };
            var scroller = new ScrollViewer
            {
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                Content = img,
                Margin = new Thickness(0, 6, 0, 0),
            };
            // Zoom dell'immagine: "Adatta" la fa stare nella finestra, − / + e Ctrl + rotellina la ingrandiscono.
            double? scale = null; // null = adattata alla finestra
            TextBlock? zoomText = null;
            void Apply()
            {
                if (scale == null)
                {
                    img.Width = double.NaN;
                    img.MaxWidth = Math.Max(100, scroller.ViewportWidth > 0 ? scroller.ViewportWidth : source.PixelWidth);
                    zoomText!.Text = "Adatta";
                }
                else
                {
                    img.MaxWidth = double.PositiveInfinity;
                    img.Width = source.PixelWidth * scale.Value;
                    zoomText!.Text = $"{scale.Value * 100:0}%";
                }
            }
            double Current() => scale ?? (img.ActualWidth > 0 ? img.ActualWidth / source.PixelWidth : 1.0);
            void Zoom(double factor)
            {
                scale = factor <= 0 ? null : Math.Clamp(Current() * factor, 0.1, 6.0);
                Apply();
            }
            var zoomBar = ZoomBar(out var zt, () => Zoom(1 / 1.2), () => Zoom(1.2), () => Zoom(0));
            zoomText = zt;
            bar.Children.Add(zoomBar);
            scroller.PreviewMouseWheel += (_, e) =>
            {
                if ((Keyboard.Modifiers & ModifierKeys.Control) == 0) return;
                Zoom(e.Delta > 0 ? 1.1 : 1 / 1.1);
                e.Handled = true;
            };
            scroller.SizeChanged += (_, _) => { if (scale == null) Apply(); };
            scroller.Loaded += (_, _) => Apply();
            panel.Children.Add(scroller);
        }
        else
        {
            panel.Children.Add(EditableView(file, "", "Puoi modificare il testo: si salva da solo."));
        }
        return panel;
    }

    /// <summary>Piccola barra − 100% + per lo zoom.</summary>
    private static StackPanel ZoomBar(out TextBlock label, Action minus, Action plus, Action reset)
    {
        var panel = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        var m = new Button { Content = "−", Padding = new Thickness(12, 4, 12, 4), Margin = new Thickness(0, 0, 4, 8), ToolTip = "Rimpicciolisci (Ctrl + rotellina)" };
        var p = new Button { Content = "+", Padding = new Thickness(12, 4, 12, 4), Margin = new Thickness(4, 0, 0, 8), ToolTip = "Ingrandisci (Ctrl + rotellina)" };
        var t = new TextBlock { MinWidth = 52, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 8), Cursor = Cursors.Hand, ToolTip = "Torna alla dimensione normale" };
        m.Click += (_, _) => minus();
        p.Click += (_, _) => plus();
        t.MouseLeftButtonUp += (_, _) => reset();
        panel.Children.Add(m);
        panel.Children.Add(t);
        panel.Children.Add(p);
        label = t;
        return panel;
    }

    /// <summary>Immagine caricata in memoria (il file resta libero: si può eliminare o sostituire).</summary>
    private static BitmapImage LoadImage(string path)
    {
        var img = new BitmapImage();
        img.BeginInit();
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.UriSource = new Uri(path);
        img.EndInit();
        img.Freeze();
        return img;
    }

    /// <summary>Testo modificabile che si salva da solo nel suo file (1 s dopo l'ultima modifica e quando si esce).</summary>
    private UIElement EditableView(string path, string search, string hint)
    {
        var box = TextView(File.ReadAllText(path), search);
        box.FontSize = BaseFontSize * _settings.TextZoom;
        box.IsReadOnly = false;
        box.AcceptsReturn = true;
        box.AcceptsTab = true;
        var status = new TextBlock { Style = (Style)FindResource("Hint"), Text = hint, Margin = new Thickness(0, 6, 0, 0) };
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        string saved = box.Text;
        void Save()
        {
            timer.Stop();
            if (box.Text == saved) return;
            try
            {
                File.WriteAllText(path, box.Text);
                saved = box.Text;
                status.Text = "Modifiche salvate alle " + DateTime.Now.ToString("HH:mm:ss");
                RequestSync();
            }
            catch (Exception ex)
            {
                status.Text = "Non riesco a salvare: " + ex.Message;
            }
        }
        timer.Tick += (_, _) => Save();
        box.TextChanged += (_, _) =>
        {
            timer.Stop();
            timer.Start();
        };
        box.LostFocus += (_, _) => Save();
        box.Unloaded += (_, _) => Save();
        // Zoom del testo: pulsanti − / + e Ctrl + rotellina del mouse. Resta uguale per tutte le note.
        TextBlock zoomText = new();
        void Zoom(double factor)
        {
            _settings.TextZoom = factor <= 0 ? 1.0 : Math.Clamp(_settings.TextZoom * factor, 0.6, 3.0);
            box.FontSize = BaseFontSize * _settings.TextZoom;
            zoomText.Text = $"{_settings.TextZoom * 100:0}%";
            _settings.Save();
        }
        var top = new DockPanel { Margin = new Thickness(0, 6, 0, 0) };
        var zoomBar = ZoomBar(out zoomText, () => Zoom(1 / 1.15), () => Zoom(1.15), () => Zoom(0));
        zoomText.Text = $"{_settings.TextZoom * 100:0}%";
        DockPanel.SetDock(zoomBar, Dock.Right);
        top.Children.Add(zoomBar);
        status.Margin = new Thickness(0);
        status.VerticalAlignment = VerticalAlignment.Center;
        top.Children.Add(status);
        box.PreviewMouseWheel += (_, e) =>
        {
            if ((Keyboard.Modifiers & ModifierKeys.Control) == 0) return;
            Zoom(e.Delta > 0 ? 1.1 : 1 / 1.1);
            e.Handled = true;
        };
        var panel = new DockPanel();
        DockPanel.SetDock(top, Dock.Top);
        panel.Children.Add(top);
        panel.Children.Add(box);
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

    // ---------------- Sincronizzazione ----------------

    /// <summary>Sincronizza qualche secondo dopo l'ultima modifica (più modifiche vicine = una sola copia).</summary>
    private void RequestSync()
    {
        if (_settings.SyncEnabled.Count == 0) return;
        _syncTimer.Stop();
        _syncTimer.Start();
    }

    private void UpdateSyncButton()
    {
        var targets = CloudSync.Targets(_settings);
        SyncButton.Visibility = targets.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (targets.Count > 0 && !_syncing) SyncText.Text = "☁  " + string.Join(", ", targets.Select(t => t.Name));
    }

    private async void SyncNow_Click(object sender, RoutedEventArgs e) => await SyncAsync();

    private async Task SyncAsync()
    {
        var targets = CloudSync.Targets(_settings);
        if (targets.Count == 0 || _syncing) return;
        _syncing = true;
        SyncText.Text = "☁  Sincronizzo…";
        var root = _settings.ResolveNotesFolder();
        var skip = _recordingNote?.Folder;
        try
        {
            var result = await Task.Run(() => CloudSync.Run(root, targets, skip, CancellationToken.None));
            var names = string.Join(", ", targets.Select(t => t.Name));
            SyncText.Text = result.Errors.Count == 0
                ? $"☁  {names} · aggiornato alle {DateTime.Now:HH:mm}"
                : "⚠  Sincronizzazione incompleta";
            SyncButton.ToolTip = result.Errors.Count == 0
                ? $"{result.Copied} file copiati nell'ultima sincronizzazione. Clic per sincronizzare adesso."
                : string.Join("\n", result.Errors);
        }
        catch (Exception ex)
        {
            SyncText.Text = "⚠  Sincronizzazione non riuscita";
            SyncButton.ToolTip = ex.Message;
        }
        finally
        {
            _syncing = false;
        }
    }

    private void Settings_Click(object sender, RoutedEventArgs e)
    {
        var w = new SettingsWindow(_settings) { Owner = this };
        if (w.ShowDialog() == true)
        {
            LoadNotes();
            UpdateSyncButton();
            RequestSync();
        }
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
