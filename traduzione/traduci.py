#!/usr/bin/env python3
"""Rende multilingua un sorgente WristMirror originale (cinese), in place.

Uso:
  python3 traduzione/traduci.py <cartella_sorgente_originale> [--dizionario traduzione/lingue.json]

Cosa fa:
  - ogni letterale "..." con caratteri cinesi nei file .kt/.java (commenti esclusi) diventa
    Tr.tr("<cinese>", "<it>", "<en>", ...): la lingua si sceglie a runtime da quella del telefono
    (cinese, italiano, inglese, spagnolo, francese, portoghese PT/BR, bulgaro, turco, tedesco, russo; altrimenti inglese);
  - le stringhe multilinea \"\"\"...\"\"\" con cinese (es. Introduction.kt) diventano Tr.tr(\"\"\"...\"\"\", ...);
  - crea Tr.kt e aggiunge l'import dove serve;
  - res/values: inglese come predefinito, cinese in values-zh, le altre lingue in values-<lingua>;
  - applica gli adattamenti di ADATTA (pacchetto it.gemini.wristmirror, aggiornamenti da Kkaoss969/wristmirror-it);
  - controlla segnaposto ($x, ${...}, \\n) e i prefissi usati da startsWith/contains, lingua per lingua.
Scrive traduzione/mancanti.json con quello che manca nel dizionario.
Esce con 0 se tutto è tradotto e i controlli passano, 2 se manca qualcosa, 3 se un controllo fallisce.
"""
import glob, json, os, re, sys

CJK = re.compile('[一-鿿]')
LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
RAW = re.compile(r'"""(.*?)"""', re.S)
PH = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_]\w*|\\n')
CMP = re.compile(r'(?:startsWith|contains)\s*\(\s*"((?:[^"\\\n]|\\.)*)"')
PKG = 'io.github.escosis.hwtfaceswap'
# cartelle Android per ogni lingua del dizionario ('en' = predefinita)
CARTELLE = {'zh': 'values-zh', 'it': 'values-it', 'en': 'values', 'es': 'values-es', 'fr': 'values-fr',
            'pt': 'values-pt-rPT', 'br': 'values-pt', 'bg': 'values-bg', 'tr': 'values-tr', 'de': 'values-de',
            'ru': 'values-ru'}

# adattamenti per la versione distribuita da Kkaoss969 (pacchetto proprio, aggiornamenti dal nostro repository).
# Il link «Repository del progetto» in Informazioni resta quello dell'autore (Escosis): l'app è sua.
ADATTA = [
    ('app/build.gradle.kts', 'applicationId = "io.github.escosis.hwtfaceswap"', 'applicationId = "it.gemini.wristmirror"'),
    ('app/build.gradle.kts', 'versionCode = appVersionCode',
     'versionCode = appVersionCode * 100 + (providers.environmentVariable("WM_REVISIONE").orNull?.toIntOrNull() ?: 0)'),
    ('app/src/main/java/io/github/escosis/hwtfaceswap/UpdateChecker.kt', '"https://api.github.com/repos/Escosis/WristMirror/releases/latest"',
     '"https://api.github.com/repos/Kkaoss969/wristmirror-it/releases/latest"'),
    ('app/src/main/java/io/github/escosis/hwtfaceswap/UpdateReleaseParser.kt', '"/Escosis/WristMirror/releases/tag/$tagName"', '"/Kkaoss969/wristmirror-it/releases/tag/$tagName"'),
    ('app/src/main/java/io/github/escosis/hwtfaceswap/UpdateReleaseParser.kt', '"/Escosis/WristMirror/releases/download/$tagName/"', '"/Kkaoss969/wristmirror-it/releases/download/$tagName/"'),
]

TR_KT = '''package io.github.escosis.hwtfaceswap

import java.util.Locale

/** Testi tradotti (generato da traduzione/traduci.py del repository wristmirror-it): sceglie la lingua del telefono.
 * Ordine degli argomenti: %s. Lingue non previste: inglese. */
object Tr {
    @JvmStatic
    fun indice(): Int {
        val l = Locale.getDefault()
        return when (l.language) {
%s
            "pt" -> if (l.country.equals("PT", ignoreCase = true)) %d else %d
            else -> %d
        }
    }

    @JvmStatic
    fun tr(vararg testi: String): String = testi.getOrElse(indice()) { testi[0] }
}
'''


def kotlin_tr(lingue):
    righe = []
    for i, l in enumerate(lingue):
        if l in ('pt', 'br'):
            continue
        righe.append(f'            "{l}" -> {i}')
    return TR_KT % (', '.join(lingue), '\n'.join(righe), lingue.index('pt'), lingue.index('br'), lingue.index('en'))


def classify(text):
    """Per ogni riga: 'comment', 'raw' (dentro una stringa multilinea) o 'code'."""
    out, block, raw = [], False, False
    for line in text.split('\n'):
        st = line.lstrip()
        if raw:
            out.append(('raw', line))
            if '"""' in line:
                raw = False
            continue
        if block:
            out.append(('comment', line))
            if '*/' in line:
                block = False
            continue
        if st.startswith('/*') and '*/' not in st:
            block = True
            out.append(('comment', line))
            continue
        if st.startswith('*') or st.startswith('//'):
            out.append(('comment', line))
            continue
        out.append(('code', line))
        if line.count('"""') % 2 == 1:
            raw = True
    return out


def literals_in_code(line):
    res, pos = [], 0
    for m in LIT.finditer(line):
        pre = line[pos:m.start()]
        if '//' in pre or '/*' in pre:
            break
        if line[max(0, m.start() - 2):m.start()] == '""' or line[m.end():m.end() + 2] == '""':
            pos = m.end()
            continue  # parte di un """
        res.append((m.start(), m.end(), m.group(1)))
        pos = m.end()
    return res


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    root = args[0]
    dpath = args[args.index('--dizionario') + 1] if '--dizionario' in args else os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lingue.json')
    d = json.load(open(dpath, encoding='utf-8'))
    LINGUE = d['lingue']
    ALTRE = LINGUE[1:]
    T, RIGHE, RES, KEEP = d['stringhe'], d['righe_testo'], d['risorse'], set(d['invariate'])

    def chiamata(zh):
        return 'Tr.tr(' + ', '.join('"' + x + '"' for x in [zh] + [T[zh][l] for l in ALTRE]) + ')'

    java = os.path.join(root, 'app/src/main/java')
    files = sorted(glob.glob(java + '/**/*.kt', recursive=True) + glob.glob(java + '/**/*.java', recursive=True))
    missing, missing_raw, problems, compare_keys = {}, {}, [], set()
    replaced = 0

    for f in files:
        text = open(f, encoding='utf-8').read()
        rel = os.path.relpath(f, root)
        out, changed = [], False
        for n, (kind, line) in enumerate(classify(text), 1):
            if kind == 'raw':
                key = line.strip()
                if CJK.search(key) and key not in RIGHE:
                    missing_raw.setdefault(key, f'{rel}:{n}')
                out.append(line)
                continue
            if kind == 'comment':
                out.append(line)
                continue
            for m in CMP.finditer(line):
                if CJK.search(m.group(1)):
                    compare_keys.add(m.group(1))
            parts, pos = [], 0
            for s, e, content in literals_in_code(line):
                parts.append(line[pos:s])
                if CJK.search(content) and content not in KEEP:
                    if content in T and all(l in T[content] for l in ALTRE):
                        parts.append(chiamata(content))
                        replaced += 1
                        changed = True
                    else:
                        missing.setdefault(content, f'{rel}:{n}')
                        parts.append(line[s:e])
                else:
                    parts.append(line[s:e])
                pos = e
            parts.append(line[pos:])
            out.append(''.join(parts))
        text = '\n'.join(out)

        # stringhe multilinea
        def raw_sub(m):
            nonlocal replaced, changed
            body = m.group(1)
            if not CJK.search(body):
                return m.group(0)
            versioni = [body]
            for l in ALTRE:
                righe = []
                for r in body.split('\n'):
                    k = r.strip()
                    if CJK.search(k):
                        if k not in RIGHE or l not in RIGHE[k]:
                            return m.group(0)
                        # una traduzione può aggiungere righe (\n): ognuna con lo stesso rientro, per trimIndent
                        rientro = r[:len(r) - len(r.lstrip())]
                        r = '\n'.join(rientro + x for x in RIGHE[k][l].split('\n'))
                    righe.append(r)
                versioni.append('\n'.join(righe))
            replaced += 1
            changed = True
            return 'Tr.tr(' + ', '.join('"""' + v + '"""' for v in versioni) + ')'
        text = RAW.sub(raw_sub, text)

        if changed:
            pm = re.search(r'^package\s+([\w.]+)\s*;?\s*$', text, re.M)
            if pm and pm.group(1) != PKG:
                imp = f'import {PKG}.Tr' + (';' if f.endswith('.java') else '')
                if imp not in text:
                    text = text[:pm.end()] + '\n\n' + imp + text[pm.end():]
        open(f, 'w', encoding='utf-8').write(text)

    open(os.path.join(java, *PKG.split('.'), 'Tr.kt'), 'w', encoding='utf-8').write(kotlin_tr(LINGUE))

    # risorse XML
    missing_res = {}
    base = os.path.join(root, 'app/src/main/res')
    for xml in glob.glob(os.path.join(base, 'values', '*.xml')):
        name = os.path.basename(xml)
        s = open(xml, encoding='utf-8').read()
        voci = re.findall(r'<string name="([^"]+)">(.*?)</string>', s, flags=re.S)
        cinesi = [(k, v) for k, v in voci if CJK.search(v)]
        if not cinesi:
            continue
        ok = True
        for k, v in cinesi:
            for l in ALTRE:
                if l not in RES.get(name, {}).get(k, {}):
                    missing_res[f'{name}:{k}:{l}'] = v
                    ok = False
        if not ok:
            continue
        for l in LINGUE:
            cart = os.path.join(base, CARTELLE[l])
            if l == 'en':
                def sub(m):
                    if CJK.search(m.group(2)):
                        return f'<string name="{m.group(1)}">{RES[name][m.group(1)]["en"]}</string>'
                    return m.group(0)
                open(xml, 'w', encoding='utf-8').write(re.sub(r'<string name="([^"]+)">(.*?)</string>', sub, s, flags=re.S))
                continue
            os.makedirs(cart, exist_ok=True)
            righe = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
            for k, v in cinesi:
                righe.append(f'    <string name="{k}">{v if l == "zh" else RES[name][k][l]}</string>')
            righe.append('</resources>\n')
            open(os.path.join(cart, name), 'w', encoding='utf-8').write('\n'.join(righe))

    # adattamenti (se l'originale cambia questi punti, il controllo fallisce: vanno aggiornati qui)
    for rel, vecchio, nuovo in ADATTA:
        f = os.path.join(root, rel)
        t = open(f, encoding='utf-8').read() if os.path.exists(f) else ''
        if vecchio in t:
            open(f, 'w', encoding='utf-8').write(t.replace(vecchio, nuovo))
        elif nuovo not in t:
            problems.append(f'adattamento non applicato: {rel}: {vecchio}')

    # controlli sul dizionario
    for zh, tr in T.items():
        for l, v in tr.items():
            if sorted(PH.findall(zh)) != sorted(PH.findall(v)):
                problems.append(f'segnaposto diversi [{l}]: {zh!r} -> {v!r}')
            if '"' in v.replace('\\"', ''):
                problems.append(f'virgolette non escapate [{l}]: {v!r}')
    for k in compare_keys:
        if k in KEEP or k not in T:
            continue
        for zh in T:
            if zh != k and k in zh:
                for l in ALTRE:
                    a, b = T[k][l], T[zh][l]
                    if zh.startswith(k) and not b.startswith(a):
                        problems.append(f'prefisso rotto [{l}]: {a!r} deve iniziare {b[:60]!r}')
                    elif a not in b:
                        problems.append(f'contenuto rotto [{l}]: {a!r} deve stare in {b[:60]!r}')

    report = {'stringhe': missing, 'righe_testo': missing_raw, 'risorse': missing_res, 'problemi': problems}
    json.dump(report, open(os.path.join(os.path.dirname(dpath), 'mancanti.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(f'sostituzioni: {replaced}; mancanti: {len(missing)} stringhe, {len(missing_raw)} righe, {len(missing_res)} risorse; problemi: {len(problems)}')
    for p in problems:
        print('  !', p)
    if problems:
        sys.exit(3)
    if missing or missing_raw or missing_res:
        sys.exit(2)


if __name__ == '__main__':
    main()
