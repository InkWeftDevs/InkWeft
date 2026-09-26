"""Conservative change-to-check mapping; unknown executable changes fall back to full."""
from functools import cache
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]

@cache
def inventory(module):
    result = {}
    for path in (ROOT / "android" / module / "src/androidTest").rglob("*Test.kt"):
        text = path.read_text(encoding="utf-8")
        count = len(re.findall(r"@Test\b", text))
        if count:
            package = re.search(r"^package\s+(\S+)", text, re.M).group(1)
            result[f"{package}.{path.stem}"] = count
    return result

def plan(paths, full=False):
    core = lint = full
    app = set(inventory("app")) if full else set()
    room = set(inventory("data-local")) if full else set()
    reasons = []
    for path in paths:
        p = Path(path)
        if p.suffix.lower() in {".md", ".txt", ".png", ".jpg", ".svg"} and "/src/" not in path:
            continue
        if path.startswith("ci/test_") and p.suffix == ".py":
            continue
        if path.startswith("android/core-domain/src/test/"):
            core = True
        elif "/src/androidTest/" in path and p.suffix == ".kt":
            module = path.split("/")[1]
            candidates = inventory(module)
            found = {c for c in candidates if c.endswith("." + p.stem)}
            # Helpers and removed tests affect their entire module.
            (app if module == "app" else room).update(found or candidates)
        elif path.startswith("android/app/src/main/"):
            lint = True
            app.update(inventory("app"))
        elif path.startswith("android/core-domain/") or path.startswith("android/data-local/"):
            core = lint = True
            room.update(inventory("data-local")); app.update(inventory("app"))
        else:
            # Build scripts, workflow changes and unclassified executable files.
            core = lint = True
            room.update(inventory("data-local")); app.update(inventory("app"))
        reasons.append(path)
    return {"core": core, "lint": lint, "app": sorted(app), "room": sorted(room),
            "build": bool(core or lint or app or room), "device": bool(app or room),
            "expected_app": sum(inventory("app")[c] for c in app),
            "expected_room": sum(inventory("data-local")[c] for c in room),
            "changed": paths, "reasons": reasons, "forced_full": full}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True); parser.add_argument("--head", default="HEAD")
    parser.add_argument("--full", action="store_true"); parser.add_argument("--output", default="android-plan.json")
    args = parser.parse_args()
    files = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", args.base, args.head], cwd=ROOT, text=True).splitlines()
    result = plan(files, args.full)
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
