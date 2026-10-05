# Coroutine job-registration verification

This is synthetic engineering evidence. It contains no user notes, device data, application database, or credentials.

## Recorded run

One local rerun on 2026-10-05 UTC, using existing tools; exact capture time, tool output, source/log SHA-256 and exit codes are in `results.json`.

- Real IO-dispatcher reproduction: default-start job completed before registration and left `busy=true` in **1,137 of 20,000** iterations. LAZY creation, registration, then `start()` produced **0 of 20,000** stranded gates. Compile/run exit: **0/0**.
- Registration protocol tests: **3 tests passed**. Compile/run exit: **0/0**.
- DEFAULT-start mutation: **3 tests, 1 failure**, in `immediateSuccessAndCompletedFailureBothReleaseTheirOwnGate`. Compile/run exit: **0/1**; this failure is expected.
- Tools: Eclipse Adoptium JDK **17.0.20.1+1**, Kotlin JVM compiler/stdlib **2.3.0**, kotlinx-coroutines core/test **1.10.2**, JUnit **4.13.2**, Hamcrest **1.3**.

The IO count depends on scheduling and is not a stable failure rate. This saved rerun stands on its own; earlier unsaved console output is not used as its evidence.

## Scope and limits

`Race.kt` uses `UnconfinedTestDispatcher` and real `withContext(Dispatchers.IO) { "" }`. A dispatcher change need not suspend the caller if the dispatched work completes before the suspension decision. The fixture checks whether cleanup runs before the outer variable receives the job.

The three deterministic tests cover synchronous success/already-completed failure, completion after suspension, and an older completed or cancelled task not unlocking its replacement. These tests exercise a small copy of the registration protocol. They do not execute the production callers or establish the exact cause of the failed UI run.

**Not executed:** Room queries, Compose, an Android device, the actual excerpt/continuous-writing flow, Gradle, or product compilation. The relationship to the observed CI `navigationReady` timeout remains **unconfirmed** because that failure did not capture the `capturingExcerpt` flag. The normal Android UI dispatcher and the test dispatcher also have different launch behavior. Scope cancellation before the coroutine body begins is outside these checks.

## Sources and provenance

- `sources/Race.kt`: unchanged minimal IO reproduction source used for the earlier investigation.
- `sources/CaptureJobRegistrationTest.kt`: exact test source from commit `26feb48b178dee59fac73c812fc14779c4c976a9`, originally at `android/app/src/androidTest/java/org/inkweft/app/CaptureJobRegistrationTest.kt`.
- `sources/DefaultCaptureJobRegistrationTest.kt`: identical bytes except the single `start=CoroutineStart.LAZY` becomes `start=CoroutineStart.DEFAULT`. It keeps the same test class and assertions. The script verifies this correspondence before compiling.
- Production fix: `5ccb3ee2d6208b9f2363932ea6deeca434dce0ff`, based on `6bc691bed94557812dc94132863b07d5b4d87eda`. Two callers register their LAZY jobs before starting them; identity-checked cleanup is retained.

`logs/*-compile.log` and `logs/*-run.log` contain merged stdout/stderr as captured, including the expected mutation failure. Empty compile logs mean the compiler emitted no output. Adjacent `.exit` files hold actual process exit codes. `SHA256SUMS` covers the published package; `results.json` separately hashes the recorded source, script, logs and dependency jars.

## Reproduce with already-installed tools

Set these variables to your existing tools. `KOTLIN_COMPILER_CP` must include the compiler and its runtime dependencies; it may be a directory wildcard. No command downloads or installs anything.

```sh
export JAVA_HOME='<existing-jdk-directory>'
export KOTLIN_COMPILER_CP='<existing-kotlin-compiler-and-dependencies-classpath>'
export KOTLIN_STDLIB_JAR='<existing-kotlin-stdlib-2.3.0.jar>'
export COROUTINES_CORE_JAR='<existing-kotlinx-coroutines-core-jvm-1.10.2.jar>'
export COROUTINES_TEST_JAR='<existing-kotlinx-coroutines-test-jvm-1.10.2.jar>'
export JUNIT_JAR='<existing-junit-4.13.2.jar>'
export HAMCREST_JAR='<existing-hamcrest-core-1.3.jar>'
export OUT_DIR='<new-output-directory>'
bash run.sh
```

The script executes each check once, creates fresh classes/logs/results under `OUT_DIR`, and refuses to overwrite an existing output directory. It expects the mutation to fail and returns success only when the recorded exit codes and test outcomes match that expectation. Python 3 is used only for source/hash checks and result formatting. Running this script is not an Android integration test.
