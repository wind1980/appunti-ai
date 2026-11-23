# MeNoter – regole di lavoro

MeNoter è l'app Android personale di Edoardo per registrare, trascrivere (whisper.cpp sul telefono) e
lavorare le note con Claude. Tutta la parte AI passa dall'app Claude con il suo account **Claude Pro**:
niente API, niente server, niente pacchetti di minuti.

Il progetto viene modificato da **più chat e da più PC**. L'unico punto comune è questo repository
(`wind1980/appunti-ai`). Per non creare versioni parallele valgono queste regole.

## Prima di iniziare
1. Scarica sempre l'ultima versione di `main` (`git pull` o un clone nuovo) prima di toccare qualsiasi file.
2. Leggi il README e l'ultimo commit di `main` per sapere a che versione è arrivata l'app.
3. Controlla se esistono rami o pull request aperti (`git ls-remote origin`): se ce n'è uno,
   va unito a `main` o chiuso prima di lavorare sugli stessi file (chiedi a Edoardo).

## Durante il lavoro
- **Esiste una sola versione dell'app: quella su `main`.** Ogni sviluppo, da qualsiasi chat o PC,
  parte dall'ultimo `main` e torna su `main`. Niente versioni parallele, niente anteprime separate.
- Per verificare che compili prima di pubblicare si può usare un ramo temporaneo
  (avvio manuale del workflow "Compila APK" su quel ramo: compila ma non pubblica). Appena la
  build è verde il ramo si unisce a `main` (push diretto o pull request) e si elimina.
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
