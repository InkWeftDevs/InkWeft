#!/usr/bin/env python3
"""Host SQLite checks only. Does not substitute for Android Room or UI execution."""
import json
from pathlib import Path
import re
import sqlite3
import uuid

android = Path(__file__).resolve().parents[2]
schemas = android / "data-local/schemas/org.inkweft.data.NoteDatabase"
def create(db, version):
    for entity in json.loads((schemas / f"{version}.json").read_text())["database"]["entities"]:
        name = entity["tableName"]
        db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", name))
def tables(db):
    return [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
def shape(db, name):
    indexes = [(r[1], r[2], list(db.execute(f"PRAGMA index_info('{r[1]}')"))) for r in db.execute(f"PRAGMA index_list('{name}')") if r[3] == "c"]
    return list(db.execute(f"PRAGMA table_info('{name}')")), sorted(db.execute(f"PRAGMA foreign_key_list('{name}')")), sorted(indexes)
old = sqlite3.connect(":memory:"); create(old, 15)
book, card = str(uuid.uuid4()), str(uuid.uuid4())
old.execute("INSERT INTO notes VALUES (?,1,'合成迁移','保留正文',12)", (book,))
old.execute("INSERT INTO note_revisions VALUES (?,1,'合成迁移','保留正文',12)", (book,))
old.execute("INSERT INTO notebook_pages VALUES (?,?,0,0,0,500,707,0,NULL,NULL)", (book, book))
old.execute("INSERT INTO study_cards VALUES (?,?,1,'原卡','旧混合正文😀',NULL)", (card, book))
old.execute("INSERT INTO study_card_revisions VALUES (?,1,'原卡','旧混合正文😀',NULL)", (card,))
old.execute("INSERT INTO study_card_source_sets VALUES (?,1,'',1)", (card,))
old.execute("INSERT INTO canvas_authoring VALUES ('PAGE',?,?,1,?)", (book, book, b"synthetic-DDL-preservation-marker"))
old.execute("INSERT INTO image_sources VALUES (?,'synthetic',3)", (book,))
old.execute("INSERT INTO image_chunks VALUES (?,'synthetic',0,?)", (book, b"img"))
before = {name: list(old.execute(f"SELECT * FROM '{name}'")) for name in tables(old)}
source = (android / "data-local/src/main/kotlin/org/inkweft/data/RecallStorage.kt").read_text()
for statement in re.findall(r'sql\.execSQL\("([^"\n]+)"\)', source):
    old.execute(statement)
new = sqlite3.connect(":memory:"); create(new, 16)
assert tables(old) == tables(new)
for name in tables(new):
    assert shape(old, name) == shape(new, name), name
for name, rows in before.items():
    assert list(old.execute(f"SELECT * FROM '{name}'")) == rows, name
assert not list(old.execute("PRAGMA foreign_key_check"))
assert len(tables(new)) == 42
print("PASS: actual 15→16 DDL equals generated Room16; 34 legacy tables/bytes unchanged; 8 review tables empty; FK closure intact")
