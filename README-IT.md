# WristMirror – versione multilingua

> **WristMirror è opera di [Escosis](https://github.com/Escosis)**: tutto il merito dell'app (protocollo, funzioni, interfaccia) è suo.
> Progetto originale: https://github.com/Escosis/WristMirror (licenza Apache-2.0, vedi `LICENSE` e `THIRD_PARTY_NOTICES.md`).
> Questo repository contiene solo una **traduzione non ufficiale**, distribuita da Kkaoss969 con Gateway Assistant; non è affiliata con l'autore.
> Problemi della traduzione: segnalateli qui, non all'autore. Se vi piace l'app, mettete una stella al progetto originale.

L'app segue la lingua del telefono: italiano, inglese, spagnolo, francese, portoghese (Portogallo e Brasile), bulgaro, turco e tedesco; sui telefoni in cinese resta il testo originale; nelle altre lingue l'inglese.

Differenze rispetto all'originale (modifiche nostre, come richiesto dalla licenza Apache-2.0):
- pacchetto `it.gemini.wristmirror`, quindi si installa accanto all'originale;
- controllo aggiornamenti dell'app su questo repository (il link «Repository del progetto» in Informazioni resta quello dell'autore);
- testi tradotti, con il nome dell'autore nella schermata Informazioni e nell'avviso iniziale;
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
