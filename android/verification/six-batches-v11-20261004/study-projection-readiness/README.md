# Study projection readiness: protocol evidence

Preserved completed experiments; packaging did not rerun them.

## Boundary

The source copies the relevant `combine(mapId, reload)` → `flatMapLatest` →
`catch`/`collect` sequence and compares publication orders and current-key guards.
Readers and graphs are synthetic. It does **not** instantiate production
`StudyViewModel`, Room, Compose, or Android Main, and does not prove the cause of
either Android CI failure or a passing product/device regression.

Immediate and real-IO cases use `Dispatchers.Unconfined`; IO returns a synthetic
graph. Stale-result cases use a manually stepped dispatcher, without suspending
the final collector. Each IO variant runs exactly 1,000 iterations, graph waits
are capped at five seconds, and manual queue drains have bounded step checks.

## Recorded results

Latest `compile.exit` and `run.exit` are both `0`; empty `compile.stdout` means no
compiler output. `ALL_PROTOCOL_ASSERTIONS_PASS` means the script confirmed
expected defective behavior in original variants and protected behavior in
corrected variants, not that the original protocol was correct.

- Key-first permits immediate new-graph delivery to be overwritten by trailing
  `loading=true, nodes=[]`; loading-first prevents this
- An external synchronous key observer sees the old loaded graph under the new
  key with key-first; loading-first marks that transition as loading
- Latest fixed IO run: original order **22/1,000** loading remnants; loading-first
  **0/1,000**, copied from `run.stdout`
- Buffered old success can clear new loading and publish old nodes; a graph-key
  guard rejects it. An old failure queued before cancellation can also clear
  loading and set `readFailed`; a reader-key guard rejects it. The error case is
  an old-flow catch race, not a buffered downstream exception
- Both guarded stale-result cases subsequently accept the correct new graph

`history/v1/` preserves the earlier source/stdout only. It lacked the external-key
observer and recorded **14/1,000** versus **0/1,000**. The latest suite added that
case and ran once again. IO counts are scheduler-dependent observations, not
production failure-rate estimates; runs were not repeated until a target count.

## Reproduce using existing tools

Use an existing JDK and library directory containing compiler-embeddable 2.3.0
with its dependencies, stdlib 2.3.0, and coroutines-core-jvm 1.10.2. The original
classpath was an existing Gradle 9.4.1 distribution's `lib`; Gradle was not run.
Versions and recorded dependency hashes are in `versions.txt`. No downloads,
installation, network, or Android device are needed.

From this directory, write new outputs elsewhere:

```sh
export JDK_HOME="/path/to/existing/jdk"
export TOOLCHAIN_LIB="/path/to/existing/compiler-libraries"
OUT="$(mktemp -d)"
CP="$TOOLCHAIN_LIB/kotlin-stdlib-2.3.0.jar:$TOOLCHAIN_LIB/kotlinx-coroutines-core-jvm-1.10.2.jar"
"$JDK_HOME/bin/java" -cp "$TOOLCHAIN_LIB/*" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$CP" -d "$OUT/protocol.jar" StudyReadinessProtocol.kt \
  > "$OUT/compile.stdout" 2>&1
status=$?
printf '%s\n' "$status" > "$OUT/compile.exit"
if [ "$status" -eq 0 ]; then
  "$JDK_HOME/bin/java" -cp "$OUT/protocol.jar:$CP" \
    StudyReadinessProtocolKt > "$OUT/run.stdout" 2>&1
  printf '%s\n' "$?" > "$OUT/run.exit"
fi
printf 'New outputs: %s\n' "$OUT"
```

`SHA256SUMS` verifies all preserved text files using relative paths. No jar/class
files or private workspace paths are included.
