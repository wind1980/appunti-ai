# Appunti AI – versione 0.3

App Android personale: registra o importa un audio, lo trascrive **sul telefono** con whisper.cpp e manda la trascrizione all'app **Claude** (piano Pro) per riassunti, traduzioni, mappe e domande. Nessun server, nessuna API, l'audio non esce dal telefono.

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
