"""Run the production freshness SQL on an isolated host SQLite fixture.

This is an SQL semantics check, not Android/Room instrumentation.
Only stdlib is used. No device, uploaded code or user database is accessed.
"""
import json
import re
import sqlite3
from pathlib import Path

android = Path(__file__).resolve().parents[2]
source = android / "data-local/src/main/kotlin/org/inkweft/data/NotebookPages.kt"
query = re.search(r'@Query\("([^"]+)"\)\s+suspend fun hasCurrentSearchText', source.read_text()).group(1)
db = sqlite3.connect(":memory:")
db.executescript("""
CREATE TABLE notebook_pages(id TEXT PRIMARY KEY, trashedAt INTEGER);
CREATE TABLE ink_pages(noteId TEXT PRIMARY KEY, revision INTEGER);
CREATE TABLE page_search_text(pageId TEXT PRIMARY KEY, inkRevision INTEGER, text TEXT);
CREATE TABLE ink_strokes(payload BLOB);
CREATE TABLE page_objects(payload BLOB);
CREATE TABLE canvas_authoring(payload BLOB);
INSERT INTO notebook_pages VALUES ('page',NULL);
""")
reads = []


def authorize(action, table, column, database, origin):
    if action == sqlite3.SQLITE_READ:
        reads.append([table, column])
        if table in {"ink_strokes", "page_objects", "canvas_authoring"}:
            return sqlite3.SQLITE_DENY
    return sqlite3.SQLITE_OK


db.set_authorizer(authorize)
checks = []


def check(name, expected, page="page"):
    actual = bool(db.execute(query, {"id": page}).fetchone()[0])
    assert actual == expected, (name, actual, expected)
    checks.append({"case": name, "expected": expected, "actual": actual})


check("no_index", False)
db.execute("INSERT INTO page_search_text VALUES ('page',0,'indexed text')")
check("empty_page_index", True)
db.execute("INSERT INTO ink_pages VALUES ('page',1)")
check("stale_ink_revision", False)
db.execute("UPDATE page_search_text SET inkRevision=1")
check("matching_header", True)
db.execute("DELETE FROM page_search_text")
check("object_or_layer_transaction_invalidated_index", False)
db.execute("INSERT INTO page_search_text VALUES ('page',1,'new index')")
db.execute("UPDATE notebook_pages SET trashedAt=1")
check("trashed_page", False)
db.execute("UPDATE notebook_pages SET trashedAt=NULL")
check("restored_page", True)
check("missing_page", False, "missing")
print(json.dumps({"scope": "HOST_SQLITE_NOT_ANDROID_ROOM", "sqlite_version": sqlite3.sqlite_version,
                  "production_query": query, "checks": checks, "read_columns": reads,
                  "payload_reads": 0}, ensure_ascii=False, indent=2))
