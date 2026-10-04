"""Reference a real Room run only when its complete scoped inputs still match."""
import hashlib
import json
from pathlib import Path

BASELINE = "android/verification/six-batches-v11-20261004/room-reuse-9593b92.json"
# Full source trees include main, debug/androidTestDebug overlays and future source sets.
# Include tests and shared build/dependency tooling, not only production Kotlin.
INPUT_PATHS = (
    "android/core-domain/src", "android/data-local/src", "android/data-local/schemas",
    "android/core-domain/build.gradle.kts", "android/data-local/build.gradle.kts",
    "android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle.properties",
    "android/gradle", "android/gradlew", "android/gradlew.bat",
    "android/buildSrc", "android/build-logic", "android/gradle.lockfile",
    "android/core-domain/gradle.lockfile", "android/data-local/gradle.lockfile",
)


def input_hashes(root):
    """Hash ordered relative names and file bytes, including additions/deletions."""
    hashes = {}
    for name in INPUT_PATHS:
        path = root / name
        if path.is_symlink():
            raise ValueError("Room evidence inputs cannot be symlinks: " + str(path))
        if not path.exists():
            hashes[name] = None
            continue
        files = sorted(path.rglob("*")) if path.is_dir() else [path]
        digest = hashlib.sha256()
        for file in files:
            if file.is_symlink():
                raise ValueError("Room evidence inputs cannot be symlinks: " + str(file))
            if file.is_file():
                digest.update(file.relative_to(root).as_posix().encode() + b"\0")
                digest.update(hashlib.sha256(file.read_bytes()).digest())
        hashes[name] = digest.hexdigest()
    return hashes


def room_evidence(root, mode, expected, classes):
    result = {"status": "RUN_REQUIRED", "baseline": BASELINE}
    if mode != "focused":
        return dict(result, reason="Full mode always executes Room")
    try:
        baseline = json.loads((root / BASELINE).read_text())
        receipt_path = root / baseline["receipt"]
        receipt_bytes = receipt_path.read_bytes()
        receipt = json.loads(receipt_bytes)
        current = input_hashes(root)
        result["input_sha256"] = current
        if (hashlib.sha256(receipt_bytes).hexdigest() != baseline["receipt_sha256"]
                or receipt["expected"] != expected or receipt["passed"] != expected
                or receipt["planned_total"] != expected
                or receipt["failures"] != [] or receipt["infrastructure"] != []):
            return dict(result, reason="Original Room evidence is missing, changed, or not a complete pass")
        if current != baseline["input_sha256"] or list(classes) != baseline["room_classes"]:
            return dict(result, reason="Room source/test/build inputs or selected classes differ from the actual run")
        return dict(result, status="REUSED_EVIDENCE", source_commit=baseline["source_commit"],
                    source_run=baseline["source_run"], source_url=baseline["source_url"],
                    receipt=baseline["receipt"], receipt_sha256=baseline["receipt_sha256"],
                    reused_passed=receipt["passed"], reason="Exact input content match; no Room tests executed in this run")
    except (OSError, ValueError, KeyError, TypeError) as error:
        return dict(result, reason="Room evidence cannot be verified: " + str(error))
