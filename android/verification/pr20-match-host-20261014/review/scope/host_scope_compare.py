"""HOST_ONLY: in-memory synthetic SQLite comparison, never Android or user files."""
from pathlib import Path
import sqlite3,json,hashlib,time,statistics,re,uuid
HERE=Path(__file__).resolve().parent
SOURCE=Path('/workspace/InkWeft/android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt')
source=SOURCE.read_text();shelf=re.search(r'val shelf="""(.*?)"""\.trimIndent\(\)',source,re.S)[1]
schemas=Path('/workspace/InkWeft/android/data-local/schemas/org.inkweft.data.NoteDatabase')
schema_path=max(schemas.glob('*.json'),key=lambda p:int(p.stem));schema=json.loads(schema_path.read_text())['database']
ids=lambda n:[str(uuid.UUID(hashlib.sha256(f'scope-only-{i}'.encode()).hexdigest()[:32]))for i in range(n)]
variants={'rowid':'CREATE TEMP TABLE library_scope(id TEXT PRIMARY KEY)','without_rowid':'CREATE TEMP TABLE library_scope(id TEXT PRIMARY KEY) WITHOUT ROWID'}
def prepare(n,mode):
 c=sqlite3.connect(':memory:',isolation_level=None);c.execute('PRAGMA temp_store='+mode)
 for table in ['notes','notebook_workspace']:
  entity=next(e for e in schema['entities']if e['tableName']==table);c.execute(entity['createSql'].replace('${TABLE_NAME}',table))
 c.execute('CREATE TEMP TABLE library_drafts(id TEXT PRIMARY KEY,revision INTEGER,title TEXT,text TEXT,position INTEGER)')
 c.create_function('library_blank',1,lambda v:int(not v or v.isspace()))
 c.create_function('library_title',1,lambda v:v.lower().encode('utf-16-be'))
 c.executemany('INSERT INTO notes VALUES(?,?,?,?,?)',[(s,1,['Å','中文','Same','\U00010400'][i%4],'body',i//4)for i,s in enumerate(ids(n))])
 c.executemany('INSERT INTO notebook_workspace(noteId,world,paper,folder,tags,favorite,trashedAt,centerX,centerY,zoom,revision,coverKey,selectedPageId,pinned) VALUES(?,0,1,?,?,?,NULL,500,707,0,0,\'auto\',\'\',?)',[(s,'课程'if i%3 else '\u2003','复习\n项目'if i%2 else '',int(i%5==0),int(i%7==0))for i,s in enumerate(ids(n))])
 return c
samples=[];details=[]
for mode in ['DEFAULT','MEMORY']:
 for n in [500,20000]:
  c=prepare(n,mode)
  for pair in range(8):
   for name in (['rowid','without_rowid']if pair%2==0 else ['without_rowid','rowid']):
    c.execute('DROP TABLE IF EXISTS library_scope');c.execute('BEGIN')
    t=time.perf_counter_ns();cpu=time.process_time_ns();c.execute(variants[name]);created=time.perf_counter_ns()
    c.execute(shelf+' INSERT INTO library_scope SELECT id FROM shelf WHERE trashedAt IS NULL');end=time.perf_counter_ns();cpuend=time.process_time_ns()
    count=c.execute('SELECT COUNT(*) FROM library_scope').fetchone()[0];assert count==n
    membership=c.execute('SELECT id FROM notes WHERE id IN(SELECT id FROM library_scope) ORDER BY updatedAt DESC,id LIMIT 61 OFFSET 60').fetchall()
    expected=c.execute('SELECT id FROM notes ORDER BY updatedAt DESC,id LIMIT 61 OFFSET 60').fetchall();assert membership==expected
    samples.append(dict(temp_store=mode,rows=n,pair=pair,variant=name,create_ms=(created-t)/1e6,insert_ms=(end-created)/1e6,total_ms=(end-t)/1e6,cpu_ms=(cpuend-cpu)/1e6,count=count))
    if pair==0:
     physical=[dict(zip(['name','pages','bytes'],r))for r in c.execute("SELECT name,COUNT(*),SUM(pgsize) FROM dbstat('temp') WHERE name LIKE '%scope%' GROUP BY name")]
     details.append(dict(temp_store=mode,rows=n,variant=name,sqlite_schema=c.execute("SELECT type,name,tbl_name,rootpage,sql FROM temp.sqlite_schema WHERE tbl_name='library_scope'").fetchall(),index_list=c.execute('PRAGMA temp.index_list(library_scope)').fetchall(),physical=physical,query_plan=c.execute('EXPLAIN QUERY PLAN SELECT id FROM notes WHERE id IN(SELECT id FROM library_scope) ORDER BY updatedAt DESC,id LIMIT 61 OFFSET 60').fetchall()))
    c.execute('ROLLBACK')
  c.close()
# Focused semantic comparisons: saved + existing overlay + unsaved overlay, Unicode filters/order, NULL difference.
c=prepare(145,'DEFAULT');saved=ids(145);fresh=str(uuid.UUID(int=1));c.execute('INSERT INTO library_drafts VALUES(?,?,?,?,?)',(saved[2],1,'Changed','draft',0));c.execute('INSERT INTO library_drafts VALUES(?,?,?,?,?)',(fresh,0,'新建','new',1));c.execute('UPDATE notebook_workspace SET trashedAt=1 WHERE noteId=?',(saved[1],))
semantic=[]
for filt in ['trashedAt IS NULL','trashedAt IS NOT NULL',"trashedAt IS NULL AND library_blank(folder)=1","trashedAt IS NULL AND folder='课程'","trashedAt IS NULL AND favorite=1","trashedAt IS NULL AND instr(char(10)||tags||char(10),char(10)||'复习'||char(10))>0"]:
 results=[]
 for name,create in variants.items():
  c.execute('DROP TABLE IF EXISTS library_scope');c.execute(create);c.execute(shelf+' INSERT INTO library_scope SELECT id FROM shelf WHERE '+filt)
  rows=c.execute(shelf+' SELECT id,title,revision FROM shelf WHERE id IN(SELECT id FROM library_scope) ORDER BY pinned DESC,library_title(title),updatedAt DESC,draftPosition,id').fetchall()
  results.append(rows)
 assert results[0]==results[1];semantic.append({'filter':filt,'rows':len(results[0]),'equal':True})
nulls={}
for name,create in variants.items():
 c.execute('DROP TABLE IF EXISTS library_scope');c.execute(create)
 try:c.execute('INSERT INTO library_scope VALUES(NULL)');nulls[name]='accepted'
 except sqlite3.IntegrityError:nulls[name]='rejected_NOT_NULL'
c.close()
summary=[]
for mode in ['DEFAULT','MEMORY']:
 for n in [500,20000]:
  v={name:[r for r in samples if r['temp_store']==mode and r['rows']==n and r['variant']==name]for name in variants}
  a=[r['total_ms']for r in v['rowid']];b=[r['total_ms']for r in v['without_rowid']]
  summary.append(dict(temp_store=mode,rows=n,rowid_median_ms=statistics.median(a),without_rowid_median_ms=statistics.median(b),paired_diff_median_ms=statistics.median([y-x for x,y in zip(a,b)]),paired_relative_median_pct=statistics.median([(y-x)/x*100 for x,y in zip(a,b)]),faster_pairs=sum(y<x for x,y in zip(a,b)),pairs=len(a)))
report=dict(scope='HOST_ONLY_NOT_NATIVE_PERFORMANCE',sqlite_version=sqlite3.sqlite_version,source_sha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),schema_path=str(schema_path),schema_sha256=hashlib.sha256(schema_path.read_bytes()).hexdigest(),page_size=4096,fixture='synthetic UUID note IDs; author tables in :memory:; temp DEFAULT and MEMORY; balanced order; 8 pairs; 500/20000 rows; create+INSERT SELECT only timed; queries/assertions outside timer',samples=samples,summary=summary,details=details,semantics=semantic,null_difference=nulls,limits=['Host SQLite3.53.1 differs from Android Requery3.49.0','No JNI, Room, cancellation, concurrent writer, WAL, or save latency is measured','process_time includes this Python host; in-memory main and narrow synthetic fixture only','Same-connection repeats/cache state are descriptive correlated samples, not independent performance acceptance'])
(HERE/'host-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'scope':report['scope'],'summary':summary,'semantics':semantic,'null_difference':nulls},ensure_ascii=False,indent=2))
