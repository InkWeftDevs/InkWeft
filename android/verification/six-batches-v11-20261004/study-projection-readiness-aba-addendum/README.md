# Addendum: same-key return (ABA) requires request ownership

One completed deterministic experiment, preserved without rerunning or replacing
the original evidence package.

## Boundary and comparison

Both variants publish loading first. The baseline already guards the current map
and observes `combine(mapId, reload).flatMapLatest`. The candidate reuses reload
as the sole request epoch: selection sets loading, updates mapId, then increments
reload; `reload.flatMapLatest` captures the map for that epoch, and success/error
publication checks both epoch and current map.

Readers/graphs are synthetic. A manually stepped dispatcher controls queue
interleavings; an additional Unconfined case checks immediate reads and external
key observers. This does not instantiate production StudyViewModel, Room,
Compose, or Android Main, establish a CI failure's cause, or run product tests.

## Recorded results

Compile/run exit codes are both 0. `ALL_ABA_PROTOCOL_ASSERTIONS_PASS` confirms the
expected baseline defects and candidate protections; it does not endorse the
baseline.

- Same-turn A→B→A conflates to A, starting no new baseline reader and leaving
  loading true/nodes empty with no queued work. Epoch observation starts A at r=2
- Buffered old A success passes the baseline map guard, clears loading and
  publishes old nodes. The epoch guard rejects r=0 while r=2 is loading
- Old A failure queued before cancellation similarly poisons baseline loading
  and readFailed; the epoch guard rejects it
- Both guarded stale-result cases later accept the new A graph and clear loading
- Immediate A→B→A epoch reads complete normally; external key observers see
  loading true and empty nodes during transitions

All queue interleavings have bounded step checks. No stochastic retry loop or new
IO-frequency measurement was used. The original 22/1,000 and historical
14/1,000 counts remain in the separate original package.

## Reproduce with existing tools

Use versions in `versions.txt` and the compiler's existing dependencies. No
installation, network, Gradle, or device is needed. Write new outputs elsewhere:

```sh
export JDK_HOME="/path/to/existing/jdk"
export TOOLCHAIN_LIB="/path/to/existing/compiler-libraries"
OUT="$(mktemp -d)"
CP="$TOOLCHAIN_LIB/kotlin-stdlib-2.3.0.jar:$TOOLCHAIN_LIB/kotlinx-coroutines-core-jvm-1.10.2.jar"
"$JDK_HOME/bin/java" -cp "$TOOLCHAIN_LIB/*" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$CP" -d "$OUT/protocol.jar" StudyReadinessAbaProtocol.kt \
  > "$OUT/compile.stdout" 2>&1
status=$?
printf '%s\n' "$status" > "$OUT/compile.exit"
if [ "$status" -eq 0 ]; then
  "$JDK_HOME/bin/java" -cp "$OUT/protocol.jar:$CP" \
    StudyReadinessAbaProtocolKt > "$OUT/run.stdout" 2>&1
  printf '%s\n' "$?" > "$OUT/run.exit"
fi
printf 'New outputs: %s\n' "$OUT"
```

Relative-path SHA256SUMS covers preserved text files. No jar/class files or
private workspace paths are included.
