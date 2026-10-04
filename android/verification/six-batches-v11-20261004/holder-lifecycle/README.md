# AndroidView snapshot-owner lifecycle evidence

The full run on 8d6abf5 hit a real null Handler in Compose 1.10.5 AndroidViewHolder's queued update callback after nested map return. The standalone reproduction exercises the same runtime snapshot observer, without copying its implementation or distributing dependency JARs.

A remaining scope sharing the same callback is essential: after two scopes are invalidated and one scope is cleared/detached, the queued notification can still reach that departed scope. The recorded run reports one detached-scope notification. Moving input reads to composition and applying native values without independent read observation reports zero departed-holder notifications while the new value still renders.

The production candidate preserves key(mapKey), view/viewport ownership, and real-time event guards. It reads all mutable render inputs during composition; only the native update block uses Snapshot.withoutReadObservation. The portal test retains unavailable-branch and author-data assertions and additionally verifies departed/new view identities and attachment. The affected map-state, native-ink, selection and navigation regressions must still execute on the candidate.

`result.json` records the actual compile/run commands, outputs and dependency hashes. This JVM mechanism proof is separate from Android regression results.
