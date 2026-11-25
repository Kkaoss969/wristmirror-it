#!/usr/bin/env python3
"""Traduce in italiano un sorgente WristMirror originale (cinese), in place.

Uso:
  python3 traduzione/traduci.py <cartella_sorgente_originale> [--dizionario traduzione/it.json]

Cosa fa:
  - sostituisce i letterali "..." con caratteri cinesi nei file .kt/.java (commenti esclusi)
  - sostituisce le righe dentro le stringhe multilinea \"\"\"...\"\"\" (es. Introduction.kt)
  - traduce i valori in app/src/main/res/values/*.xml
  - controlla segnaposto ($x, ${...}, \\n) e i prefissi usati da startsWith/contains/==
Scrive traduzione/mancanti.json con quello che manca nel dizionario.
Esce con codice 0 se tutto è tradotto e i controlli passano, 2 se manca qualcosa, 3 se un controllo fallisce.
"""
import glob, json, os, re, sys

CJK = re.compile('[一-鿿]')
LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
PH = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_]\w*|\\n')
CMP = re.compile(r'(?:startsWith|contains)\s*\(?\s*"((?:[^"\\\n]|\\.)*)"')


def code_lines(text):
    """Restituisce, per ogni riga, (è_commento, dentro_stringa_multilinea)."""
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
    """Letterali nella riga prima di un eventuale commento: [(start, end, contenuto)]."""
    res, pos = [], 0
    for m in LIT.finditer(line):
        pre = line[pos:m.start()]
        if '//' in pre or '/*' in pre:
            break
        res.append((m.start(), m.end(), m.group(1)))
        pos = m.end()
    return res


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    root = args[0]
    dpath = args[args.index('--dizionario') + 1] if '--dizionario' in args else os.path.join(os.path.dirname(__file__), 'it.json')
    d = json.load(open(dpath, encoding='utf-8'))
    T, RAW, RES, KEEP = d['stringhe'], d['righe_testo'], d['risorse'], set(d['invariate'])

    files = sorted(glob.glob(os.path.join(root, 'app/src/main/java/**/*.kt'), recursive=True) +
                   glob.glob(os.path.join(root, 'app/src/main/java/**/*.java'), recursive=True))
    missing, missing_raw, problems = {}, {}, []
    compare_keys = set()
    replaced = 0

    for f in files:
        text = open(f, encoding='utf-8').read()
        rel = os.path.relpath(f, root)
        out = []
        for n, (kind, line) in enumerate(code_lines(text), 1):
            if kind == 'comment':
                out.append(line)
                continue
            if kind == 'raw':
                key = line.strip()
                if CJK.search(key):
                    if key in RAW:
                        line = line[:len(line) - len(line.lstrip())] + RAW[key]
                        replaced += 1
                    else:
                        missing_raw.setdefault(key, f'{rel}:{n}')
                out.append(line)
                continue
            for m in CMP.finditer(line):
                if CJK.search(m.group(1)):
                    compare_keys.add(m.group(1))
            parts, pos = [], 0
            for s, e, content in literals_in_code(line):
                parts.append(line[pos:s])
                if CJK.search(content) and content not in KEEP:
                    if content in T:
                        parts.append('"' + T[content] + '"')
                        replaced += 1
                    else:
                        missing.setdefault(content, f'{rel}:{n}')
                        parts.append(line[s:e])
                else:
                    parts.append(line[s:e])
                pos = e
            parts.append(line[pos:])
            out.append(''.join(parts))
        open(f, 'w', encoding='utf-8').write('\n'.join(out))

    # risorse XML
    missing_res = {}
    for xml in glob.glob(os.path.join(root, 'app/src/main/res/values/*.xml')):
        name = os.path.basename(xml)
        s = open(xml, encoding='utf-8').read()

        def sub(m):
            if not CJK.search(m.group(2)):
                return m.group(0)
            val = RES.get(name, {}).get(m.group(1))
            if val is None:
                missing_res[f'{name}:{m.group(1)}'] = m.group(2)
                return m.group(0)
            return f'<string name="{m.group(1)}">{val}</string>'
        s = re.sub(r'<string name="([^"]+)">(.*?)</string>', sub, s, flags=re.S)
        open(xml, 'w', encoding='utf-8').write(s)

    # controlli sul dizionario
    for zh, it in T.items():
        if sorted(PH.findall(zh)) != sorted(PH.findall(it)):
            problems.append(f'segnaposto diversi: {zh!r} -> {it!r}')
        if '"' in it.replace('\\"', ''):
            problems.append(f'virgolette non escapate: {it!r}')
    for k in compare_keys:
        if k in KEEP or k not in T:
            continue
        for zh, it in T.items():
            if zh != k and k in zh:
                if zh.startswith(k) and not it.startswith(T[k]):
                    problems.append(f'prefisso rotto: {T[k]!r} deve iniziare {it[:60]!r}')
                elif T[k] not in it:
                    problems.append(f'contenuto rotto: {T[k]!r} deve stare in {it[:60]!r}')

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
