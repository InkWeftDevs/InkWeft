#!/usr/bin/env bash
# Uses existing local tools only. No Gradle, downloads, Android, or application build.
set -euo pipefail
: "${JAVA_HOME:?Set the existing JDK directory}"
: "${KOTLIN_COMPILER_CP:?Set the existing Kotlin compiler and its dependency classpath}"
: "${KOTLIN_STDLIB_JAR:?}"
: "${COROUTINES_CORE_JAR:?}"
: "${COROUTINES_TEST_JAR:?}"
: "${JUNIT_JAR:?}"
: "${HAMCREST_JAR:?}"
: "${OUT_DIR:?Set a new output directory; existing evidence is never overwritten}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [[ -e "$OUT_DIR" ]]; then echo 'OUT_DIR already exists; choose a fresh directory' >&2; exit 2; fi
mkdir -p "$OUT_DIR/logs"
export ROOT OUT_DIR
JAVA="$JAVA_HOME/bin/java"
BASE_CP="$KOTLIN_STDLIB_JAR:$COROUTINES_CORE_JAR"
TEST_CP="$BASE_CP:$JUNIT_JAR:$HAMCREST_JAR"
# Check the recorded mutant changes exactly the coroutine start policy, not its assertions.
python3 - <<'PY'
import os
from pathlib import Path
root = Path(os.environ['ROOT']) / 'sources'
original = (root / 'CaptureJobRegistrationTest.kt').read_bytes()
assert original.count(b'start=CoroutineStart.LAZY') == 1
assert (root / 'DefaultCaptureJobRegistrationTest.kt').read_bytes() == original.replace(b'start=CoroutineStart.LAZY', b'start=CoroutineStart.DEFAULT')
PY
"$JAVA" -version > "$OUT_DIR/logs/java-version.log" 2>&1
"$JAVA" -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -version > "$OUT_DIR/logs/kotlin-version.log" 2>&1
run_check() {
    local name=$1 source=$2 classpath=$3 main=$4
    shift 4
    local compiled=0 executed=0
    "$JAVA" -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -classpath "$classpath" -d "$OUT_DIR/$name-classes" "$source" \
        > "$OUT_DIR/logs/$name-compile.log" 2>&1 || compiled=$?
    printf '%s\n' "$compiled" > "$OUT_DIR/logs/$name-compile.exit"
    if [[ "$compiled" == 0 ]]; then
        "$JAVA" -cp "$OUT_DIR/$name-classes:$classpath" "$main" "$@" \
            > "$OUT_DIR/logs/$name-run.log" 2>&1 || executed=$?
        printf '%s\n' "$executed" > "$OUT_DIR/logs/$name-run.exit"
    else
        printf 'NOT_RUN\n' > "$OUT_DIR/logs/$name-run.exit"
    fi
}
run_check io "$ROOT/sources/Race.kt" "$BASE_CP:$COROUTINES_TEST_JAR" RaceKt
run_check protocol "$ROOT/sources/CaptureJobRegistrationTest.kt" "$TEST_CP" org.junit.runner.JUnitCore org.inkweft.app.CaptureJobRegistrationTest
run_check default-mutant "$ROOT/sources/DefaultCaptureJobRegistrationTest.kt" "$TEST_CP" org.junit.runner.JUnitCore org.inkweft.app.CaptureJobRegistrationTest
python3 - <<'PY'
import datetime, hashlib, json, os, re
from pathlib import Path
root, out = Path(os.environ['ROOT']), Path(os.environ['OUT_DIR'])
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
checks = {}
for name in ('io', 'protocol', 'default-mutant'):
    checks[name] = {kind + '_exit': (out / 'logs' / f'{name}-{kind}.exit').read_text().strip() for kind in ('compile', 'run')}
io = (out / 'logs/io-run.log').read_text() if (out / 'logs/io-run.log').exists() else ''
checks['io']['observations'] = [{
    'lazy': lazy == 'true', 'synchronous_before_assignment': int(sync),
    'stranded_busy': int(busy), 'iterations': int(total),
} for lazy, sync, busy, total in re.findall(r'lazy=(false|true) synchronous_before_assignment=(\d+) stranded_busy=(\d+) / (\d+)', io)]
result = {
    'captured_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'scope': 'Standalone coroutine protocol only; real IO dispatcher returning an empty string. No Room, Compose, Android device, or production capture execution.',
    'source_test_commit': '26feb48b178dee59fac73c812fc14779c4c976a9',
    'production_fix_commit': '5ccb3ee2d6208b9f2363932ea6deeca434dce0ff',
    'production_base_commit': '6bc691bed94557812dc94132863b07d5b4d87eda',
    'ci_failure_causality': 'UNCONFIRMED: no capturingExcerpt flag was captured in the failing CI run.',
    'checks': checks,
    'source_sha256': {str(p.relative_to(root)): sha(p) for p in sorted((root / 'sources').glob('*.kt'))},
    'script_sha256': sha(root / 'run.sh'),
    'logs_sha256': {str(p.relative_to(out)): sha(p) for p in sorted((out / 'logs').iterdir())},
    'tools': {
        'java_version_output': (out / 'logs/java-version.log').read_text().strip(),
        'kotlin_version_output': (out / 'logs/kotlin-version.log').read_text().strip(),
        'jars': {key: {'name': Path(os.environ[key]).name, 'sha256': sha(Path(os.environ[key]))}
                 for key in ('KOTLIN_STDLIB_JAR','COROUTINES_CORE_JAR','COROUTINES_TEST_JAR','JUNIT_JAR','HAMCREST_JAR')},
    },
}
(out / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
# Mutant failure is expected; its original assertion remains unchanged.
assert all(c['compile_exit'] == '0' for c in checks.values()), checks
assert checks['io']['run_exit'] == '0' and checks['protocol']['run_exit'] == '0', checks
assert checks['default-mutant']['run_exit'] == '1', checks
assert 'OK (3 tests)' in (out / 'logs/protocol-run.log').read_text()
assert 'Tests run: 3,  Failures: 1' in (out / 'logs/default-mutant-run.log').read_text()
assert 'immediateSuccessAndCompletedFailureBothReleaseTheirOwnGate' in (out / 'logs/default-mutant-run.log').read_text()
assert len(checks['io']['observations']) == 2 and checks['io']['observations'][1]['stranded_busy'] == 0, checks
print(json.dumps(checks, ensure_ascii=False, indent=2))
PY
