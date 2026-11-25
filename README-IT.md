# WristMirror – versione italiana

Traduzione italiana non ufficiale di [WristMirror](https://github.com/Escosis/WristMirror) (v1.2.0) di Escosis.

- Il branch `main` contiene il sorgente tradotto; ogni push compila l'APK con GitHub Actions.
- L'ultimo APK compilato si trova nel branch `apk`.
- L'APK è firmato con la chiave di debug: prima di installarlo va disinstallata la versione originale.

## Aggiornamenti automatici

Ogni giorno un'attività pianificata controlla se è uscita una nuova versione dell'originale; se sì, applica il dizionario `traduzione/it.json`, traduce le stringhe nuove, aggiorna `main` e fa compilare l'APK. La procedura è in `traduzione/AGGIORNAMENTO.md`.
