"""Local source preparation only. No build, adb, network, git mutation or execution.

Derive portable, byte-identical paired probes from the fixed candidate. The
generated Kotlin is an uncompiled proposal until the separately approved job.
"""
import difflib
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
REPO = Path('/workspace/InkWeft')
BASE = 'a128bb07e34cb82f00eeafc8e62a47bf7b95b128'
CANDIDATE = '7949011ca2b84492f6438d275a09d28faef3b472'
FACTORY = 'android/data-local/src/main/kotlin/org/inkweft/data/PreservingOpenHelperFactory.kt'

def blob(ref, path):
    return subprocess.check_output(['git', '-C', str(REPO), 'show', f'{ref}:{path}'])

def replace(text, old, new, count=1):
    assert text.count(old) == count, (old[:100], text.count(old), count)
    return text.replace(old, new)

HELPER = '''
    private val pairSeed=checkNotNull(InstrumentationRegistry.getArguments().getString("pairSeed")).toLong()
    private val pairRandom=java.util.Random(pairSeed)
    private fun id()=UUID(pairRandom.nextLong(),pairRandom.nextLong()).toString()
    private fun emit(marker:String,value:JSONObject){
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","$marker $value\\n")})
    }
    // Outside all measured operations. Hash actual logical rows, not a fixture recipe.
    // Keep creation timestamps deterministic; later real saves retain their normal clock.
    private suspend fun fixtureEvidence(db:NoteDatabase,label:String,tables:List<String>)=db.withTransaction{
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE notes SET updatedAt=1700000000000 WHERE updatedAt>1000000000000")
        sql.execSQL("UPDATE note_revisions SET committedAt=1700000000000 WHERE committedAt>1000000000000")
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun add(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)}
        val counts=JSONObject()
        for(table in tables){
            require(table.matches(Regex("[a-z_]+")))
            add(table.toByteArray(Charsets.UTF_8));var rows=0
            val order=if(table=="page_layer_memberships")"1,2,3" else "1,2"
            sql.query("SELECT * FROM $table ORDER BY $order").use{c->
                c.columnNames.forEach{add(it.toByteArray(Charsets.UTF_8))}
                while(c.moveToNext()){
                    rows++
                    for(i in 0 until c.columnCount){
                        val type=c.getType(i);add(byteArrayOf(type.toByte()))
                        add(when(type){
                            android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf()
                            android.database.Cursor.FIELD_TYPE_BLOB->c.getBlob(i)
                            else->c.getString(i).toByteArray(Charsets.UTF_8)
                        })
                    }
                }
            }
            counts.put(table,rows)
        }
        val settings=JSONObject()
        for(setting in listOf("page_size","auto_vacuum","journal_mode","synchronous")){
            sql.query("PRAGMA $setting").use{it.moveToFirst();settings.put(setting,if(setting=="journal_mode")it.getString(0) else it.getLong(0))}
        }
        assertEquals(1024L,settings.getLong("page_size"));assertEquals(1L,settings.getLong("auto_vacuum"))
        assertEquals("wal",settings.getString("journal_mode"));assertEquals(2L,settings.getLong("synchronous"))
        emit("KVM_PAIR_FIXTURE",JSONObject().put("label",label).put("seed",pairSeed)
            .put("logicalSha256",digest.digest().joinToString(""){"%02x".format(it)})
            .put("rowCounts",counts).put("writerSqlite",settings))
    }
'''

library_path='android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryReadPerformanceTest.kt'
s=blob(CANDIDATE,library_path).decode()
s=replace(s,'class LibraryReadPerformanceTest {','class KvmLibraryPairProbe {')
s=replace(s,'    private fun id()=UUID.randomUUID().toString()',HELPER)
s=replace(s,'WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK)',
          'WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK,operationId=id())')
s=replace(s,'            val headerMethod=',
          '            fixtureEvidence(db,"library-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","page_search_text","library_search_pending"))\n            val headerMethod=')
s=replace(s,'            val coldStart=System.nanoTime()',
          '            val coldEnd=java.util.concurrent.atomic.AtomicLong()\n            val directProgress=JSONArray()\n            val coldStart=System.nanoTime()')
s=replace(s,'val cold=async(Dispatchers.IO){repo.read(request)}',
          'val cold=async(Dispatchers.IO){try{repo.read(request)}finally{coldEnd.set(System.nanoTime())}}')
s=replace(s,'                val start=System.nanoTime()\n                val result=noteRepo.save',
          '                val checksStart=scanned.get();val start=System.nanoTime()\n                val result=noteRepo.save')
s=replace(s,'writer.title,"Synthetic concurrent edit $i"))\n                assertTrue',
          'writer.title,"Synthetic concurrent edit $i"))\n                val end=System.nanoTime();val checksEnd=scanned.get()\n                assertTrue')
s=replace(s,'                add((System.nanoTime()-start)/1e6)\n',
          '                val elapsed=(end-start)/1e6\n                directProgress.put(JSONObject().put("sample",i).put("startNs",start).put("endNs",end).put("elapsedMs",elapsed).put("checksStart",checksStart).put("checksEnd",checksEnd).put("committedRevision",writer.revision).put("committed",true))\n                add(elapsed)\n')
s=replace(s,'            val coldScanned=scanned.get()',
          '            val coldScanned=scanned.get()\n            checkpoint("KVM_DIRECT_INTERVALS",JSONObject().put("coldStartNs",coldStart).put("coldEndNs",coldEnd.get()).put("saves",directProgress))')
s=replace(s,'            val observeStart=System.nanoTime()',
          '            val batchProgress=java.util.concurrent.CopyOnWriteArrayList<Pair<Int,Long>>()\n            val observeStart=System.nanoTime()')
s=replace(s,'                observeRows.set(count)',
          '                val committedNs=System.nanoTime();batchProgress.add(count to committedNs)\n                observeRows.set(count)')
s=replace(s,'                    val elapsed=(System.nanoTime()-start)/1e6',
          '                    val end=System.nanoTime();val elapsed=(end-start)/1e6')
s=replace(s,'.put("sample",i).put("elapsedMs",elapsed)',
          '.put("sample",i).put("elapsedMs",elapsed).put("startNs",start).put("endNs",end).put("committed",true).put("committedRevision",writer.revision)')
s=replace(s,'                savedSamples.put("indexRowsFinal",observeRows.get())',
          '                checkpoint("KVM_INDEX_INTERVALS",JSONObject().put("observeStartNs",observeStart).put("batches",JSONArray().apply{batchProgress.forEach{put(JSONObject().put("rows",it.first).put("committedNs",it.second))}}))\n                savedSamples.put("indexRowsFinal",observeRows.get())')
s=replace(s,'            val warm=buildList{','            val warmSamples=buildList{')
s=replace(s,'assertEquals(0,result.pendingIndex)}}.sorted()',
          'assertEquals(0,result.pendingIndex)}}\n            val warm=warmSamples.sorted()')
s=replace(s,'.put("coldMs",coldMs).put("coldSourceChecks",coldScanned).put("warmMedianMs",warm[2])',
          '.put("coldMs",coldMs).put("coldSourceChecks",coldScanned).put("warmMedianMs",warm[2]).put("warmSamplesMs",JSONArray(warmSamples))')
library=s

history_path='android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryCollectionBenchmarkTest.kt'
s=blob(CANDIDATE,history_path).decode()
s=replace(s,'class HistoryCollectionBenchmarkTest {','class KvmHistoryPairProbe {\n'+HELPER)
s=s.replace('UUID.randomUUID().toString()','id()')
s=replace(s,'WorkspaceRepository(db).create("垃圾清理探针",false,PaperStyle.BLANK)',
          'WorkspaceRepository(db).create("垃圾清理探针",false,PaperStyle.BLANK,operationId=id())')
s=replace(s,'            val notifications=AtomicInteger()',
          '            fixtureEvidence(db,"history-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","study_cards","study_card_revisions","study_source_revisions","study_card_source_sets"))\n            val initialAuthor=checkNotNull(NoteRepository(db).read(book))\n            val notifications=AtomicInteger()')
s=replace(s,'                val saves=mutableListOf<Double>()',
          '                val pausedProgress=JSONArray();val liveProgress=JSONArray()\n                val saves=mutableListOf<Double>()')
s=replace(s,'                        withTimeout(5_000){NoteRepository(db).rename(RenameNote(id(),book,note.revision,"并发保存 $i"))}\n                        saves+=(System.nanoTime()-started)/1_000_000.0',
          '                        val saved=withTimeout(5_000){NoteRepository(db).rename(RenameNote(id(),book,note.revision,"并发保存 $i"))}\n                        val ended=System.nanoTime();saves+=(ended-started)/1_000_000.0\n                        assertTrue(saved is SaveResult.Committed);assertEquals(note.revision+1,(saved as SaveResult.Committed).note.revision)\n                        pausedProgress.put(JSONObject().put("sample",i).put("startNs",started).put("endNs",ended).put("committed",true).put("revision",saved.note.revision))')
s=replace(s,'                val liveCollection=async(Dispatchers.IO){HistoryStorage(db){if(it=="after-roots"){\n                    liveScans.incrementAndGet();liveEntered.complete(Unit)\n                }}.collectDetached()}',
          '                val liveCallStart=java.util.concurrent.atomic.AtomicLong();val liveCallEnd=java.util.concurrent.atomic.AtomicLong()\n                val rootEvents=java.util.concurrent.CopyOnWriteArrayList<Long>()\n                val liveCollection=async(Dispatchers.IO){\n                    liveCallStart.set(System.nanoTime())\n                    try{HistoryStorage(db){if(it=="after-roots"){\n                        rootEvents.add(System.nanoTime());liveScans.incrementAndGet();liveEntered.complete(Unit)\n                    }}.collectDetached()}finally{liveCallEnd.set(System.nanoTime())}\n                }')
s=replace(s,'                    NoteRepository(db).rename(RenameNote(id(),book,note.revision,"持续保存 $i"))\n                    liveSaves+=(System.nanoTime()-started)/1_000_000.0',
          '                    val saved=NoteRepository(db).rename(RenameNote(id(),book,note.revision,"持续保存 $i"))\n                    val ended=System.nanoTime();liveSaves+=(ended-started)/1_000_000.0\n                    assertTrue(saved is SaveResult.Committed);assertEquals(note.revision+1,(saved as SaveResult.Committed).note.revision)\n                    liveProgress.put(JSONObject().put("sample",i).put("startNs",started).put("endNs",ended).put("committed",true).put("revision",saved.note.revision))')
s=replace(s,'                val finished=HistoryStorage(db)',
          '                val finishStart=System.nanoTime()\n                val finished=HistoryStorage(db)')
s=replace(s,'                val liveMs=(System.nanoTime()-liveStarted)/1_000_000.0',
          '                val finishEnd=System.nanoTime()\n                val liveMs=(finishEnd-liveStarted)/1_000_000.0')
s=replace(s,'                fun JSONObject.timings',
          '                val finalAuthor=checkNotNull(NoteRepository(db).read(book))\n                assertEquals(initialAuthor.revision+60,finalAuthor.revision);assertEquals("持续保存 29",finalAuthor.title)\n                emit("KVM_HISTORY_INTERVALS",JSONObject().put("paused",pausedProgress).put("live",liveProgress)\n                    .put("collectionStartNs",liveCallStart.get()).put("collectionEndNs",liveCallEnd.get()).put("afterRootsNs",JSONArray(rootEvents))\n                    .put("finishStartNs",finishStart).put("finishEndNs",finishEnd)\n                    .put("firstDeleted",liveReport.detachedSources).put("finishDeleted",finished.detachedSources).put("finalRevision",finalAuthor.revision))\n                fun JSONObject.timings')
history=s

# Join/cancel every structured child before closing the shared database, also on
# a failed entry timeout/assertion. This changes cleanup scope only, not the work.
def joined_before_close(text):
    text=replace(text,'()=runBlocking{coroutineScope{','()=runBlocking{')
    token='        try{\n'
    offset=text.index(token,text.index('@Test fun '))
    text=text[:offset]+text[offset:].replace(token,'        try{coroutineScope{\n',1)
    return replace(text,'        }finally{db.close();context.deleteDatabase(name)}\n    }}\n}',
                   '        }}finally{db.close();context.deleteDatabase(name)}\n    }\n}')
library=joined_before_close(library)
history=joined_before_close(history)

raster_path='android/app/src/androidTest/java/org/inkweft/app/TileLocalRedrawTest.kt'
s=blob(CANDIDATE,raster_path).decode()
s=replace(s,'class TileLocalRedrawTest {','class KvmLocalAppendPairProbe {')
s=replace(s,'    private fun id()=UUID.randomUUID().toString()',HELPER)
start=s.index('    private class InterruptedPanWait:')
end=s.index('    @Test fun oneFourEightLayerLocalAppend',start)
s=s[:start]+s[end:]
s=replace(s,'        val metadataDiagnostics=InstrumentationRegistry.getArguments().getString("rasterMetadataDiagnostics")=="true"\n','')
s=replace(s,'WorkspaceRepository(db).create("局部重绘 $count 层",true,PaperStyle.BLANK)',
          'WorkspaceRepository(db).create("局部重绘 $count 层",true,PaperStyle.BLANK,operationId=id())')
s=replace(s,'                val first=ready(active,target)',
          '                fixtureEvidence(db,"raster-$count-before",listOf("notes","note_revisions","notebook_workspace","notebook_pages","ink_pages","ink_strokes","canvas_authoring","page_layer_memberships"))\n                val first=ready(active,target)')
s=replace(s,'                val metadataTimings=if(metadataDiagnostics)CopyOnWriteArrayList<InkRasterMetadataTiming>()else null\n                if(metadataTimings!=null)repo.rasterMetadataObserver={metadataTimings.add(it);Unit}\n                val local=try{ready(active,target)}finally{repo.rasterMetadataObserver=null}',
          '                val local=ready(active,target)')
start=s.index('                metadataTimings?.let{')
end=s.index('                InstrumentationRegistry.getInstrumentation()',start)
s=s[:start]+s[end:]
s=s.replace('import java.util.concurrent.CopyOnWriteArrayList\n','')
s=replace(s,'                jobs.clearAndJoin(active+full);active.clear();full.clear()',
          '                jobs.clearAndJoin(active+full)\n                emit("KVM_RASTER_CLEANUP",JSONObject().put("layers",count).put("joined",jobs.joined).put("resources",JSONObject(RenderResources.snapshot())))\n                active.clear();full.clear()')
raster=s

probes={library_path.replace('LibraryReadPerformanceTest','KvmLibraryPairProbe'):library,
        history_path.replace('HistoryCollectionBenchmarkTest','KvmHistoryPairProbe'):history,
        raster_path.replace('TileLocalRedrawTest','KvmLocalAppendPairProbe'):raster}
origins={list(probes)[0]:library_path,list(probes)[1]:history_path,list(probes)[2]:raster_path}
records=[]
for path,value in probes.items():
    target=ROOT/'common-overlay'/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(value)
    previous=blob(CANDIDATE,origins[path]).decode()
    delta=''.join(difflib.unified_diff(previous.splitlines(True),value.splitlines(True),fromfile=origins[path],tofile=path))
    (ROOT/(target.stem+'.review.diff')).write_text(delta)
    records.append({'path':path,'from_candidate_path':origins[path],'bytes':len(value.encode()),'sha256':hashlib.sha256(value.encode()).hexdigest()})
factory=ROOT/'baseline-overlay'/FACTORY;factory.parent.mkdir(parents=True,exist_ok=True);factory.write_bytes(blob(CANDIDATE,FACTORY))

# Produce complete Android/ci tracked-source manifests without materializing old
# working trees or borrowing build outputs. This is read-only Git plus in-memory overlays.
def manifest(ref, baseline):
    names=subprocess.check_output(['git','-C',str(REPO),'ls-tree','-r','--name-only',ref],text=True).splitlines()
    names=[p for p in names if (p.startswith('android/') and '/verification/' not in p) or p.startswith('ci/')]
    rows={}
    for path in names:
        b=blob(ref,path);rows[path]={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
    if baseline:
        b=factory.read_bytes();rows[FACTORY]={'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
    for x in records:rows[x['path']]={k:x[k] for k in ('bytes','sha256')}
    serialized=json.dumps(rows,sort_keys=True,separators=(',',':')).encode()
    return {'git_base':ref,'git_tree':subprocess.check_output(['git','-C',str(REPO),'rev-parse',ref+'^{tree}'],text=True).strip(),
            'manifest_scope':'Every tracked android/ path excluding verification artifacts, plus ci/; generated build outputs/SDK/caches excluded. Git base pins other repository paths.',
            'canonical_manifest_sha256':hashlib.sha256(serialized).hexdigest(),'files':rows}

for label,ref in [('B',BASE),('C',CANDIDATE)]:
    m=manifest(ref,label=='B');(ROOT/f'{label}-source-manifest.json').write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
spec={'baseline_git':BASE,'candidate_git':CANDIDATE,'report_only_head':'320ca865ce17efeed1c112f115be57e1bd05a1fb',
      'baseline_production_override':{'path':FACTORY,'from_git':CANDIDATE,'sha256':hashlib.sha256(factory.read_bytes()).hexdigest()},
      'candidate_production_overrides':[], 'common_probes':records,
      'status':'PREPARED_SOURCE_ONLY_NOT_COMPILED_NOT_RUN',
      'workflows_added':False,'git_branches_changed':False,'native_executions':0}
(ROOT/'source-spec.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'status':spec['status'],'common_probes':records},ensure_ascii=False,indent=2))
