#!/usr/bin/env python3
"""
Build a Mihon-compatible extension repository from release APKs + source-info JSON.

Usage (from repo root, after assembleRelease):
  python3 scripts/generate-repo-index.py \
    --github-user YOUR_USER \
    --github-repo senpou \
    --branch repo

Writes into ./repo/:
  apk/*.apk
  icon/*.png
  index.min.json
  index.json   (simple list, same data as min)
  repo.json    (optional meta for newer clients)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
CONTENT_WARNING_NSFW = {2, 3}  # MIXED=2, NSFW=3 in our ordinal+1 mapping; treat both as nsfw flag for classic index


def find_aapt() -> Path:
    env_sdk = (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "").strip()
    candidates = []
    if env_sdk:
        candidates.append(Path(env_sdk))
    candidates.append(Path.home() / "Android" / "Sdk")
    candidates.append(Path("/opt/android-sdk"))

    android_home = next((p for p in candidates if (p / "build-tools").is_dir()), None)
    if android_home is None:
        raise SystemExit("Android SDK with build-tools not found (set ANDROID_HOME)")

    build_tools = sorted((android_home / "build-tools").iterdir())
    if not build_tools:
        raise SystemExit("No build-tools in Android SDK")
    aapt = build_tools[-1] / "aapt"
    if not aapt.is_file():
        raise SystemExit(f"aapt not found at {aapt}")
    return aapt


def collect_artifacts(root: Path) -> list[tuple[Path, Path]]:
    """Return list of (source_info_json, apk_path)."""
    pairs: list[tuple[Path, Path]] = []
    for info in root.glob("src/**/build/keiyoushi-source-info.json"):
        apk_dir = info.parent / "outputs" / "apk" / "release"
        apks = list(apk_dir.glob("*.apk"))
        if not apks:
            print(f"warn: no release apk for {info}", file=sys.stderr)
            continue
        pairs.append((info, apks[0]))
    return pairs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-user", required=True, help="GitHub username or org")
    parser.add_argument("--github-repo", default="senpou", help="Repository name")
    parser.add_argument("--branch", default="repo", help="Branch that hosts APKs/index")
    parser.add_argument(
        "--base-url",
        default=None,
        help="Override base URL for apk/icon (default: raw.githubusercontent.com/...)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("repo"),
        help="Output directory (default: ./repo)",
    )
    parser.add_argument(
        "--signing-key-fingerprint",
        default="",
        help="SHA-256 fingerprint hex of the APK signing key (no colons), for repo.json meta",
    )
    args = parser.parse_args()

    root = Path.cwd()
    out: Path = args.out
    apk_dir = out / "apk"
    icon_dir = out / "icon"
    apk_dir.mkdir(parents=True, exist_ok=True)
    icon_dir.mkdir(parents=True, exist_ok=True)

    if args.base_url:
        base = args.base_url.rstrip("/")
    else:
        base = (
            f"https://raw.githubusercontent.com/{args.github_user}/"
            f"{args.github_repo}/{args.branch}"
        )

    aapt = find_aapt()
    pairs = collect_artifacts(root)
    if not pairs:
        raise SystemExit(
            "No release artifacts found. Run:\n"
            "  ./gradlew assembleRelease\n"
            "with signing env vars set."
        )

    index: list[dict] = []

    for info_path, apk_path in sorted(pairs, key=lambda p: p[0].as_posix()):
        info = json.loads(info_path.read_text(encoding="utf-8"))
        pkg = info["packageName"]
        version_name = info["versionName"]
        version_code = int(info["versionCode"])
        ext_name = info["name"]
        content_warning = int(info.get("contentWarning", 1))
        sources = info.get("sources") or []

        # Normalize apk file name: drop -release suffix if present
        apk_name = apk_path.name.replace("-release.apk", ".apk")
        if not apk_name.endswith(".apk"):
            apk_name = f"{apk_name}.apk"
        dest_apk = apk_dir / apk_name
        shutil.copy2(apk_path, dest_apk)

        # Icon via aapt
        badging = subprocess.check_output(
            [str(aapt), "dump", "--include-meta-data", "badging", str(apk_path)],
            text=True,
        )
        icon_match = APPLICATION_ICON_320_REGEX.search(badging)
        if not icon_match:
            raise SystemExit(f"No application-icon-320 in {apk_path}")
        icon_entry = icon_match.group(1)
        icon_name = f"{pkg}.png"
        with ZipFile(apk_path) as zf, zf.open(icon_entry) as src, (icon_dir / icon_name).open("wb") as dst:
            dst.write(src.read())

        lang = sources[0]["lang"] if sources else "all"
        nsfw = 1 if content_warning in CONTENT_WARNING_NSFW or content_warning == 3 else 0
        # MIXED (2) often still nsfw-ish in catalogs; keep 1 for MIXED and NSFW for safety toggles
        if content_warning >= 2:
            nsfw = 1

        index.append(
            {
                "name": f"Senpou: {ext_name}" if not ext_name.startswith("Senpou") else ext_name,
                "pkg": pkg,
                "apk": apk_name,
                "lang": lang,
                "code": version_code,
                "version": version_name,
                "nsfw": nsfw,
                "sources": [
                    {
                        "name": s["name"],
                        "lang": s["lang"],
                        "id": str(s["id"]),
                        "baseUrl": s["baseUrl"],
                    }
                    for s in sources
                ],
            }
        )
        print(f"  + {pkg} v{version_name} -> {apk_name}")

    index.sort(key=lambda e: e["pkg"])

    (out / "index.min.json").write_text(
        json.dumps(index, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    (out / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")

    repo_meta = {
        "name": "Senpou",
        "website": f"https://github.com/{args.github_user}/{args.github_repo}",
        "signingKeyFingerprint": args.signing_key_fingerprint,
        "apkBaseUrl": f"{base}/apk",
        "index": f"{base}/index.min.json",
    }
    (out / "repo.json").write_text(json.dumps(repo_meta, indent=2) + "\n", encoding="utf-8")

    # Small helper page
    (out / "README.md").write_text(
        f"""# Senpou extension repo

Add this URL in Mihon → Browse → Extensions → Repositories:

```
{base}/index.min.json
```

Then install **Senpou *** extensions from the list.
""",
        encoding="utf-8",
    )

    print()
    print("Repo generated at:", out.resolve())
    print("Mihon repository URL:")
    print(f"  {base}/index.min.json")


if __name__ == "__main__":
    main()
