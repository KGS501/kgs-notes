#!/usr/bin/env python3
"""Regenerate the open-source notices bundled in the app.

Writes app/src/main/assets/licenses/:
  gpl-3.0.txt     KGS Notes' own license, copied from LICENSE
  apache-2.0.txt  the license of the Android libraries (from --apache-license)
  editor-*.txt    the license texts of every production package bundled into the editor

Run after `pnpm install` in editor/web whenever editor dependencies change. The script fails
when a bundled package is not assigned to a notice group, so new dependencies can't be missed.
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "editor" / "web"
OUT = ROOT / "app" / "src" / "main" / "assets" / "licenses"

# Notice files shown in Settings, keyed by the packages they cover.
GROUPS = {
    "editor-tiptap.txt": lambda name: name.startswith("@tiptap/"),
    "editor-prosemirror.txt": lambda name: name.startswith("prosemirror-")
    or name in {"orderedmap", "rope-sequence", "w3c-keyname"},
    "editor-marked.txt": lambda name: name == "marked",
    "editor-linkify.txt": lambda name: name == "linkifyjs",
}


def resolve(name: str, from_dir: Path) -> Path:
    """Node-style lookup: the nearest node_modules/<name> above from_dir."""
    for directory in [from_dir, *from_dir.parents]:
        candidate = directory / "node_modules" / name
        if (candidate / "package.json").exists():
            return candidate.resolve()
    raise SystemExit(f"Cannot resolve {name} from {from_dir}; run pnpm install in editor/web")


def production_packages() -> dict[str, Path]:
    manifest = json.loads((WEB / "package.json").read_text())
    pending = [(name, WEB) for name in manifest.get("dependencies", {})]
    found: dict[str, Path] = {}
    while pending:
        name, from_dir = pending.pop()
        path = resolve(name, from_dir)
        if name in found:
            continue
        found[name] = path
        package = json.loads((path / "package.json").read_text())
        pending += [(dependency, path) for dependency in package.get("dependencies", {})]
    return found


def license_text(path: Path) -> str:
    for candidate in sorted(path.iterdir()):
        if candidate.name.lower().startswith(("license", "licence")):
            return candidate.read_text().replace("\r\n", "\n").strip()
    raise SystemExit(f"No license file in {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apache-license", type=Path, default=Path("/usr/share/common-licenses/Apache-2.0"),
                        help="Canonical Apache License 2.0 text")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", OUT / "gpl-3.0.txt")
    shutil.copyfile(args.apache_license, OUT / "apache-2.0.txt")

    grouped: dict[str, dict[str, list[str]]] = {group: {} for group in GROUPS}
    for name, path in sorted(production_packages().items()):
        package = json.loads((path / "package.json").read_text())
        group = next((group for group, matches in GROUPS.items() if matches(name)), None)
        if group is None:
            sys.exit(f"{name} is bundled into the editor but has no notice group in {Path(__file__).name}")
        label = f"{name} {package['version']} ({package.get('license', 'unknown license')})"
        grouped[group].setdefault(license_text(path), []).append(label)

    for group, texts in grouped.items():
        # Packages sharing an identical license text (same holder and terms) are listed together.
        sections = ["\n".join(labels) + "\n\n" + text for text, labels in texts.items()]
        (OUT / group).write_text("\n\n\n".join(sections) + "\n")
        print(f"{group}: {sum(len(labels) for labels in texts.values())} packages, {len(texts)} license texts")


if __name__ == "__main__":
    main()
