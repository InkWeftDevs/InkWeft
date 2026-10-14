from pathlib import Path
import datetime, hashlib, json, subprocess, time

root = Path('/workspace/InkWeft')
out = Path('/workspace/scratch/pr20-gc-author-only-20261014')
cache = Path('/workspace/.setup/gradle/caches')
jdk = Path('/workspace/.setup/tools/jdk-17.0.16+8/bin')
main_classes = root / 'android/data-local/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
test_classes = root / 'android/data-local/build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes'
source = root / 'android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest.kt'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

dependencies = [
    next(cache.rglob('kotlin-stdlib-2.3.10.jar')),
    next(cache.rglob('kotlinx-coroutines-core-jvm-1.10.2.jar')),
    next(cache.rglob('junit-4.13.2.jar')),
    next(cache.rglob('hamcrest-core-1.3.jar')),
    cache / '9.4.1/transforms/410a77131322234df44ecb9b10d91f65/transformed/sqlite-runtime.jar',
    Path('/workspace/.setup/tools/android-sdk/platforms/android-36/android.jar'),
]
class_files = list(test_classes.glob('org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest$Recorder*.class'))
class_files += list(test_classes.glob('org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest$Boundary*.class'))
class_files += list(main_classes.glob('org/inkweft/data/SaveBoundaryDiagnostics*.class'))
assert class_files and all(path.is_file() for path in dependencies)
classpath = ':'.join(str(path) for path in [out/'host-classes', test_classes, main_classes, *dependencies])
sources = sorted((out/'host-src').rglob('*.java'))
result = {
    'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'source_sha256_before': digest(source),
    'compiled_class_sha256': {str(path.relative_to(root)): digest(path) for path in class_files},
    'host_source_sha256': {str(path.relative_to(out)): digest(path) for path in sources},
    'dependency_sha256': {str(path): digest(path) for path in dependencies},
    'native_execution': False,
    'android_json_runtime_tested': False,
    'scope': 'Actual compiled Recorder and Boundary; host Debug and JSONArray argument capture stand-ins; no DB',
}
started = time.monotonic()
compile_result = subprocess.run([str(jdk/'javac'), '-cp', classpath, '-d', str(out/'host-classes'), *map(str,sources)], capture_output=True, text=True)
(out/'host-javac.log').write_text(compile_result.stdout + compile_result.stderr)
result['javac_exit_code'] = compile_result.returncode
if compile_result.returncode == 0:
    run_result = subprocess.run([str(jdk/'java'), '-cp', classpath, 'GcRecorderHostCheck'], capture_output=True, text=True, timeout=30)
    (out/'host-run.log').write_text(run_result.stdout + run_result.stderr)
    result['host_exit_code'] = run_result.returncode
    if run_result.returncode == 0:
        result['checks'] = json.loads(run_result.stdout)
result['elapsed_seconds'] = time.monotonic() - started
result['source_sha256_after'] = digest(source)
result['source_unchanged'] = result['source_sha256_before'] == result['source_sha256_after']
(out/'host-result.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
if compile_result.returncode:
    print(compile_result.stderr)
elif run_result.returncode:
    print(run_result.stderr)
