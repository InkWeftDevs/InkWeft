# Captured local callback equality regression

The standalone reproductions use the project compiler Kotlin 2.3.10 and Compose runtime 1.10.5. Both compiled and executed successfully; exact commands, outputs and dependency hashes are in `result.json`. No dependency JAR is included.

`RefRepro.kt` shows that two local callable-reference objects with different captured projections compare equal despite different identity. Capturing lambdas compare unequal. `RuntimeRepro.kt` executes the actual Compose `mutableStateOf`/structural policy: replacing the reference retains the old `null` projection result, while replacing the lambda produces the current `target`.

Old `StudyWorkspaceKt$StudyContent$moveOutlineDrag$2$1` extends `FunctionReferenceImpl`, captures `$projection`/`$cardById`, and does not override equality. `rememberUpdatedState` uses the default structural policy. The outline was mounted before the cross-screen fixture appended 28 targets; a stale projection could therefore show a current hover through the edge coroutine but clear it synchronously on pointer-up through the old callable reference.

Keep explicit capturing lambdas for `moveOutlineDrag` and `latestWorkMode`. The latter also captures the current node map and map key; its native regression now adds a branch after mounting, adds a different-branch decoy question, and requires exactly one question from the selected new branch.

The temporary outline observer is removed. Its own former local callable reference had the same stale-capture limitation, so its old `tab=0` observation was not proof of a real tab switch. The bounded test evidence file remains synthetic-only. JVM mechanism evidence and individual source-bound Android results are kept separate; final same-candidate acceptance remains required.

For the recorded compiler commands, set `GRADLE_USER_HOME`, `JAVA_HOME`, `ANDROID_HOME`, and `REPRO_DIR` to your matching dependency cache, JDK 17, Android SDK, and this reproduction directory. Verify the recorded hashes before execution.
