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
DEFAULT_REQUEST_INTERVAL = 1.0
DEFAULT_PROVIDER = "ollama"
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
DEFAULT_OLLAMA_MODEL = "translategemma:4b-it-q4_K_M"
DEFAULT_BATCH_SIZE = 20
DEFAULT_BATCH_CHARS = 6000

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
    def __init__(
        self,
        cache_path: Path,
        provider: str = DEFAULT_PROVIDER,
        request_interval: float = DEFAULT_REQUEST_INTERVAL,
        ollama_url: str = DEFAULT_OLLAMA_URL,
        ollama_model: str = DEFAULT_OLLAMA_MODEL,
        batch_size: int = DEFAULT_BATCH_SIZE,
        batch_chars: int = DEFAULT_BATCH_CHARS,
    ):
        self.provider = provider
        self.cache_path = cache_path
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache = {}
        self.counter = 0
        self.request_interval = max(0.25, float(request_interval))
        self.last_request_at = 0.0
        self.ollama_url = ollama_url.rstrip("/")
        self.ollama_model = ollama_model
        self.batch_size = max(1, int(batch_size))
        self.batch_chars = max(500, int(batch_chars))
        self.engine = None

        if cache_path.exists():
            try:
                self.cache = json.loads(cache_path.read_text("utf-8"))
                print(f"      Cache chargé : {len(self.cache)} traductions")
            except Exception:
                eprint("      Cache illisible : nouveau cache utilisé.")

        if self.provider == "google":
            try:
                from deep_translator import GoogleTranslator
            except ImportError:
                raise SystemExit(
                    "Module manquant : deep-translator\n"
                    "Installe-le avec : python -m pip install -r requirements.txt"
                )
            self.engine = GoogleTranslator(source="en", target="fr")
        elif self.provider == "ollama":
            self._check_ollama()
        else:
            raise SystemExit(f"Provider inconnu : {self.provider}")

    def save(self):
        tmp = self.cache_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.cache, ensure_ascii=False, indent=2), "utf-8")
        tmp.replace(self.cache_path)

    def _check_ollama(self) -> None:
        try:
            req = urllib.request.Request(
                f"{self.ollama_url}/api/tags",
                headers={"User-Agent": "Rebuild3-FR-Builder/1.0"},
            )
            with urllib.request.urlopen(req, timeout=10) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:
            raise SystemExit(
                "Ollama n'est pas joignable sur "
                f"{self.ollama_url}. Vérifie qu'Ollama est démarré.\n{exc}"
            )

        names = {
            item.get("name", "")
            for item in payload.get("models", [])
            if isinstance(item, dict)
        }
        if self.ollama_model not in names:
            # Ollama peut parfois renvoyer le modèle avec :latest.
            base_names = {name.split(":")[0] for name in names}
            wanted_base = self.ollama_model.split(":")[0]
            if wanted_base not in base_names:
                raise SystemExit(
                    f"Modèle Ollama absent : {self.ollama_model}\n"
                    f"Installe-le avec : ollama pull {self.ollama_model}"
                )

        print(f"      Ollama OK : {self.ollama_model}")

    def _throttle(self) -> None:
        elapsed = time.monotonic() - self.last_request_at
        remaining = self.request_interval - elapsed
        if remaining > 0:
            time.sleep(remaining)
        self.last_request_at = time.monotonic()

    def _translate_google(self, text: str) -> str:
        self._throttle()
        return self.engine.translate(text) or text

    def _translate_ollama(self, text: str) -> str:
        prompt = (
            "You are a professional English (en) to French (fr) translator. "
            "Your goal is to accurately convey the meaning and nuances of the "
            "original English text while adhering to French grammar, vocabulary, "
            "and cultural sensitivities.\n"
            "Produce only the French translation, without any additional "
            "explanations or commentary. Preserve every token matching "
            "ZXQPH followed by digits and QXZ exactly, character for character. "
            "Please translate the following English text into French:\n\n"
            f"{text}"
        )

        body = json.dumps(
            {
                "model": self.ollama_model,
                "messages": [{"role": "user", "content": prompt}],
                "stream": False,
                "keep_alive": "30m",
                "options": {
                    "temperature": 0,
                    "num_ctx": 8192,
                },
            }
        ).encode("utf-8")

        req = urllib.request.Request(
            f"{self.ollama_url}/api/chat",
            data=body,
            headers={
                "Content-Type": "application/json",
                "User-Agent": "Rebuild3-FR-Builder/1.0",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=600) as response:
            payload = json.loads(response.read().decode("utf-8"))

        result = payload.get("message", {}).get("content", "").strip()
        if not result:
            raise RuntimeError("Ollama a renvoyé une traduction vide.")
        return result

    def _translate_ollama_batch(self, texts: list[str]) -> list[str]:
        """Traduit plusieurs fragments en un seul appel Ollama avec JSON Schema."""
        if not texts:
            return []

        items = [{"id": i, "text": text} for i, text in enumerate(texts)]
        schema = {
            "type": "object",
            "properties": {
                "translations": {
                    "type": "array",
                    "minItems": len(items),
                    "maxItems": len(items),
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "integer"},
                            "text": {"type": "string"},
                        },
                        "required": ["id", "text"],
                        "additionalProperties": False,
                    },
                }
            },
            "required": ["translations"],
            "additionalProperties": False,
        }

        prompt = (
            "Translate every item from English to natural French. "
            "This is text from the zombie survival game Rebuild 3. "
            "Keep the tone appropriate to a strategy/survival game. "
            "Do not merge, omit, reorder or explain items. "
            "Keep each id unchanged. Return JSON matching the provided schema.\n\n"
            "INPUT:\n"
            + json.dumps({"items": items}, ensure_ascii=False)
        )

        body = json.dumps(
            {
                "model": self.ollama_model,
                "messages": [{"role": "user", "content": prompt}],
                "stream": False,
                "keep_alive": "30m",
                "format": schema,
                "options": {
                    "temperature": 0,
                    "num_ctx": 8192,
                },
            },
            ensure_ascii=False,
        ).encode("utf-8")

        req = urllib.request.Request(
            f"{self.ollama_url}/api/chat",
            data=body,
            headers={
                "Content-Type": "application/json",
                "User-Agent": "Rebuild3-FR-Builder/1.0",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=900) as response:
            payload = json.loads(response.read().decode("utf-8"))

        raw = payload.get("message", {}).get("content", "").strip()
        if not raw:
            raise RuntimeError("Ollama a renvoyé un batch vide.")

        parsed = json.loads(raw)
        rows = parsed.get("translations")
        if not isinstance(rows, list):
            raise RuntimeError("Réponse batch sans tableau 'translations'.")

        by_id = {}
        for row in rows:
            if not isinstance(row, dict):
                continue
            idx = row.get("id")
            text = row.get("text")
            if isinstance(idx, int) and isinstance(text, str) and text.strip():
                by_id[idx] = text.strip()

        expected = set(range(len(texts)))
        if set(by_id) != expected:
            missing = sorted(expected - set(by_id))
            extra = sorted(set(by_id) - expected)
            raise RuntimeError(
                f"Batch incomplet : manquants={missing[:5]}, extras={extra[:5]}"
            )

        return [by_id[i] for i in range(len(texts))]

    def _store_batch(self, texts: list[str], translations: list[str]) -> None:
        if len(texts) != len(translations):
            raise RuntimeError("Taille batch traduction incohérente.")
        for source, translated in zip(texts, translations):
            self.cache[source] = translated
            self.counter += 1
        self.save()

    def _translate_batch_with_fallback(self, texts: list[str], depth: int = 0) -> None:
        """Batch -> demi-lots -> traduction unitaire en dernier recours."""
        pending = [text for text in texts if text not in self.cache]
        if not pending:
            return

        try:
            translations = self._translate_ollama_batch(pending)
            self._store_batch(pending, translations)
            print(
                f"      Batch Ollama OK : {len(pending)} fragments "
                f"({len(self.cache)} en cache)"
            )
            return
        except Exception as exc:
            if len(pending) == 1:
                eprint(
                    f"      Batch unitaire échoué, fallback traduction simple : {exc}"
                )
                translated = self._translate_ollama(pending[0])
                self._store_batch(pending, [translated])
                return

            mid = len(pending) // 2
            eprint(
                f"      Batch de {len(pending)} fragments refusé/incomplet ; "
                f"découpage en {mid}+{len(pending)-mid}. Détail : {exc}"
            )
            self._translate_batch_with_fallback(pending[:mid], depth + 1)
            self._translate_batch_with_fallback(pending[mid:], depth + 1)

    def translate_many(self, texts: list[str]) -> None:
        """Pré-remplit le cache en lots pour les fragments uniques."""
        unique = []
        seen = set()

        for text in texts:
            if (
                not text
                or text in self.cache
                or text in seen
                or not re.search(r"[A-Za-z]", text)
            ):
                continue
            # Les très longs textes gardent le chemin unitaire/splitté existant.
            if len(text) > 3500:
                continue
            seen.add(text)
            unique.append(text)

        if not unique:
            return

        batches = []
        current = []
        current_chars = 0

        for text in unique:
            size = len(text)
            if current and (
                len(current) >= self.batch_size
                or current_chars + size > self.batch_chars
            ):
                batches.append(current)
                current = []
                current_chars = 0

            current.append(text)
            current_chars += size

        if current:
            batches.append(current)

        print(
            f"      Pré-traduction batch : {len(unique)} fragments, "
            f"{len(batches)} lot(s), max {self.batch_size}/lot"
        )

        for index, batch in enumerate(batches, 1):
            print(
                f"      Lot {index}/{len(batches)} : "
                f"{len(batch)} fragments, {sum(map(len, batch))} caractères"
            )
            self._translate_batch_with_fallback(batch)

    def _collect_fragment(self, text: str, output: list[str]) -> None:
        if not text or not re.search(r"[A-Za-z]", text):
            return
        match = re.match(r"^(\s*)(.*?)(\s*)$", text, re.DOTALL)
        if not match:
            core = text
        else:
            core = match.group(2)
        if core and re.search(r"[A-Za-z]", core) and core not in self.cache:
            output.append(core)

    def _collect_square_token(self, token: str, output: list[str]) -> None:
        inside = token[1:-1]
        if "|" not in inside:
            return

        parts = inside.split("|")
        if parts[0] in {"g", "g2", "p"}:
            for part in parts[1:]:
                self._collect_value_fragments(part, output)
            return

        for pos, part in enumerate(parts):
            if pos == 0 and part.startswith("*"):
                part = part[1:]
            self._collect_value_fragments(part, output)

    def _collect_value_fragments(self, value: str, output: list[str]) -> None:
        if not value or not re.search(r"[A-Za-z]", value):
            return

        protected_patterns = [
            ("square", SQUARE_RE),
            ("curly", CURLY_RE),
            ("printf", PRINTF_RE),
            ("escape", ESCAPE_RE),
            ("url", URL_RE),
        ]

        cursor = 0
        length = len(value)

        while cursor < length:
            next_kind = None
            next_match = None

            for kind, pattern in protected_patterns:
                match = pattern.search(value, cursor)
                if match is None:
                    continue
                if next_match is None or match.start() < next_match.start():
                    next_kind = kind
                    next_match = match
                elif (
                    next_match is not None
                    and match.start() == next_match.start()
                    and match.end() > next_match.end()
                ):
                    next_kind = kind
                    next_match = match

            if next_match is None:
                self._collect_fragment(value[cursor:], output)
                break

            if next_match.start() > cursor:
                self._collect_fragment(value[cursor:next_match.start()], output)

            if next_kind == "square":
                self._collect_square_token(next_match.group(0), output)

            cursor = next_match.end()

    def prefetch_values(self, values: list[str]) -> None:
        fragments = []
        for value in values:
            self._collect_value_fragments(value, fragments)
        self.translate_many(fragments)

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
            self.save()
            return result

        last_error = None
        max_attempts = 4 if self.provider == "ollama" else 10

        for attempt in range(1, max_attempts + 1):
            try:
                if self.provider == "ollama":
                    result = self._translate_ollama(text)
                else:
                    result = self._translate_google(text)

                self.cache[text] = result
                self.counter += 1
                self.save()

                if self.counter % 25 == 0:
                    print(f"      {len(self.cache)} segments en cache...")
                return result

            except Exception as exc:
                last_error = exc

                if self.provider == "ollama":
                    wait = [2, 5, 15, 30][min(attempt - 1, 3)]
                    eprint(
                        f"      Ollama erreur ({attempt}/{max_attempts}), "
                        f"reprise dans {wait}s : {exc}"
                    )
                else:
                    message = str(exc).lower()
                    is_rate_limit = (
                        "too many requests" in message
                        or "429" in message
                        or "rate limit" in message
                    )
                    if is_rate_limit:
                        waits = [30, 60, 120, 180, 300, 300, 300, 300, 300, 300]
                        wait = waits[min(attempt - 1, len(waits) - 1)]
                        eprint(
                            f"      Google limite les requêtes "
                            f"({attempt}/{max_attempts}). Pause {wait}s."
                        )
                    else:
                        wait = min(5 * attempt, 60)
                        eprint(
                            f"      Traduction impossible "
                            f"({attempt}/{max_attempts}), reprise dans {wait}s : {exc}"
                        )

                self.save()
                time.sleep(wait)

        self.save()
        raise RuntimeError(
            f"Échec de traduction après {max_attempts} tentatives : {last_error}"
        )

    def _translate_fragment(self, text: str) -> str:
        """Traduit uniquement le texte humain en conservant les espaces externes."""
        if not text or not re.search(r"[A-Za-z]", text):
            return text

        match = re.match(r"^(\s*)(.*?)(\s*)$", text, re.DOTALL)
        if not match:
            return self.translate_plain(text)

        prefix, core, suffix = match.groups()
        if not core or not re.search(r"[A-Za-z]", core):
            return text

        return prefix + self.translate_plain(core) + suffix

    def _translate_square_token(self, token: str) -> str:
        """Conserve la syntaxe [..] et ne traduit que ses branches textuelles."""
        inside = token[1:-1]

        # Variables simples : [Name], [CityName], etc.
        if "|" not in inside:
            return token

        parts = inside.split("|")

        # Constructions genrées/plurielles du moteur Rebuild.
        if parts[0] in {"g", "g2", "p"}:
            translated = [parts[0]]
            translated.extend(self.translate_value(part) for part in parts[1:])
            return "[" + "|".join(translated) + "]"

        # Variantes [one|two|three], éventuellement préfixées par *.
        translated = []
        for pos, part in enumerate(parts):
            star = ""
            if pos == 0 and part.startswith("*"):
                star = "*"
                part = part[1:]
            translated.append(star + self.translate_value(part))
        return "[" + "|".join(translated) + "]"

    def translate_value(self, value: str) -> str:
        """
        Traduit sans jamais envoyer les placeholders Rebuild à l'IA.

        Les tokens [Name], {1}, %s, \\n, URLs, etc. sont retirés du texte transmis
        à Ollama puis réinjectés directement. Cela élimine le risque qu'un modèle
        renomme, espace ou supprime un placeholder.
        """
        if not value or not re.search(r"[A-Za-z]", value):
            return value

        protected_patterns = [
            ("square", SQUARE_RE),
            ("curly", CURLY_RE),
            ("printf", PRINTF_RE),
            ("escape", ESCAPE_RE),
            ("url", URL_RE),
        ]

        result = []
        cursor = 0
        length = len(value)

        while cursor < length:
            next_kind = None
            next_match = None

            for kind, pattern in protected_patterns:
                match = pattern.search(value, cursor)
                if match is None:
                    continue
                if next_match is None or match.start() < next_match.start():
                    next_kind = kind
                    next_match = match
                elif (
                    next_match is not None
                    and match.start() == next_match.start()
                    and match.end() > next_match.end()
                ):
                    # En cas de chevauchement, protège le token le plus long.
                    next_kind = kind
                    next_match = match

            if next_match is None:
                result.append(self._translate_fragment(value[cursor:]))
                break

            if next_match.start() > cursor:
                result.append(
                    self._translate_fragment(value[cursor:next_match.start()])
                )

            token = next_match.group(0)
            if next_kind == "square":
                result.append(self._translate_square_token(token))
            else:
                # Ne jamais envoyer ces tokens à Ollama.
                result.append(token)

            cursor = next_match.end()

        return "".join(result)

def split_key_value(line: str):
    match = KEY_RE.match(line)
    if not match:
        return None, None, None
    left, right = line.split("=", 1)
    return match.group(1), left, right

def translate_file(src: Path, translator: Translator) -> list[str]:
    source_lines = src.read_text("utf-8-sig", errors="replace").splitlines()

    # Première passe : collecte tous les textes du fichier et les traduit en batch.
    values_to_prefetch = []
    for line in source_lines:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", ";")):
            continue

        key, left, right = split_key_value(line)
        if key is not None:
            if key in META_KEYS or key.endswith(SKIP_SUFFIXES):
                continue
            spacing = re.match(r"(\s*)", right).group(1)
            values_to_prefetch.append(right[len(spacing):])
        else:
            prefix = line[: len(line) - len(line.lstrip())]
            values_to_prefetch.append(line[len(prefix):])

    translator.prefetch_values(values_to_prefetch)

    # Deuxième passe : reconstruction exacte du fichier depuis le cache.
    result = []
    for line in source_lines:
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

def write_ui_probe(sections: list[tuple[str, list[str]]], site_dir: Path) -> Path:
    """Petit pack de diagnostic UI, volontairement < 16 Kio."""
    prefixes = ("button_", "label_", "notice_workshop", "tooltip_")
    selected = []

    for _category, lines in sections:
        for line in lines:
            match = KEY_RE.match(line)
            if not match:
                continue
            key = match.group(1)
            if key.startswith(prefixes):
                selected.append(line)

    path = site_dir / "fr_ui_test.properties"
    body = (
        "mod_type = language\n"
        "mod_name = Français UI - TEST 16K\n"
        "mod_description = Petit pack de diagnostic pour tester la limite de collage Android de Rebuild 3.\n"
        "mod_language_name = Français UI TEST\n"
        "mod_locale_id = FRUI\n\n"
        + "\n".join(selected).rstrip()
        + "\n"
    )
    path.write_text(body, "utf-8")

    if len(body) >= 16 * 1024:
        raise RuntimeError(
            f"Le pack UI de test dépasse 16 Kio : {len(body)} caractères."
        )

    return path


def _section_records(category: str, lines: list[str]) -> list[list[str]]:
    header = [
        "; ============================================================",
        f"; {category}",
        "; ============================================================",
        "",
    ]
    grouped = group_records(lines)
    if grouped:
        grouped[0] = header + grouped[0]
        return grouped
    return [header]


def write_android_pack(
    sections: list[tuple[str, list[str]]],
    site_dir: Path,
    max_kb: int = 24,
) -> list[Path]:
    """
    Version Android multi-fichier.
    Chaque partie reste volontairement petite afin de contourner les limites
    pratiques du champ Install Mod / AIR sur Android.
    """
    max_bytes = max(8, int(max_kb)) * 1024
    chunks: list[list[str]] = []
    current: list[str] = []
    current_size = 0

    for category, lines in sections:
        for record in _section_records(category, lines):
            raw = "\n".join(record) + "\n"
            size = len(raw.encode("utf-8"))

            if current and current_size + size > max_bytes:
                chunks.append(current)
                current = []
                current_size = 0

            if size > max_bytes and current:
                chunks.append(current)
                current = []
                current_size = 0

            current.extend(record)
            current_size += size

            if size > max_bytes:
                chunks.append(current)
                current = []
                current_size = 0

    if current:
        chunks.append(current)

    total = len(chunks)
    written: list[Path] = []

    for number, chunk in enumerate(chunks, 1):
        path = site_dir / f"fr_rebuild3_android_{number:03d}_sur_{total:03d}.properties"
        body = (
            "mod_type = language\n"
            f"mod_name = Rebuild 3 - Français Android {number}/{total}\n"
            f"mod_description = Traduction française Android - partie {number} sur {total}.\n"
            "mod_language_name = Français\n"
            "mod_locale_id = FR\n\n"
            + "\n".join(chunk).rstrip()
            + "\n"
        )
        path.write_text(body, "utf-8")
        written.append(path)

    return written


def write_desktop_pack(
    sections: list[tuple[str, list[str]]],
    site_dir: Path,
) -> Path:
    """Version PC/Desktop complète en un seul fichier."""
    path = site_dir / "fr_rebuild3_desktop_complet.properties"
    body = [
        "mod_type = language",
        "mod_name = Rebuild 3 - Français Desktop complet",
        "mod_description = Traduction française complète de Rebuild 3 pour PC/Desktop.",
        "mod_language_name = Français",
        "mod_locale_id = FR",
        "",
    ]

    for category, lines in sections:
        body.extend([
            "; ============================================================",
            f"; {category}",
            "; ============================================================",
            "",
        ])
        body.extend(lines)
        body.append("")

    path.write_text("\n".join(body).rstrip() + "\n", "utf-8")
    return path


def build_index(files: list[Path], site_dir: Path, ui_probe: Path | None = None, desktop_file: Path | None = None):
    cards = []
    fr_cards = []

    for i, path in enumerate(files, 1):
        size = path.stat().st_size / 1024
        card = (
            '<article class="card">'
            '<div>'
            f'<strong>Français — Partie {i}/{len(files)}</strong>'
            f'<small>{size:.1f} KiB — installer cette partie dans Rebuild 3.</small>'
            '</div>'
            '<div class="actions">'
            f'<button data-file="{html.escape(path.name)}">Copier {i}/{len(files)}</button>'
            '</div>'
            '</article>'
        )
        cards.append(card)

        fr_cards.append(
            '<article class="card">'
            '<div>'
            f'<strong>Partie {i}/{len(files)}</strong>'
            f'<small>{size:.1f} KiB</small>'
            '</div>'
            f'<button data-file="../{html.escape(path.name)}">Copier {i}/{len(files)}</button>'
            '</article>'
        )

    probe_card = ""
    if ui_probe is not None:
        probe_size = ui_probe.stat().st_size / 1024
        probe_card = (
            '<article class="card">'
            '<div>'
            '<strong>TEST UI &lt; 16 Kio <span class="badge">Diagnostic</span></strong>'
            f'<small>{probe_size:.1f} KiB — teste uniquement la limite de collage et les menus.</small>'
            '</div>'
            '<div class="actions">'
            f'<button data-file="{html.escape(ui_probe.name)}">Copier le test UI</button>'
            '</div>'
            '</article>'
        )

    desktop_card = ""
    if desktop_file is not None:
        desktop_size = desktop_file.stat().st_size / 1024
        desktop_card = (
            '<article class="card">'
            '<div>'
            '<strong>PC / Desktop <span class="badge">1 fichier</span></strong>'
            f'<small>{desktop_size:.1f} KiB — version complète en un seul fichier.</small>'
            '</div>'
            '<div class="actions">'
            f'<a class="link secondary" href="{html.escape(desktop_file.name)}">Télécharger</a>'
            '</div>'
            '</article>'
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
.grid{display:grid;gap:12px;margin-top:20px}
.card{display:flex;justify-content:space-between;align-items:center;gap:18px;padding:16px;background:#181818;border:1px solid #303030;border-radius:12px}
.card strong{display:block;font-size:1.05rem}
.card small{display:block;color:#999;margin-top:4px;line-height:1.4}
.actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}
button,.link{border:0;border-radius:10px;padding:11px 16px;font-weight:700;cursor:pointer;text-decoration:none;background:#eee;color:#111;font-size:.9rem}
.link.secondary{background:#2a2a2a;color:#eee;border:1px solid #444}
.badge{display:inline-block;font-size:.72rem;padding:2px 7px;border:1px solid #444;border-radius:999px;color:#aaa;margin-left:6px}
#status{position:fixed;left:50%;bottom:18px;transform:translateX(-50%);background:#000;border:1px solid #444;border-radius:999px;padding:10px 16px;opacity:0;transition:.2s;pointer-events:none}
#status.show{opacity:1}
code{background:#222;padding:.12rem .35rem;border-radius:5px}
h2{margin-top:32px}
@media(max-width:650px){.card{align-items:flex-start;flex-direction:column}.actions{justify-content:flex-start}}
</style>
</head>
<body>
<main>
<h1>Rebuild 3 — Français Android</h1>
<p class="lead">Traduction française complète et mods gameplay pour Rebuild 3.</p>

<section class="notice">
<b>Installation Android :</b>
installe toutes les parties <b>dans l'ordre indiqué</b> via
<code>Config → Modding → Install Mod</code>, puis redémarre le jeu.
</section>

<h2>PC / Desktop</h2>
<section class="grid">__DESKTOP_CARD__</section>

<h2>Diagnostic Android</h2>
<section class="grid">__PROBE_CARD__</section>
<p class="lead">Teste d'abord ce petit fichier. Les gros morceaux ci-dessous restent expérimentaux tant que la limite réelle de Rebuild n'est pas mesurée.</p>

<h2>Traduction française <span class="badge">expérimental</span></h2>
<section class="grid">__FR_CARDS__</section>

<p><a class="link secondary" href="./fr/">Lien permanent vers la dernière traduction</a></p>

<h2>Mods gameplay</h2>
<section class="grid">
<article class="card">
  <div>
    <strong>Coup de Pouce <span class="badge">Cheat léger</span></strong>
    <small>Moins de grind, progression plus agréable, sans supprimer le danger.</small>
  </div>
  <div class="actions">
    <button data-file="mods/coup-de-pouce.txt">Copier le mod</button>
  </div>
</article>

<article class="card">
  <div>
    <strong>Apocalypse Vivante <span class="badge">Overhaul</span></strong>
    <small>Monde plus actif, hordes périodiques, factions dynamiques et survie retravaillée.</small>
  </div>
  <div class="actions">
    <button data-file="mods/apocalypse-vivante.txt">Copier le mod</button>
  </div>
</article>

<article class="card">
  <div>
    <strong>Tous les mods</strong>
    <small>Page dédiée aux mods gameplay.</small>
  </div>
  <div class="actions">
    <a class="link secondary" href="./mods/">Ouvrir les mods</a>
  </div>
</article>
</section>
</main>

<div id="status"></div>
<script>
const statusBox=document.getElementById('status');
function status(msg){
  statusBox.textContent=msg;
  statusBox.classList.add('show');
  setTimeout(()=>statusBox.classList.remove('show'),1800);
}
async function copyText(text){
  if(navigator.clipboard&&window.isSecureContext){
    await navigator.clipboard.writeText(text);
    return;
  }
  const ta=document.createElement('textarea');
  ta.value=text;
  ta.style.position='fixed';
  ta.style.opacity='0';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  document.execCommand('copy');
  ta.remove();
}
document.querySelectorAll('button[data-file]').forEach(btn=>{
  btn.addEventListener('click',async()=>{
    try{
      const r=await fetch('./'+btn.dataset.file,{cache:'no-store'});
      if(!r.ok)throw new Error('HTTP '+r.status);
      await copyText(await r.text());
      btn.textContent='Copié ✓';
      status('Copié dans le presse-papiers');
    }catch(e){
      status('Erreur : '+e.message);
    }
  });
});
</script>
</body>
</html>
""".replace("__FR_CARDS__", "".join(cards)).replace("__PROBE_CARD__", probe_card).replace("__DESKTOP_CARD__", desktop_card)

    (site_dir / "index.html").write_text(page, "utf-8")

    fr_dir = site_dir / "fr"
    fr_dir.mkdir(parents=True, exist_ok=True)
    latest_page = """<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rebuild 3 — Traduction FR</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,"Segoe UI",sans-serif}
body{margin:0;background:#111;color:#eee}
main{width:min(760px,92vw);margin:48px auto}
.card{display:flex;justify-content:space-between;align-items:center;gap:14px;background:#181818;border:1px solid #303030;border-radius:14px;padding:16px;margin:12px 0}
p,small{color:#bbb;line-height:1.55}
button,a{display:inline-block;border:0;border-radius:10px;padding:12px 16px;font-weight:700;text-decoration:none;cursor:pointer}
button{background:#eee;color:#111}
a{background:#2a2a2a;color:#eee;border:1px solid #444}
code{background:#222;padding:.12rem .35rem;border-radius:5px}
#status{margin-top:14px;color:#bbb}
@media(max-width:620px){.card{align-items:flex-start;flex-direction:column}}
</style>
</head>
<body>
<main>
<h1>Rebuild 3 — Français</h1>
<p>Cette URL reste permanente et propose toujours la dernière version publiée.</p>
<p>Installe <b>toutes les parties dans l'ordre</b>, puis redémarre Rebuild 3.</p>
<section>__FR_CARDS__</section>
<p><a href="../">Retour à l'accueil</a></p>
<div id="status"></div>
</main>
<script>
const status=document.getElementById('status');
document.querySelectorAll('button[data-file]').forEach(btn=>{
  btn.onclick=async function(){
    try{
      const r=await fetch(this.dataset.file,{cache:'no-store'});
      if(!r.ok)throw new Error('HTTP '+r.status);
      await navigator.clipboard.writeText(await r.text());
      this.textContent='Copié ✓';
      status.textContent='Partie copiée dans le presse-papiers.';
    }catch(e){
      status.textContent='Erreur : '+e.message;
    }
  };
});
</script>
</body>
</html>
""".replace("__FR_CARDS__", "".join(fr_cards))

    (fr_dir / "index.html").write_text(latest_page, "utf-8")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-zip", type=Path)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--chunk-kb", type=int, default=DEFAULT_CHUNK_KB)
    ap.add_argument("--cache", type=Path, default=None)
    ap.add_argument("--request-interval", type=float, default=DEFAULT_REQUEST_INTERVAL)
    ap.add_argument("--provider", choices=["ollama", "google"], default=DEFAULT_PROVIDER)
    ap.add_argument("--ollama-url", default=DEFAULT_OLLAMA_URL)
    ap.add_argument("--ollama-model", default=DEFAULT_OLLAMA_MODEL)
    ap.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    ap.add_argument("--batch-chars", type=int, default=DEFAULT_BATCH_CHARS)
    args = ap.parse_args()

    out = args.out.resolve()
    work = out / "_work"
    extracted = work / "source"
    site = out / "site"
    cache = args.cache.resolve() if args.cache else (out / "translation_cache.json")

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
    print(f"      Cache persistant : {cache}")
    print(f"      Provider : {args.provider}")
    translator = Translator(
        cache,
        provider=args.provider,
        request_interval=args.request_interval,
        ollama_url=args.ollama_url,
        ollama_model=args.ollama_model,
        batch_size=args.batch_size,
        batch_chars=args.batch_chars,
    )
    print(
        f"      Batch Ollama : max {args.batch_size} fragments / "
        f"{args.batch_chars} caractères"
    )
    sections = []

    print("[3/5] Traduction EN → FR...")
    for position, src in enumerate(sources, 1):
        category = src.stem[3:] if src.stem.startswith("en_") else src.stem
        print(f"      [{position}/{len(sources)}] {src.name}")
        translated = translate_file(src, translator)
        sections.append((category, translated))
        translator.save()

    print("[4/5] Génération Android multi-fichier + Desktop complet...")
    ui_probe = write_ui_probe(sections, site)
    fr_files = write_android_pack(sections, site, max_kb=24)
    desktop_file = write_desktop_pack(sections, site)
    build_index(fr_files, site, ui_probe=ui_probe, desktop_file=desktop_file)

    manifest = {
        "source": SOURCE_URL,
        "language": "fr",
        "locale_id": "FR",
        "ui_probe": {
            "name": ui_probe.name,
            "bytes": ui_probe.stat().st_size,
            "characters": len(ui_probe.read_text("utf-8")),
        },
        "android": {
            "files": [
                {"name": path.name, "bytes": path.stat().st_size}
                for path in fr_files
            ],
        },
        "desktop": {
            "file": {
                "name": desktop_file.name,
                "bytes": desktop_file.stat().st_size,
            },
        },
    }
    (site / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        "utf-8",
    )

    print("[5/5] Terminé.")
    print(f"      Site : {site}")
    print(
        f"      Test UI : {ui_probe.name} - "
        f"{ui_probe.stat().st_size / 1024:.1f} KiB / "
        f"{len(ui_probe.read_text('utf-8'))} caractères"
    )
    print(
        f"      Desktop : {desktop_file.name} - "
        f"{desktop_file.stat().st_size / 1024:.1f} KiB"
    )
    print(f"      Android : {len(fr_files)} partie(s)")
    for path in fr_files:
        print(f"      {path.name} : {path.stat().st_size / 1024:.1f} KiB")

if __name__ == "__main__":
    main()
