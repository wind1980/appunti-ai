# MeNoter – regole di lavoro

MeNoter è l'app Android personale di Edoardo per registrare, trascrivere (whisper.cpp sul telefono) e
lavorare le note con Claude. Tutta la parte AI passa dall'app Claude con il suo account **Claude Pro**:
niente API, niente server, niente pacchetti di minuti.

Il progetto viene modificato da **più chat e da più PC**. L'unico punto comune è questo repository
(`wind1980/appunti-ai`). Per non creare versioni parallele valgono queste regole.

## Prima di iniziare
1. Scarica sempre l'ultima versione di `main` (`git pull` o un clone nuovo) prima di toccare qualsiasi file.
2. Leggi il README e l'ultimo commit di `main` per sapere a che versione è arrivata l'app.
3. Controlla se esistono rami aperti (`git ls-remote origin`): se un ramo `anteprima`/di prova
   è ancora aperto, chiedi a Edoardo se va unito o chiuso prima di lavorare sugli stessi file.

## Durante il lavoro
- Lavora direttamente su `main` per le modifiche normali. Usa un ramo separato solo se Edoardo
  vuole provare qualcosa senza toccare la versione di tutti i giorni: il ramo pubblica una
  release "Anteprima" con link fisso
  (`https://github.com/wind1980/appunti-ai/releases/download/anteprima-NOMERAMO/AppuntiAI.apk`)
  e va unito a `main` o chiuso appena possibile.
- **Numeri di versione**: prima di assegnarne uno nuovo controlla `versionCode` e `versionName`
  in `app/build.gradle.kts` su `main` e usa il successivo. `versionCode` deve sempre crescere,
  altrimenti il telefono rifiuta l'aggiornamento.
- **Database (Room)**: prima di cambiare tabelle controlla la `version` attuale in
  `data/Database.kt` su `main` e aggiungi una migrazione dalla versione precedente.
  Mai riusare un numero di versione già usato: le note di Edoardo devono sopravvivere a ogni aggiornamento.
- Qui non si può compilare (gli strumenti Android sono bloccati): la verifica è la build di
  GitHub Actions. Dopo ogni push controlla che sia verde.

## Alla fine
- Fai subito commit e push su GitHub: niente modifiche lasciate solo sul PC o in una chat.
- Aggiorna il README con le novità della versione.
- Ogni push su `main` pubblica una nuova release: è quella che Edoardo scarica dal link solito
  (`https://github.com/wind1980/appunti-ai/releases/latest`). Per modifiche che non cambiano
  l'app (README, questo file) aggiungi `[skip ci]` al messaggio di commit.
- Scrivi le risposte e il testo dell'app in italiano.
