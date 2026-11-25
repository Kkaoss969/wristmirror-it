# WristMirror – versione multilingua

Traduzione non ufficiale di [WristMirror](https://github.com/Escosis/WristMirror) di Escosis (licenza Apache-2.0, vedi `LICENSE` e `THIRD_PARTY_NOTICES.md`).

L'app segue la lingua del telefono: italiano, inglese, spagnolo, francese, portoghese (Portogallo e Brasile), bulgaro, turco e tedesco; sui telefoni in cinese resta il testo originale; nelle altre lingue l'inglese.

Differenze rispetto all'originale:
- pacchetto `it.gemini.wristmirror`, quindi si installa accanto all'originale;
- controllo aggiornamenti dell'app su questo repository;
- APK firmato con una chiave propria (segreti `WM_KEYSTORE` e `WM_PASSWORD` del repository).

## Dove si scarica

- Ultima versione: [Release](https://github.com/Kkaoss969/wristmirror-it/releases/latest) (`WristMirror-it.apk`).
- Dallo Store di Gateway Assistant (scheda «Telefono»).

## Come funziona la traduzione

- `traduzione/lingue.json`: dizionario (cinese → 9 lingue).
- `traduzione/traduci.py`: applica il dizionario al sorgente originale (`Tr.tr(...)` nel codice, `values-xx` per le risorse) e gli adattamenti.
- `traduzione/REVISIONE`: numero che si alza quando si ripubblica la stessa versione con traduzioni corrette.

## Aggiornamenti automatici

Ogni giorno un'attività pianificata controlla se è uscita una nuova versione dell'originale; se sì, applica il dizionario, traduce le stringhe nuove, aggiorna `main` e GitHub Actions compila e pubblica la release. La procedura è in `traduzione/AGGIORNAMENTO.md`.
