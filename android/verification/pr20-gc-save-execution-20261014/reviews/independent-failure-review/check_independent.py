"""Offline audit only. No runtime, network, Android, CI, or native execution."""
import base64
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(__file__).resolve().parent
HELPER = ROOT / "failure-proposal/failure_text.py"
spec = importlib.util.spec_from_file_location("reviewed_failure_helper", HELPER)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
sha = lambda x: hashlib.sha256(x).hexdigest()
compact = lambda x: json.dumps(x, ensure_ascii=True, separators=(",", ":")).encode()
binding = dict(run_id="900000001", run_attempt=1, workflow_sha="a"*40,
               source_commit="b"*40, source_spec_sha256="c"*64,
               method_plan_sha256="d"*64, test_source_sha256="e"*64,
               ordinal=8, identity="C1",
               selection="org.inkweft.data.LibrarySnapshotHoldDiagnosticsTest#idleHeldSnapshotAndActiveSearchSaveBoundaries",
               status="FAIL", junit_status="FAIL", adb_exit=0,
               process_exit_outcome="NATURAL_EXIT")
cases = []

def bound(n):
    whole, tail = divmod(n, 32768)
    return whole * (4 * math.ceil(32768/3) + 1024) + (4 * math.ceil(tail/3) + 1024 if tail else 0) + 8192

def decode_independently(records, expected):
    """Separate byte consumer; no calls into reviewed reconstruction function."""
    b, z = records[0], records[-1]
    assert set(b) == {"schema", "scope", "binding", "raw_bytes", "raw_sha256", "kind", "encoding", "chunk_bytes", "parts"}
    assert b["schema"] == 2 and b["scope"] == "DIAGNOSTIC_FAILURE_ONLY"
    assert b["binding"] == expected and expected["status"] in ("FAIL", "INCOMPLETE")
    assert b["kind"] == "BEGIN" and b["encoding"] == "base64-utf8" and b["chunk_bytes"] == 32768
    n = b["raw_bytes"]
    assert type(n) is int and 0 <= n <= 20*1024*1024
    count = (n + 32767)//32768
    assert type(b["parts"]) is int and b["parts"] == count and len(records) == count+2
    context = sha(compact(b))
    chunks = []
    for i, p in enumerate(records[1:-1]):
        assert set(p) == {"schema", "scope", "kind", "context_sha256", "part", "parts", "raw_bytes", "raw_sha256", "base64"}
        assert p["schema"] == 2 and p["scope"] == b["scope"] and p["kind"] == "PART"
        assert type(p["part"]) is int and p["part"] == i and p["parts"] == count
        assert p["context_sha256"] == context
        c = base64.b64decode(p["base64"], validate=True)
        assert base64.b64encode(c).decode() == p["base64"]
        assert len(c) == min(32768, n - i*32768) == p["raw_bytes"] and sha(c) == p["raw_sha256"]
        chunks.append(c)
    assert z == dict(schema=2, scope=b["scope"], kind="END", context_sha256=context,
                     parts=count, raw_bytes=n, raw_sha256=b["raw_sha256"])
    data = b"".join(chunks)
    assert len(data) == n and sha(data) == b["raw_sha256"]
    data.decode("utf-8", errors="strict")
    return data

def positive(name, data, use_binding=None):
    before = sha(data)
    b = use_binding or binding
    records = h.prepare_failure_text(data, b, before, len(data), bound(len(data)))
    assert decode_independently(records, b) == data
    assert h.reconstruct_failure_text(records, b) == data
    assert sha(data) == before
    actual = sum(len(h.PREFIX.encode())+len(compact(r))+1 for r in records)
    assert actual == h.wire_bytes(records) <= bound(len(data)) == h.maximum_wire_bytes(len(data))
    cases.append(dict(name=name, status="PASS", bytes=len(data), wire_bytes=actual, wire_cap=bound(len(data))))
    return records

for n in [0, 1, 2, 3, 32767, 32768, 32769, 65535, 65536, 65537, 278528, 858514, 3790465]:
    positive("byte-exact-size-"+str(n), b"x"*n)
positive("utf8-character-straddles-boundary", b"x"*32767 + "界🙂\n\"\x00".encode()*100)
positive("incomplete-keeps-status", b"INSTRUMENTATION_FAILED: disconnected\n", dict(binding, status="INCOMPLETE", junit_status=None, adb_exit=1, process_exit_outcome="UNCONFIRMED"))

def refused(name, data, reason, **changes):
    args = dict(data=data, binding=binding, expected_sha256=sha(data), raw_limit=len(data), wire_limit=bound(len(data)))
    args.update(changes)
    before = sha(data)
    records = h.prepare_failure_text(**args)
    assert len(records) == 1 and records[0]["kind"] == "REFUSED" and records[0]["reason"] == reason
    assert not {"base64", "original_json", "text"} & set(records[0])
    assert records[0]["binding"]["status"] == "FAIL" and sha(data) == before
    assert h.wire_bytes(records) <= 8192
    cases.append(dict(name=name, status="PASS", refusal=reason))

refused("wrong-expected-sha", b"unchanged\n", "HASH_MISMATCH", expected_sha256="0"*64)
refused("over-raw-cap", b"abc", "RAW_OVERSIZE", raw_limit=2)
refused("invalid-utf8", b"good\n\xf0\x80\x80\x80", "INVALID_UTF8")
refused("over-wire-cap", b"a"*32768, "ENCODED_OVERSIZE", wire_limit=8192)
refused("no-full-stream-after-normal", b"test failure", "NORMAL_FORWARD_ALREADY_STARTED", normal_forward_started=True)
for i, secret in enumerate([b"ghp_"+b"z"*24, b"github_pat_"+b"z"*24, b"AKIA"+b"Z"*16,
                             b"-----BEGIN PRIVATE KEY-----", b"-----BEGIN RSA PRIVATE KEY-----",
                             b"-----BEGIN EC PRIVATE KEY-----", b"-----BEGIN OPENSSH PRIVATE KEY-----",
                             b"ssh-rsa "+b"Z"*44, b"ssh-ed25519 "+b"Z"*44]):
    refused("listed-sensitive-pattern-cross-chunk-"+str(i), b"x"*(32768-2)+secret, "SENSITIVE")

base = positive("mutation-control", b"a"*32768+b"b"*32768+b"c")
mutations = {
    "missing-part": lambda r: r.pop(1),
    "duplicate-part": lambda r: r.insert(1, copy.deepcopy(r[1])),
    "reordered-parts": lambda r: r.__setitem__(slice(1,3), [r[2],r[1]]),
    "wrong-context": lambda r: r[1].__setitem__("context_sha256", "0"*64),
    "wrong-part-hash": lambda r: r[1].__setitem__("raw_sha256", "0"*64),
    "wrong-total-hash": lambda r: r[0].__setitem__("raw_sha256", "0"*64),
    "missing-end": lambda r: r.pop(),
    "bad-base64": lambda r: r[1].__setitem__("base64", r[1]["base64"][:-1]+"!"),
    "extra-base64-padding": lambda r: r[1].__setitem__("base64", r[1]["base64"]+"="),
    "wrong-run": lambda r: r[0]["binding"].__setitem__("run_id", "900000002"),
    "wrong-source": lambda r: r[0]["binding"].__setitem__("source_commit", "f"*40),
    "pass-upgrade": lambda r: r[0]["binding"].__setitem__("status", "PASS"),
    "unknown-part-key": lambda r: r[1].__setitem__("unexpected", 1),
}
for name, mutate in mutations.items():
    records = copy.deepcopy(base); mutate(records)
    for decoder in [decode_independently, h.reconstruct_failure_text]:
        try: decoder(records, binding)
        except (AssertionError, ValueError, TypeError, KeyError): pass
        else: raise AssertionError(name+" accepted by "+decoder.__name__)
    cases.append(dict(name=name, status="PASS", malformed_rejected_by_both=True))
try: h.prepare_failure_text(b"fail", dict(binding,status="PASS"), sha(b"fail"), 4, 8192)
except AssertionError: cases.append(dict(name="producer-refuses-PASS", status="PASS"))
else: raise AssertionError("producer allows PASS")

# Independent arithmetic over the existing capacity models; does not execute/rewrite joint_budget.py.
budget = json.loads((ROOT/"joint-budget.json").read_text())
gc_path = Path("/workspace/scratch/pr20-gc-author-only-budget-20261014/budget.json")
gc = json.loads(gc_path.read_text())
contract = json.loads(Path("/workspace/scratch/pr20-save-snapshot-control-20261014/contract.json").read_text())

def independent_save_capacity(failed=False):
    markers = []
    expected = contract["expected"]
    fixture = dict(rowCounts=expected["rowCounts"], logicalSha256=expected["fixtureSha256"], writerSqlite=expected["writerSqlite"])
    for arm, mode in enumerate(contract["order"]):
        broken = failed and arm == 2
        rows = {key: ([-2**63]*len(bounds) if broken else [max(pair,key=lambda x:len(str(x))) for pair in bounds])
                for key,bounds in contract["ranges"].items()}
        markers.append(("LIBRARY_SNAPSHOT_CONTROL_RESULT", dict(schema=1, diagnosticOnly=True, seed=4101, mode=mode,
            readiness=expected["readiness"][arm], fixture=fixture, startRevision=1, finalRevision=-2**63 if broken else 31,
            readerBeforeRevision=expected["readerBeforeRevision"][arm], readerAfterRevision=expected["readerAfterRevision"][arm],
            saves=[rows["save"] for _ in range(30)])))
        for kind,key,count in [("EVENTS","boundary",1536),("READS","read",128)]:
            for part in range((count+95)//96):
                markers.append(("LIBRARY_SNAPSHOT_CONTROL_"+kind, dict(schema=1,mode=mode,part=part,
                    events=[rows[key] for _ in range(min(96,count-part*96))])))
        markers.append(("LIBRARY_SNAPSHOT_CONTROL_END", dict(schema=1,mode=mode,completed=not broken,coverageValid=not broken,
            committedSaves=30,recordedEvents=1536,recordedReadEvents=128,droppedEvents=-2**31 if broken else 0,
            droppedReadEvents=-2**31 if broken else 0,invalid=False,readerExited=not broken,writersExited=not broken,
            databaseClosed=not broken,databaseDeleted=not broken,cleanupErrors=-2**31 if broken else 0)))
    raw = 16384
    wire = 0
    maximum = {}
    ordered = []
    for i,(marker,payload) in enumerate(markers):
        text = compact(payload).decode()
        size = len(text.encode())
        raw += size + len(marker.encode()) + 162
        maximum[marker] = max(size,maximum.get(marker,0))
        item = dict(sequence=i,marker=marker,payload_bytes=size,payload_sha256=sha(text.encode()))
        ordered.append(item)
        wire += len(b"INKWEFT_CAUSAL_RECORD ")+len(compact(dict(item,ordinal=8,identity="C1",original_json=text)))+65
    end = dict(ordinal=8,identity="C1",count=len(markers),ordered_payloads=ordered,ordered_payloads_sha256="0"*64)
    wire += len(b"INKWEFT_CAUSAL_END ")+len(compact(end))+65
    return dict(raw_framed_bytes=raw,official_record_end_bytes=wire,max_payload_bytes=maximum,
                payloads=len(markers),derived_records_uploaded_bytes=0)

assert independent_save_capacity() == budget["save_normal_capacity"]
assert independent_save_capacity(True) == budget["save_failed_last_arm_capacity"]
fixed = sum(budget["fixed_text_buckets_bytes"].values())
assert fixed == 3*1024*1024 and budget["failure_only_extra_raw_reserve_bytes"] == 256*1024
for path, expected in budget["input_sha256"].items(): assert sha(Path(path).read_bytes()) == expected
for n in list(range(0, 10))+[32767,32768,32769,65536,278528,858514,3790465,20*1024*1024]:
    assert bound(n) == h.maximum_wire_bytes(n)
normal_gc = sum(sum(m[k] for k in ["raw_framed_bytes","official_record_bytes","official_end_bytes"])
                for m in gc["normal_capacity_model"]["methods"])
assert normal_gc == budget["normal_gc_original_and_forwarded_bytes"]
first = gc["normal_capacity_model"]["methods"][0]
prefix = sum(first[k] for k in ["raw_framed_bytes","official_record_bytes","official_end_bytes"])
caps = dict(guard=16384+262144,
            gc=gc["last_trial_failed_capacity_model"]["methods"][1]["raw_framed_bytes"]+262144,
            save=budget["save_failed_last_arm_capacity"]["raw_framed_bytes"]+262144)
assert caps == budget["failure_raw_caps"]
assert {k: bound(v) for k,v in caps.items()} == budget["failure_wire_caps"]
totals = dict(all_methods_success=normal_gc+budget["save_normal_capacity"]["raw_framed_bytes"]+budget["save_normal_capacity"]["official_record_end_bytes"]+fixed,
              first_failure_in_guards=caps["guard"]+bound(caps["guard"])+fixed,
              first_failure_in_GC_worst_last_trial=prefix+caps["gc"]+bound(caps["gc"])+fixed,
              first_failure_in_save_worst_last_arm=normal_gc+caps["save"]+bound(caps["save"])+fixed)
assert totals == budget["cases_bytes"]
assert max(totals.values()) == budget["maximum_case_bytes"] == 19675060
assert budget["limit_bytes"]-max(totals.values()) == budget["remaining_bytes"] == 1296460

inputs = [HELPER, ROOT/"joint_budget.py", ROOT/"joint-budget.json", gc_path,
          Path("/workspace/scratch/pr20-save-snapshot-control-20261014/contract.json")]
result = dict(status="PASS_OFFLINE_HELPER_AND_BUDGET_ONLY_RUNTIME_PENDING", cases=len(cases),
              details=cases, capacity_cases=totals, maximum_bytes=19675060, remaining_bytes=1296460,
              input_sha256={str(p):sha(p.read_bytes()) for p in inputs},
              limits=["Synthetic tests are not native results or CI authorization.",
                      "Secret checking proves the listed regex patterns, not detection of every possible credential.",
                      "Runtime path/hash binding, whole-plan fail-stop, normal/failure mutex, category and total-byte enforcement await stable caller review.",
                      "Source capacity assumptions and numeric row bounds are input contracts; no production performance claim."])
(OUT/"helper-budget-results.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps({k: result[k] for k in ["status","cases","maximum_bytes","remaining_bytes"]}))
