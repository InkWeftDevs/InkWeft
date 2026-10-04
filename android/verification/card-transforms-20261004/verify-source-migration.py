#!/usr/bin/env python3
"""Host SQLite DDL/data check. This is not Android Room or device execution."""
import json
from pathlib import Path
import re
import sqlite3
import uuid

android = Path(__file__).resolve().parents[2]
schemas = android / "data-local/schemas/org.inkweft.data.NoteDatabase"
source = android / "data-local/src/main/kotlin/org/inkweft/data"

def create(db, version):
    for entity in json.loads((schemas / f"{version}.json").read_text())["database"]["entities"]:
        table = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", table))

def tables(db):
    return [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]

def structure(db, table):
    # Room's expected schema normalizes SQL spellings; compare semantic PRAGMA metadata.
    indexes = []
    for row in db.execute(f"PRAGMA index_list('{table}')"):
        if row[3] == "c":
            indexes.append((row[1], row[2], list(db.execute(f"PRAGMA index_info('{row[1]}')"))))
    return (list(db.execute(f"PRAGMA table_info('{table}')")),
            sorted(db.execute(f"PRAGMA foreign_key_list('{table}')")), sorted(indexes))

migrated = sqlite3.connect(":memory:")
create(migrated, 13)
book, card = str(uuid.uuid4()), str(uuid.uuid4())
body = "旧摘录\n 个人备注保持原样😀"
blob = b"synthetic-original-snapshot\x00\xff"
migrated.execute("INSERT INTO notes VALUES (?,1,'旧本','',1234)", (book,))
migrated.execute("INSERT INTO note_revisions VALUES (?,1,'旧本','',1234)", (book,))
migrated.execute("INSERT INTO notebook_pages VALUES (?,?,0,0,0,500,707,0,NULL,NULL)", (book, book))
migrated.execute("INSERT INTO study_cards VALUES (?,?,2,'旧卡',?,NULL)", (card, book, body))
for revision in (1, 2):
    migrated.execute("INSERT INTO study_card_revisions VALUES (?,?,'旧卡',?,NULL)", (card, revision, body))
migrated.execute("INSERT INTO study_sources VALUES (?,?,0,10,10,30,30,'',?)", (card, book, blob))
migrated.execute("INSERT INTO image_sources VALUES (?,'synthetic-image-digest',3)", (book,))
migrated.execute("INSERT INTO image_chunks VALUES (?,'synthetic-image-digest',0,?)", (book, b"img"))
before = {table: list(migrated.execute(f"SELECT * FROM '{table}'")) for table in tables(migrated)}

# These are exactly the statements called by MIGRATION_13_14, not rewritten fixture DDL.
for name in ("StudySourceVersions.kt", "CardTransformRepository.kt"):
    statements = re.findall(r'sql\.execSQL\("([^"\n]+)"\)', (source / name).read_text())
    assert statements, name
    for statement in statements:
        migrated.execute(statement)

for table, rows in before.items():
    assert list(migrated.execute(f"SELECT * FROM '{table}'")) == rows, table
assert list(migrated.execute("SELECT cardRevision,sourceRefs,complete FROM study_card_source_sets ORDER BY cardRevision")) == [(1, "", 0), (2, f"{card}@1", 1)]
assert migrated.execute("SELECT snapshot FROM study_source_revisions").fetchone()[0] == blob
assert not list(migrated.execute("PRAGMA foreign_key_check"))

expected = sqlite3.connect(":memory:")
create(expected, 14)
assert tables(migrated) == tables(expected)
for table in tables(expected):
    assert structure(migrated, table) == structure(expected, table), table
print("PASS: schema13→14 DDL matches generated Room14 schema; all 29 old tables unchanged; legacy body, source bytes and original image rows preserved; old source version explicitly unavailable")
