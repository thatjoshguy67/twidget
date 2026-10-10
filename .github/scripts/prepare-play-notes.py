"""Convert Play Console language blocks into upload action release notes."""

import argparse
from pathlib import Path
import re


def parse_notes(text):
    pattern = re.compile(r"<([a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)>\s*\n(.*?)\n</\1>", re.S)
    notes = {}
    for match in pattern.finditer(text):
        locale, body = match.group(1), match.group(2).strip()
        if locale in notes:
            raise ValueError(f"Duplicate locale: {locale}")
        if not body or len(body) > 500:
            raise ValueError(f"{locale}: notes must contain 1–500 Unicode characters")
        notes[locale] = body
    if not notes or pattern.sub("", text).strip():
        raise ValueError("Expected only Play Console language blocks, e.g. <en-GB>…</en-GB>")
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    notes = parse_notes(args.source.read_text(encoding="utf-8"))
    args.destination.mkdir(parents=True, exist_ok=True)
    for locale, body in notes.items():
        (args.destination / f"whatsnew-{locale}").write_text(body, encoding="utf-8")
    print(f"Prepared Play notes for {', '.join(notes)}")


if __name__ == "__main__":
    main()
