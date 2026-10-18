"""Check Android translations or export a UTF-8 CSV for translators. No network/API."""
import argparse
import collections
import csv
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
LOCALES = {"en": "values-en", "ru": "values-ru", "ko": "values-ko", "zh-Hans": "values-b+zh+Hans",
           "ja": "values-ja", "de": "values-de", "fr": "values-fr", "pt-BR": "values-pt-rBR", "es": "values-es"}
REVISIONS = ROOT / "localization/revisions.json"


def strings(folder):
    result = {}
    for path in sorted((RES / folder).glob("*.xml")):
        for node in ET.parse(path).getroot():
            if node.tag == "string" and node.get("translatable") != "false":
                key = node.attrib["name"]
                if key in result:
                    raise ValueError(f"Duplicate resource: {folder}/{key}")
                result[key] = "".join(node.itertext())
    return result


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def placeholders(value):
    return collections.Counter(re.findall(r"(?<!%)%(?:\d+\$)?[sdf]", value))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, help="Export all keys, Italian and translated texts; blanks are pending.")
    parser.add_argument("--record", action="store_true", help="Record revisions AFTER reviewing changed translations; never run blindly.")
    args = parser.parse_args()
    source = strings("values")
    translations = {locale: strings(folder) for locale, folder in LOCALES.items()}
    errors = []
    previous = json.loads(REVISIONS.read_text(encoding="utf-8")) if REVISIONS.exists() else {}
    if not args.record and source.keys() != previous.keys():
        errors.append("Update reviewed translation revisions for new/removed keys with --record after review")
    changed = {key for key, value in source.items() if previous and previous.get(key, {}).get("source") != digest(value)}
    for locale, texts in translations.items():
        missing = source.keys() - texts.keys()
        print(f"{locale}: {len(source.keys() & texts.keys())}/{len(source)} translated; {len(missing)} pending")
        if missing:
            errors.append(f"Missing translations: {locale}/{sorted(missing)}")
        if changed & missing:
            errors.append(f"New/changed copy must be translated: {locale}/{sorted(changed & missing)}")
        for key, text in texts.items():
            if key not in source:
                errors.append(f"Obsolete key: {locale}/{key}")
                continue
            if placeholders(text) != placeholders(source[key]):
                errors.append(f"Placeholder mismatch: {locale}/{key}")
            old = previous.get(key, {})
            if not args.record and (old.get("source") != digest(source[key]) or old.get(locale) != digest(text)):
                errors.append(f"Unrecorded translation review: {locale}/{key}")
            if not args.record and old.get("source") != digest(source[key]) and old.get(locale) == digest(text):
                errors.append(f"Source changed; review translation: {locale}/{key}")
    if args.export:
        args.export.parent.mkdir(parents=True, exist_ok=True)
        with args.export.open("w", encoding="utf-8-sig", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(["key", "it", *LOCALES])
            for key in sorted(source):
                writer.writerow([key, source[key], *(texts.get(key, "") for texts in translations.values())])
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if args.record:
        REVISIONS.parent.mkdir(parents=True, exist_ok=True)
        rows = {key: {"source": digest(value), **{locale: digest(texts[key]) for locale, texts in translations.items() if key in texts}}
                for key, value in sorted(source.items())}
        REVISIONS.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
