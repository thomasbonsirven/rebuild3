#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import html
import json
import re
import shutil
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

SOURCE_URL = "https://rebuildgame.com/rebuild3_mod_sources_2024.zip"
DEFAULT_OUT = Path("rebuild3_fr_output")
BUNDLED_SOURCE = Path(__file__).resolve().parent / "source" / "rebuild3_mod_sources_2024.zip"
DEFAULT_CHUNK_KB = 180

META_KEYS = {
    "mod_type",
    "mod_name",
    "mod_description",
    "mod_language_name",
    "mod_locale_id",
}
SKIP_SUFFIXES = ("_picture", "_pictureColin", "_pictureColin2", "_icon")

KEY_RE = re.compile(r"^([A-Za-z0-9_'.-]+)\s*=")
SQUARE_RE = re.compile(r"\[[^\[\]]*\]")
CURLY_RE = re.compile(r"\{\d+\}")
PRINTF_RE = re.compile(r"%(?:\d+\$)?[-+#0 ]*\d*(?:\.\d+)?[a-zA-Z]")
ESCAPE_RE = re.compile(r"\\[nrt]")
URL_RE = re.compile(r"https?://[^\s]+")

def eprint(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)

def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 Rebuild3-FR-Android-Builder/1.0"},
    )
    print(f"[1/5] Téléchargement : {url}")
    with urllib.request.urlopen(req, timeout=60) as response, dest.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    print(f"      -> {dest} ({dest.stat().st_size / 1024:.1f} KiB)")

def safe_extract(archive: zipfile.ZipFile, dest: Path) -> None:
    root = dest.resolve()
    for member in archive.infolist():
        target = (dest / member.filename).resolve()
        if not str(target).startswith(str(root)):
            raise RuntimeError(f"Chemin ZIP refusé : {member.filename}")
    archive.extractall(dest)

class Translator:
    def __init__(self, cache_path: Path):
        try:
            from deep_translator import GoogleTranslator
        except ImportError:
            raise SystemExit(
                "Module manquant : deep-translator\n"
                "Installe-le avec : python -m pip install -r requirements.txt"
            )

        self.engine = GoogleTranslator(source="en", target="fr")
        self.cache_path = cache_path
        self.cache = {}
        self.counter = 0

        if cache_path.exists():
            try:
                self.cache = json.loads(cache_path.read_text("utf-8"))
                print(f"      Cache chargé : {len(self.cache)} traductions")
            except Exception:
                eprint("      Cache illisible : nouveau cache utilisé.")

    def save(self):
        tmp = self.cache_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.cache, ensure_ascii=False, indent=2), "utf-8")
        tmp.replace(self.cache_path)

    def translate_plain(self, text: str) -> str:
        if not text or not re.search(r"[A-Za-z]", text):
            return text
        if text in self.cache:
            return self.cache[text]

        if len(text) > 3500:
            pieces = []
            current = ""
            for piece in re.split(r"(?<=[.!?;:])(\s+)", text):
                if current and len(current) + len(piece) > 3200:
                    pieces.append(current)
                    current = piece
                else:
                    current += piece
            if current:
                pieces.append(current)
            result = "".join(self.translate_plain(piece) for piece in pieces)
            self.cache[text] = result
            return result

        last_error = None
        for attempt in range(1, 7):
            try:
                result = self.engine.translate(text) or text
                self.cache[text] = result
                self.counter += 1
                if self.counter % 25 == 0:
                    self.save()
                    print(f"      {len(self.cache)} segments en cache...")
                return result
            except Exception as exc:
                last_error = exc
                wait = min(3 * attempt, 18)
                eprint(f"      Traduction impossible ({attempt}/6), reprise dans {wait}s : {exc}")
                time.sleep(wait)

        raise RuntimeError(f"Échec de traduction : {last_error}")

    def translate_value(self, value: str) -> str:
        if not value or not re.search(r"[A-Za-z]", value):
            return value

        placeholders = {}
        index = 0

        def mask(token: str) -> str:
            nonlocal index
            key = f"ZXQPH{index:05d}QXZ"
            index += 1
            placeholders[key] = token
            return key

        def square_repl(match: re.Match) -> str:
            token = match.group(0)
            inside = token[1:-1]

            if "|" not in inside:
                return mask(token)

            parts = inside.split("|")
            if parts[0] in {"g", "g2", "p"}:
                rebuilt = "[" + "|".join(
                    [parts[0]] + [self.translate_value(part) for part in parts[1:]]
                ) + "]"
                return mask(rebuilt)

            translated = []
            for pos, part in enumerate(parts):
                star = ""
                if pos == 0 and part.startswith("*"):
                    star = "*"
                    part = part[1:]
                translated.append(star + self.translate_value(part))
            return mask("[" + "|".join(translated) + "]")

        protected = SQUARE_RE.sub(square_repl, value)
        protected = CURLY_RE.sub(lambda m: mask(m.group(0)), protected)
        protected = PRINTF_RE.sub(lambda m: mask(m.group(0)), protected)
        protected = ESCAPE_RE.sub(lambda m: mask(m.group(0)), protected)
        protected = URL_RE.sub(lambda m: mask(m.group(0)), protected)

        translated = self.translate_plain(protected)

        for key, original in placeholders.items():
            translated = translated.replace(key, original)
            translated = translated.replace(key.lower(), original)
            translated = translated.replace(key.upper(), original)

        if "ZXQPH" in translated:
            eprint("      Placeholder altéré : valeur anglaise conservée.")
            return value
        return translated

def split_key_value(line: str):
    match = KEY_RE.match(line)
    if not match:
        return None, None, None
    left, right = line.split("=", 1)
    return match.group(1), left, right

def translate_file(src: Path, translator: Translator) -> list[str]:
    result = []

    for line in src.read_text("utf-8-sig", errors="replace").splitlines():
        stripped = line.strip()

        if not stripped:
            result.append("")
            continue
        if stripped.startswith(("#", ";")):
            result.append(line)
            continue

        key, left, right = split_key_value(line)
        if key is not None:
            if key in META_KEYS or key.endswith(SKIP_SUFFIXES):
                continue

            spacing = re.match(r"(\s*)", right).group(1)
            value = right[len(spacing):]
            result.append(f"{left}={spacing}{translator.translate_value(value)}")
        else:
            prefix = line[: len(line) - len(line.lstrip())]
            result.append(prefix + translator.translate_value(line[len(prefix):]))

    return result

def group_records(lines: list[str]) -> list[list[str]]:
    records = []
    current = []

    for line in lines:
        is_key = bool(KEY_RE.match(line))
        if is_key and current:
            records.append(current)
            current = [line]
        else:
            current.append(line)

    if current:
        records.append(current)
    return records

def metadata(category: str, part: int, total: int) -> str:
    label = category.replace("_", " ").strip()
    return (
        "mod_type = language\n"
        f"mod_name = FR - {label} - {part:02d}/{total:02d}\n"
        "mod_description = Traduction française automatique de Rebuild 3 - pack Android\n"
        "mod_language_name = Français\n"
        "mod_locale_id = FR\n\n"
    )

def chunk_records(records: list[list[str]], max_bytes: int) -> list[list[str]]:
    chunks = []
    current = []
    current_size = 0
    target = max(32 * 1024, max_bytes - 1024)

    for record in records:
        raw = "\n".join(record) + "\n"
        size = len(raw.encode("utf-8"))

        if current and current_size + size > target:
            chunks.append(current)
            current = []
            current_size = 0

        current.extend(record)
        current_size += size

    if current:
        chunks.append(current)
    return chunks

def write_chunks(category: str, lines: list[str], out_dir: Path, max_bytes: int):
    chunks = chunk_records(group_records(lines), max_bytes)
    total = len(chunks)
    written = []

    for number, chunk in enumerate(chunks, 1):
        filename = f"fr_{category}_p{number:02d}.properties"
        path = out_dir / filename
        body = metadata(category, number, total) + "\n".join(chunk).rstrip() + "\n"
        path.write_text(body, "utf-8")
        written.append(path)

    return written

def build_index(files: list[Path], site_dir: Path):
    rows = []

    for path in sorted(files):
        size = path.stat().st_size / 1024
        rows.append(
            f'<article class="card"><div><strong>{html.escape(path.name)}</strong>'
            f'<small>{size:.1f} KiB</small></div>'
            f'<button data-file="{html.escape(path.name)}">Copier</button></article>'
        )

    page = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rebuild 3 — Français Android</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,"Segoe UI",sans-serif}
body{margin:0;background:#111;color:#eee}
main{width:min(900px,92vw);margin:32px auto 80px}
.lead{color:#bbb;line-height:1.55}
.notice{background:#1d1d1d;border:1px solid #333;border-radius:14px;padding:16px;margin:20px 0}
.grid{display:grid;gap:10px;margin-top:20px}
.card{display:flex;justify-content:space-between;align-items:center;gap:14px;padding:14px 16px;background:#181818;border:1px solid #303030;border-radius:12px}
.card strong{display:block;word-break:break-all}
.card small{display:block;color:#999}
button{border:0;border-radius:10px;padding:11px 16px;font-weight:700;cursor:pointer}
#status{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);background:#000;border:1px solid #444;border-radius:999px;padding:10px 16px;opacity:0;transition:.2s;pointer-events:none}
#status.show{opacity:1}
code{background:#222;padding:.12rem .35rem;border-radius:5px}
</style>
</head>
<body>
<main>
<h1>Rebuild 3 — Français Android</h1>
<p class="lead">Cette page copie chaque morceau du pack dans le presse-papiers Android.</p>
<section class="notice"><b>Installation :</b> Copier → Rebuild 3 → <code>Config → Modding → Install Mod</code> → Coller → Okay.</section>
<section class="grid">__ROWS__</section>
</main>
<div id="status"></div>
<script>
const statusBox=document.getElementById('status');
function status(msg){statusBox.textContent=msg;statusBox.classList.add('show');setTimeout(()=>statusBox.classList.remove('show'),1800)}
async function copyText(text){
  if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(text);return}
  const ta=document.createElement('textarea');ta.value=text;ta.style.position='fixed';ta.style.opacity='0';
  document.body.appendChild(ta);ta.focus();ta.select();document.execCommand('copy');ta.remove()
}
document.querySelectorAll('button[data-file]').forEach(btn=>btn.addEventListener('click',async()=>{
  try{
    const r=await fetch('./'+encodeURIComponent(btn.dataset.file),{cache:'no-store'});
    if(!r.ok)throw new Error('HTTP '+r.status);
    await copyText(await r.text());
    btn.textContent='Copié ✓';status(btn.dataset.file+' copié')
  }catch(e){status('Erreur : '+e.message)}
}));
</script>
</body>
</html>
""".replace("__ROWS__", "".join(rows))

    (site_dir / "index.html").write_text(page, "utf-8")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-zip", type=Path)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--chunk-kb", type=int, default=DEFAULT_CHUNK_KB)
    args = ap.parse_args()

    out = args.out.resolve()
    work = out / "_work"
    extracted = work / "source"
    site = out / "site"
    cache = out / "translation_cache.json"

    out.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)

    if extracted.exists():
        shutil.rmtree(extracted)
    extracted.mkdir(parents=True)

    if site.exists():
        shutil.rmtree(site)
    site.mkdir(parents=True)

    source_zip = args.source_zip.resolve() if args.source_zip else BUNDLED_SOURCE
    if not source_zip.exists():
        if args.source_zip:
            raise SystemExit(f"ZIP introuvable : {source_zip}")
        source_zip = work / "rebuild3_mod_sources_2024.zip"
        download(SOURCE_URL, source_zip)
    else:
        print(f"[1/5] ZIP local : {source_zip}")

    print("[2/5] Extraction...")
    with zipfile.ZipFile(source_zip) as archive:
        safe_extract(archive, extracted)

    sources = sorted(extracted.rglob("en_*.properties"))
    if not sources:
        raise SystemExit("Aucun fichier en_*.properties trouvé dans le ZIP.")

    print(f"      {len(sources)} fichier(s) source.")
    translator = Translator(cache)
    all_files = []

    print("[3/5] Traduction EN → FR...")
    for position, src in enumerate(sources, 1):
        category = src.stem[3:] if src.stem.startswith("en_") else src.stem
        print(f"      [{position}/{len(sources)}] {src.name}")
        translated = translate_file(src, translator)
        all_files.extend(
            write_chunks(category, translated, site, max(args.chunk_kb, 32) * 1024)
        )
        translator.save()

    print("[4/5] Génération du site...")
    build_index(all_files, site)

    manifest = {
        "source": SOURCE_URL,
        "language": "fr",
        "locale_id": "FR",
        "files": [{"name": p.name, "bytes": p.stat().st_size} for p in sorted(all_files)],
    }
    (site / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        "utf-8",
    )

    print("[5/5] Terminé.")
    print(f"      Site : {site}")
    print(f"      Morceaux : {len(all_files)}")

if __name__ == "__main__":
    main()
