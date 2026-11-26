# Aggiornare la traduzione a una nuova versione di WristMirror

Originale: https://github.com/Escosis/WristMirror — versione tradotta attuale in `traduzione/VERSIONE_ORIGINALE`.

## Procedura

1. **Controlla se c'è una versione nuova**
   ```sh
   git ls-remote --tags https://github.com/Escosis/WristMirror
   ```
   Prendi il tag `vX.Y.Z` più alto (ignora i tag con suffisso, es. `-beta`, e le righe `^{}`).
   Se è uguale a `traduzione/VERSIONE_ORIGINALE`, non c'è niente da fare: fermati.

2. **Scarica l'originale**
   ```sh
   rm -rf /tmp/up && git clone -q --depth 1 -b vX.Y.Z https://github.com/Escosis/WristMirror /tmp/up && rm -rf /tmp/up/.git
   ```

3. **Applica il dizionario**
   ```sh
   python3 traduzione/traduci.py /tmp/up
   ```
   - codice 0: tutto tradotto.
   - codice 2: guarda `traduzione/mancanti.json` e aggiungi le traduzioni in `traduzione/lingue.json`, **in tutte
     le 10 lingue** (`it`, `en`, `es`, `fr`, `pt` = Portogallo, `br` = Brasile, `bg`, `tr`, `de`, `ru`):
     `stringhe` per i letterali, `righe_testo` per le righe delle stringhe multilinea, `risorse` per gli XML.
     Le righe di diagnostica tecnica (es. `campo=07 codice=`) possono restare in inglese nelle lingue diverse dall'italiano.
     Poi riscarica l'originale (passo 2, lo script modifica in place) e rilancia finché esce con 0.
   - codice 3: un controllo è fallito (segnaposto, prefissi o un adattamento non più applicabile); correggi
     `lingue.json` (o `ADATTA` in `traduci.py` se l'originale ha cambiato quei punti) e rilancia.

   Regole per le nuove traduzioni:
   - mantieni identici i segnaposto (`$code`, `${...}`, `\n`) e non introdurre `"` o `$` nel testo;
   - i letterali che il codice confronta con `startsWith`/`contains` devono restare prefissi/sottostringhe
     delle frasi più lunghe che li contengono (lo script lo verifica);
   - `华为` e `荣耀` non si traducono (filtrano i nomi Bluetooth): sono in `invariate`;
   - se una stringa nuova sembra usata come chiave/identificativo (confronti, `when`, salvataggi), controlla il codice prima di tradurla.

   Glossario italiano (per le altre lingue segui le scelte già presenti nel dizionario): 表盘 = quadrante · 手表 = watch · 配对 = abbinamento · 解除配对 = annullamento abbinamento / Annulla abbinamento ·
   协议绑定 = associazione del protocollo · 重新协议绑定 = Riassocia protocollo · 启用 = Attiva · 替换 = Sostituisci ·
   直装/直接安装 = installazione diretta · 全能模式 = modalità completa · 省电模式 = modalità risparmio energetico ·
   载荷 = payload · 资料 = dati · 运动健康 = Huawei Health · 腕镜 = WristMirror · 腕连 = Wanlian · 华为 ID = ID Huawei ·
   刷新 = Aggiorna · 日志 = log · 发布 = release. Tono: imperativo con il tu, frasi brevi.

4. **Sostituisci il sorgente nel repo** (tieni `.git`, `.github`, `traduzione/`, `README-IT.md`)
   ```sh
   find . -mindepth 1 -maxdepth 1 ! -name .git ! -name .github ! -name traduzione ! -name README-IT.md -exec rm -rf {} +
   rm -rf /tmp/up/.github /tmp/up/traduzione /tmp/up/README-IT.md
   cp -a /tmp/up/. ./
   echo X.Y.Z > traduzione/VERSIONE_ORIGINALE
   echo 1 > traduzione/REVISIONE
   rm -f traduzione/mancanti.json
   ```
   Se l'originale ha cambiato `res/values` con nuove stringhe o file, verifica che siano tradotti.

5. **Commit e push su `main`** con messaggio `Aggiornato a WristMirror vX.Y.Z`.
   (Per ripubblicare la stessa versione con traduzioni corrette: alza `traduzione/REVISIONE` di 1.)

6. **Compilazione**: GitHub Actions compila a ogni push su `main`, mette l'APK nel branch `apk` e, se i segreti
   di firma ci sono, pubblica la release `vX.Y.Z` (`WristMirror-it.apk`, `versione.json`, `WristMirror-<versione>-it<rev>-<sha>.apk`),
   da cui lo Store di Gateway scarica l'app. Attendi l'esito con `gh run watch`; se fallisce leggi il log
   (`gh run view <id> --log-failed`), correggi e ripeti.

7. **Consegna**: scarica l'APK dal branch `apk`
   (`git clone -q --depth 1 -b apk https://github.com/Kkaoss969/wristmirror-it /tmp/apk`) e invialo in chat,
   con un riepilogo in italiano: versione nuova, quante stringhe nuove sono state tradotte, eventuali dubbi.
