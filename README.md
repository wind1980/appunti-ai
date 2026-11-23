# MeNoter – versione 0.10

App Android personale: registra o importa un audio, lo trascrive **sul telefono** con whisper.cpp e manda la trascrizione all'app **Claude** (piano Pro) per riassunti, traduzioni, mappe e domande. Nessun server, nessuna API, l'audio non esce dal telefono.

## Novità della 0.10 (funzioni prese da HiNoter)

- **Interlocutori**: nuovo pulsante. Claude riscrive la trascrizione dividendola per chi parla (nomi veri se si capiscono, altrimenti "Persona 1, 2…"). Nella scheda Trascrizione si passa da "Con interlocutori" a "Originale"; il minuto di ogni intervento porta all'audio. Chi parla lo ricostruisce Claude dal contenuto, non dalla voce.
- **Modelli**: nuovo pulsante con Riassunto breve / normale / dettagliato e i **modelli personali** (Impostazioni › Modelli personali: nome + comando per Claude; tre esempi pronti: Verbale, Appunti di lezione, Punti chiave). Il livello del pulsante "Riassunto" si sceglie nelle Impostazioni.
- **Attività**: salvando la risposta "Azioni", le cose da fare diventano un elenco da spuntare in cima alla scheda "Altro", con responsabile e scadenza modificabili e il pulsante 📅 che apre il Calendario con il promemoria già compilato. Schermata **Attività** (icona in alto nella home) con le attività di tutte le note; in home un riquadro dice quante sono da fare e quante scadute.
- **Domande su più note**: icona 💬 in alto nella home → tocca le note da includere (o "Tutte"), scrivi la domanda; Claude riceve un unico file con riassunti e trascrizioni. La risposta va in una nuova nota "Domanda su N note".
- **Traduci in altre lingue**: il pulsante chiede la lingua (resta l'ultima scelta).
- **Esporta in PDF o Word**: menu ⋮ della nota → "Esporta in PDF o Word…" (anche TXT e Markdown), scegliendo cosa includere. Resta anche "Salva nota completa (OneDrive…)".
- **Ricerca nella trascrizione** con le parole evidenziate; aprendo una nota dalla ricerca della home, la trascrizione è già filtrata sulla parola cercata.

Database versione 6 (nuova tabella delle attività): le note esistenti restano.

Funzioni di HiNoter che restano fuori perché richiedono un server: partecipazione automatica alle riunioni del calendario, link YouTube, trascrizione in tempo reale nel cloud, Wear OS.

## Novità della 0.9

- Sezioni Riassunto / Mappa / Infografica con testo e immagine, nota completa su OneDrive, "Salva in MeNoter" in due passi (sezione e nota). (0.9.1)

## Novità della 0.8

- **Mappa e Infografica come schede doppie**: testo e immagine di Claude stanno nella stessa scheda della sezione Grafica, nell'ordine in cui arrivano (entro 6 ore si abbinano da soli).
- **Incolla anche le immagini**: un'immagine copiata in Claude (tieni premuto → Copia) si incolla in MeNoter come il testo.
- In ogni scheda: "Incolla immagine" / "Incolla il testo di Claude" se manca uno dei due, "Disegna la mappa dall'elenco", condivisione di immagine e testo insieme.

## Novità della 0.7

- **Mappa e Infografica come immagini PNG**: Claude crea un file immagine con l'esecuzione del codice (serve "Esecuzione del codice e creazione di file" attiva nelle impostazioni di Claude).
- **Nuova scheda "Grafica"** in ogni nota: mappe, infografiche, immagini e PDF.
- **PDF**: "Salva in MeNoter" e "Aggiungi immagine o PDF" accettano PDF, con anteprima e visualizzatore pagina per pagina.
- Immagini salvate in Immagini › MeNoter, PDF in Download › MeNoter.

## Novità della 0.6

- **Incolla come…**: nella scheda Riepilogo AI scegli il tipo (Riassunto, Azioni, Mappa concettuale, Infografica, Traduzione, Email, Altro testo).
- **Cambia tipo** di una risposta già salvata dal menu ⋮ (mostra come mappa, infografica o testo).
- **Mappe in formato mermaid** riconosciute e disegnate.
- **Infografica come artefatto** in Claude (si vede già disegnata); MeNoter riconosce anche i frammenti HTML.

## Novità della 0.5

- **Il comando è dentro il file .txt** (in cima, prima della trascrizione), perché l'app Claude scarta il testo del messaggio quando c'è un allegato. Il nome del file indica il comando, es. "Riassunto - Riunione.txt". (0.5.1)
- **Ogni pulsante manda una richiesta completa** (comando + trascrizione): Claude, riaperto da fuori, non torna sulla chat precedente, quindi ogni invio deve bastare da solo. (0.5.2: tolta la modalità "Chat già aperta".)
- **Risposte ripulite**: MeNoter toglie le frasi di apertura e chiusura di Claude; i prompt chiedono di rispondere solo con il contenuto.
- **Mappa concettuale disegnata**: Claude risponde con un elenco a livelli e MeNoter lo disegna come schema ad albero colorato.
- **Infografica**: nuovo pulsante; Claude crea una pagina HTML che MeNoter mostra a schermo intero.
- **Salva come immagine** mappe e infografiche (condividi o salva in Immagini › MeNoter).
- **Immagini**: "Salva in MeNoter" accetta anche immagini e screenshot.

## Novità della 0.4

- **Nuovo nome: MeNoter.** (Il codice interno resta `it.edoardo.appunti`, così gli aggiornamenti si installano sopra senza perdere le note.)
- **Ritorno da Claude in un tocco**: se copi una risposta in Claude e torni nella nota, MeNoter la propone da sola ("Salva come Riassunto").
- **"Salva in MeNoter"** dal menu Condividi propone per prima la nota da cui hai scritto a Claude.
- **Risposte etichettate** (Riassunto, Azioni, Mappa, Email…). Il primo riassunto dà alla nota un titolo vero al posto di "Registrazione …".
- **Domande nella chat già aperta**: dopo un invio, le domande successive copiano solo il testo della domanda e riaprono Claude, senza ricominciare una chat con tutta la trascrizione. Si può sempre scegliere "Nuova chat".

## Novità della 0.3

- **Nuova grafica** ispirata a HiNoter: blu `#4255FF` con sfumatura viola, schede arrotondate, tema chiaro e scuro.
- **Home** con il riquadro "Nuova nota" (registra, importa audio, istruzioni per i vocali di WhatsApp).
- **Registrazione a schermo intero** con timer grande, barre del volume e pulsanti tondi Pausa/Riprendi e Termina.
- **Nota con due schede**: "Riepilogo AI" (le risposte di Claude formattate, con titoli ed elenchi) e "Trascrizione".
- **Barra Claude fissa** in basso: Riassunto, Azioni, Mappa, Traduci, Email con un tocco, più il campo "Chiedi a Claude…" per una domanda libera.

## Novità della 0.2

- **Pausa e ripresa**: la registrazione si mette in pausa e si riprende quante volte vuoi (dall'app o dalla notifica). Va in trascrizione solo quando tocchi "Termina e trascrivi".
- **Salta silenzi e pause** con il rilevatore di voce Silero (1 MB, si scarica da solo): trascrizione molto più veloce.
- **Niente ritentativi** sui pezzi disturbati (si possono riattivare nelle Impostazioni).
- **Veloce subito, preciso dopo**: un modello "principale" veloce trascrive subito; dal menu della nota "Migliora" rifà la trascrizione con il modello preciso, anche solo quando il telefono è in carica. Il testo vecchio resta finché il nuovo non è pronto.
- **Recupero**: se l'app viene chiusa durante una registrazione, all'apertura successiva la registrazione diventa una nota.

## Cosa fa

- **Registra** dal microfono, anche a schermo spento, con pausa e ripresa.
- **Importa** audio e video: dal pulsante in alto o dal menu Condividi di altre app (WhatsApp, registratore, galleria) → "Trascrivi con Appunti".
- **Trascrive offline** a blocchi di 5 minuti, in background. Il testo compare man mano. Se Android interrompe il lavoro, riparte dall'ultimo blocco.
- **Manda a Claude** con quattro pulsanti: Riassumi, Traduci, Mappa mentale, Chiedi. Le trascrizioni lunghe partono come file `.txt` allegato.
- **Salva le risposte di Claude** nella nota: copiale in Claude e tocca "Incolla", oppure seleziona il testo → Condividi → "Salva in Appunti".
- Ricerca in titoli, trascrizioni e risposte. Riascolto con salto al paragrafo (tocca il minuto).
- Impostazioni: download o importazione del modello Whisper, lingua, core usati, nomi ricorrenti, prompt modificabili.

## Come compilarla

1. Installa [Android Studio](https://developer.android.com/studio) (versione Ladybug o più recente).
2. Estrai lo zip e in Android Studio scegli **File → Open** sulla cartella `AppuntiAI`.
3. Al primo avvio Android Studio scarica Gradle e le librerie. Se chiede di installare **SDK 35**, **NDK 27.2.12479018** o **CMake 3.22.1**, accetta. Altrimenti: *Tools → SDK Manager → SDK Tools* → spunta "NDK (Side by side)" e "CMake".
4. Sul telefono attiva *Opzioni sviluppatore → Debug USB*, collegalo al PC e premi **Run ▶**.
   In alternativa: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*, poi copia `app/build/outputs/apk/debug/app-debug.apk` sul telefono e installalo.

La prima compilazione dura qualche minuto, perché compila anche whisper.cpp in C++.

## Primo uso

1. Apri l'app → **Impostazioni** → scarica il modello **Small** (181 MB) e tocca "Usa questo".
2. Registra un minuto di prova e guarda quanto impiega la trascrizione.
3. Se è veloce, prova **Large v3 Turbo – italiano** (qualità migliore, più lento). Se è lento, usa **Base**.
4. In Claude crea un Progetto "Appunti" con le tue istruzioni fisse: i prompt dei pulsanti si sommano a quelle.

## Da verificare sul tuo telefono

- Che l'app Claude riceva sia il testo nel messaggio sia il file `.txt` allegato. Se il file non arriva, attiva o disattiva "Manda sempre la trascrizione come file" nelle Impostazioni.
- La velocità della trascrizione con i diversi modelli e numeri di core.

## Limiti noti

- Non distingue chi parla.
- Non registra l'audio delle chiamate di Meet, Teams o Zoom (Android lo impedisce): registra dal microfono con il vivavoce e avvisa i partecipanti.
- La libreria è compilata per ARMv8.2 (telefoni dal 2018 circa). Su telefoni più vecchi l'app si chiude all'avvio della trascrizione: in `app/src/main/cpp/CMakeLists.txt` sostituisci `armv8.2-a+fp16+dotprod` con `armv8-a`.
- Il codice Kotlin è stato scritto senza poterlo compilare (l'ambiente di sviluppo non aveva accesso agli strumenti Android). La parte nativa invece è stata compilata e provata. Se Android Studio segnala errori alla prima compilazione, copiali e li sistemiamo.

## Struttura

```
app/src/main/
  cpp/                      whisper.cpp 1.8.5 (MIT) + ponte JNI
  java/it/edoardo/appunti/
    audio/                  registrazione, decodifica audio → 16 kHz
    whisper/                modelli e chiamate a whisper.cpp
    work/                   trascrizione in background (WorkManager)
    share/                  condivisione verso Claude
    data/                   database (Room), impostazioni, prompt
    ui/                     schermate (Jetpack Compose)
```

whisper.cpp è di Georgi Gerganov e collaboratori, licenza MIT (vedi `app/src/main/cpp/whisper.cpp/LICENSE`).
