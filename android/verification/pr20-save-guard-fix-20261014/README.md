# Invalid UUIDs in new save-boundary guard fixtures

Run `38540498244` stopped at new method 8 after seven existing correctness guards passed. Its official log confirms JUnit FAIL, adb exit 0, natural process exit and successful cleanup. The failed method's original JUnit stack was not forwarded before the controller stopped; that stack is unavailable here. Methods 9 and 10 did not run. There are no 90-save observations to analyze.

Independent source inspection found a definite fixture error: `SaveNote("first","author",...)` and subsequent named-string command IDs violate `SaveNote.init`, which calls `UUID.fromString` for both IDs. If the preceding warmup finishes, the old first constructor necessarily throws before the first measured save. This is not a claim that the inaccessible original stack has been recovered or that no later guard assertion can fail.

The minimal patch changes only IDs and corresponding author-read references inside the two new guard methods. It uses ten distinct fixed legal command UUIDs and three disjoint fixed notebook UUIDs; replay still reuses the same command object and conflict/cancellation/recovery revisions are unchanged. It consumes no random values. The entire source suffix from `assertOuterPair` through the original continuous DIRECT/paused-save/OBSERVE scenario is byte-identical to the failed-run source. Production hooks, seeded fixture, thresholds and frozen analyzer are untouched. `binding.json` records both source hashes and this suffix check.

`ValidateGuardIds.java` extracts the constructors/copy command IDs from the actual edited two guard bodies and calls the existing compiled **core SaveNote constructor** on host JVM. It covers all ten commands and three notebook identities, checks distinctness, and reproduces rejection of the old invalid command and old invalid note ID separately. `constructor-proof.log` is the exact successful output. This verifies the model-construction contract only; it is not Room or Android execution.

The root agent owns compilation, independent review, commit and any decision about a new bounded run. No run was retried and no artifact-storage route was used.

The final incremental `:data-local:compileDebugAndroidTestKotlin` compilation passed in 107.791 seconds with all nine recorded source hashes unchanged. It includes the separate new GC diagnostic test in the local tree. The compilation performs no Room/native execution, and neither the constructor proof nor compilation establishes that the repaired Android guard passes. Exact start/result/log bytes are included.
