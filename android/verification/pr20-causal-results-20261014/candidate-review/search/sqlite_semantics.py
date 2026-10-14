"""Host SQLite semantics only. No timings and no Android/SQLite 3.49 claims."""
import json
import sqlite3
from pathlib import Path

out=Path(__file__).parent
records=[]
for encoding in ['UTF-8','UTF-16le','UTF-16be']:
    db=sqlite3.connect(':memory:')
    db.execute("PRAGMA encoding='"+encoding+"'")
    db.execute('CREATE TABLE t(id INTEGER PRIMARY KEY, value TEXT)')
    actual=db.execute('PRAGMA encoding').fetchone()[0]
    for value in ['', 'ASCII', 'A\0B', '\0', 'İ', 'ſ', 'K', '中文', '🚀', '\x01\x7f', None, b'\xc4\xb0']:
        db.execute('DELETE FROM t');db.execute('INSERT INTO t VALUES(1,?)',(value,))
        kind,chars,bytes_,bare,guard=db.execute("SELECT typeof(value),length(value),length(CAST(value AS BLOB)),length(value)=length(CAST(value AS BLOB)),typeof(value)='text' AND length(value)=length(CAST(value AS BLOB)) FROM t").fetchone()
        expected=kind=='text' and (value=='' or actual=='UTF-8' and all(0<ord(c)<128 for c in value))
        assert bool(guard)==expected
        records.append({'encoding':actual,'value':value.hex() if isinstance(value,bytes) else value,
                        'type':kind,'codepointsBeforeNul':chars,'blobBytes':bytes_,
                        'bareLengthEquality':bare,'typeAndLengthGuard':guard})
    db.close()

db=sqlite3.connect(':memory:')
db.executescript('CREATE TABLE notes(id INTEGER PRIMARY KEY,text TEXT); CREATE TABLE library_drafts(id INTEGER PRIMARY KEY,text TEXT); INSERT INTO notes VALUES(1,\'ASCII BODY\');')
calls={'source':0,'fallback':0}
def tapped(value):calls['source']+=1;return value
def fallback(value):calls['fallback']+=1;return int('body' in (value or '').lower())
db.create_function('tap',1,tapped);db.create_function('original',1,fallback)
expr='COALESCE((SELECT tap(d.text) FROM library_drafts d WHERE d.id=shelf.id),(SELECT tap(n.text) FROM notes n WHERE n.id=shelf.id))'
queries={
 'original':f'original({expr})',
 'lengthCase':f"CASE WHEN length({expr})=length(CAST({expr} AS BLOB)) THEN instr(lower({expr}),?)>0 ELSE original({expr}) END",
 'typedLengthCase':f"CASE WHEN typeof({expr})='text' AND length({expr})=length(CAST({expr} AS BLOB)) THEN instr(lower({expr}),?)>0 ELSE original({expr}) END",
}
evaluations=[]
for name,expression in queries.items():
    calls.update(source=0,fallback=0)
    sql='SELECT '+expression+' FROM notes shelf'
    result=db.execute(sql,() if name=='original' else ('body',)).fetchone()[0]
    assert result==1
    evaluations.append({'variant':name,'result':result,**calls,'queryPlan':[r[3] for r in db.execute('EXPLAIN QUERY PLAN '+sql,() if name=='original' else ('body',))]})
assert [r['source'] for r in evaluations]==[1,3,4]
assert [r['fallback'] for r in evaluations]==[1,0,0]
# Invalid raw TEXT is outside the valid-Unicode proof, even with typeof='text'.
malformed=[]
for data in [b'\xff', b'\xc1\x89', b'\x80']:
    malformed.append({'hex':data.hex(),'lengths':list(db.execute('SELECT typeof(CAST(? AS TEXT)),length(CAST(? AS TEXT)),length(CAST(? AS BLOB))',(data,data,data)).fetchone())})
db.close()
report={'scope':'HOST_SQLITE_SEMANTICS_ONLY','sqliteVersion':sqlite3.sqlite_version,'timingsCollected':False,
        'encodingGuardCases':records,'correlatedExpressionEvaluations':evaluations,'malformedTextOutsideProof':malformed}
(out/'host-sqlite-semantics.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'scope':report['scope'],'sqliteVersion':sqlite3.sqlite_version,'guardCases':len(records),'sourceCalls':[r['source'] for r in evaluations],'fallbackCalls':[r['fallback'] for r in evaluations]}))
