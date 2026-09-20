#!/usr/bin/env python3
from pathlib import Path
import argparse
import re

KEY_RE = re.compile(r"^([A-Za-z0-9_'.-]+)\s*=")
META = {"mod_type", "mod_name", "mod_description", "mod_language_name", "mod_locale_id"}
SKIP_SUFFIXES = ("_picture", "_pictureColin", "_pictureColin2", "_icon")

def keys_from_files(paths):
    keys = set()
    for path in paths:
        for line in path.read_text("utf-8-sig", errors="replace").splitlines():
            m = KEY_RE.match(line)
            if not m:
                continue
            key = m.group(1)
            if key in META or key.endswith(SKIP_SUFFIXES):
                continue
            keys.add(key)
    return keys

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-dir", default="rebuild3_fr_output/_work/source")
    ap.add_argument("--site-dir", default="rebuild3_fr_output/site")
    args = ap.parse_args()

    source = Path(args.source_dir)
    site = Path(args.site_dir)
    src_files = sorted(source.rglob("en_*.properties"))
    fr_files = sorted(site.glob("fr_*.properties"))

    if not src_files:
        raise SystemExit(f"Aucune source anglaise trouvée dans {source}")
    if not fr_files:
        raise SystemExit(f"Aucun fichier français trouvé dans {site}")

    source_keys = keys_from_files(src_files)
    fr_keys = keys_from_files(fr_files)
    missing = sorted(source_keys - fr_keys)
    extra = sorted(fr_keys - source_keys)

    print(f"Clés source : {len(source_keys)}")
    print(f"Clés FR     : {len(fr_keys)}")
    print(f"Manquantes  : {len(missing)}")
    print(f"Supplément. : {len(extra)}")

    if missing:
        print("\nPremières clés manquantes :")
        print("\n".join(missing[:50]))
        return 2
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
