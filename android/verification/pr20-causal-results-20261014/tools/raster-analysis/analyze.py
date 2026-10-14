#!/usr/bin/env python3
"""Offline arithmetic for three frozen diagnostic payloads; never performance acceptance."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent.parent
SOURCE = '78a5babeaeb92292026423222d5e08635989198b'
READINESS = 'b2e1d9f9de66a26f5a4b122ea6754d77198005f8'
CONTRACT_SHA = 'ac540b3f0321760d202e3ea76f76eb7c2f2afc3c2b532708152d523cb1cea722'
VALIDATOR_SHA = '3514d2bca1f7b91efe7886ea42763b37eca873c8914938e8ad3dfeb03eaa1592'
PHASES = [('active','first',1),('active','local',2),('full','full',2),('active','hidden-local',3),('full','hidden-full',3)]
CORE = ['REQUEST','WORKER_START','METADATA_START','RASTER_START','PREPARE_START','PREPARE_END','PUBLICATION_ENTER','PUBLISHED','READY_DRAW_START','READY_DRAW_END']
SEGMENTS = {
    'requestToWorkerMs':('REQUEST','WORKER_START'),
    'workerToMetadataMs':('WORKER_START','METADATA_START'),
    'metadataMs':('METADATA_START','RASTER_START'),
    'rasterMs':('RASTER_START','PREPARE_START'),
    'prepareMs':('PREPARE_START','PREPARE_END'),
    'prepareEndToMainEntryMs':('PREPARE_END','PUBLICATION_ENTER'),
    'mainPublicationMs':('PUBLICATION_ENTER','PUBLISHED'),
    'publishedToReadyDrawMs':('PUBLISHED','READY_DRAW_START'),
    'readyDrawMs':('READY_DRAW_START','READY_DRAW_END'),
    'requestToReadyEndMs':('REQUEST','READY_DRAW_END'),
    'requestToWorkerFinallyMs':('REQUEST','WORKER_FINALLY'),
    'publishedToFirstEnqueueMs':('PUBLISHED','NOTIFY_ENQUEUE'),
    'enqueueToCallbackMs':('NOTIFY_ENQUEUE','NOTIFY_CALLBACK'),
    'callbackToReadyDrawSignedMs':('NOTIFY_CALLBACK','READY_DRAW_START'),
}
LIMITS = [
    'One opt-in diagnostic invocation, seed 4101, with one first/append/full observation per 1/4/8-layer fixture; no repeated-sample quantiles or new thresholds.',
    'Single-source diagnostic has observer overhead; it does not replace default-off paired public probes or resolve the prior d66-versus-45b regression signal.',
    'Layer intervals overlap. Their sums describe accumulated per-layer intervals, not exclusive end-to-end work or lock wait.',
    'Metadata and synchronous native CPU are source-guarded thread CPU, not host cycles. No CPU is inferred by subtracting timestamps across suspensions; payload decode and renderer CPU are not measured.',
    'Native preparation/build includes render-input, brush and native Stroke preparation; counts do not indicate cross-frame native caching.',
    'Missing callback timestamps remain null. Join does not flush Choreographer; another layer or a conflated event may trigger ready before this callback. Signed callback-to-ready can be negative.',
    'Joined workers and tracked resources are not proof of zero native/GPU heap. Pixel equality is within this fixed fixture and does not certify all transforms or a physical display.',
    'Continuous-transform/sparse-update current-device performance and the original dense 30-second gate remain outside this diagnostic.',
    'External official-log integrity, source/APK/environment binding and terminal cleanup are delegated to the supplied completed integrity report; this script independently checks its file hashes and payload arithmetic only.',
]

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path): return json.loads(Path(path).read_text())
def write(path, value): Path(path).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n')
def ms(value): return None if value is None else value/1e6

def frozen_validator():
    contract_path = ROOT/'frozen-readiness/marker-contract.json'
    validator_path = ROOT/'bound-control/markers.py'
    assert sha(contract_path)==CONTRACT_SHA, 'Frozen contract changed'
    assert sha(validator_path)==VALIDATOR_SHA, 'Frozen validator changed'
    spec=importlib.util.spec_from_file_location('frozen_causal_markers',validator_path)
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return read(contract_path),module

def integrity(report, records_path, records, contract):
    assert report['analysis_status']=='COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE' and not report['issues'], 'Whole-run integrity incomplete'
    expected=report['expected']
    assert expected['source_commit']==SOURCE and expected['readiness_git']==READINESS
    assert expected['run_id'] and expected['job_id'] and expected['workflow_sha']
    blocks=[b for b in report['state']['blocks'] if b['context']['ordinal']==13]
    assert len(blocks)==1
    block=blocks[0]; context=block['context']
    assert block['ended'] is True and block['method_pass'] is True and block['record_count']==3
    assert context['identity']=='C1' and context['source_commit']==SOURCE and context['seed']==4101
    assert str(context['run_id'])==str(expected['run_id']) and context['workflow_sha']==expected['workflow_sha']
    assert context['selection']==contract['methods']['13']['selection']
    assert context['test_source_sha256']==contract['methods']['13']['test_source_sha256']
    assert context['junit_status']=='PASS' and context['adb_exit']==0 and context['process_exit_outcome']=='NATURAL_EXIT'
    assert sha(records_path)==context['records_sha256']
    extracted=[x for x in report['extracted_records'] if x['ordinal']==13]
    assert len(extracted)==1
    extracted=extracted[0]
    assert extracted['sha256']==sha(records_path) and Path(extracted['path']).name==Path(records_path).name
    assert extracted['record_count']==3 and all(extracted[k] is True for k in ['block_closed','method_pass','whole_run_complete'])
    assert report['state']['final']==report['state']['final_observed'] and len(report['state']['methods'])==13
    final=report['state']['final']
    assert final['status']=='EXECUTION_COMPLETE_ANALYSIS_PENDING' and final['cleanup_verified'] is True
    assert final['packaging_complete'] is True and final['missing_files']==[]
    assert len(final['install_groups'])==2 and all(x['status']=='PASS' for x in final['install_groups'].values())
    assert [x['ordinal'] for x in final['methods']]==list(range(1,14)) and all(x['status']==x['junit_status']=='PASS' for x in final['methods'])
    assert set(final['cleanup'])=={'trap','always'}
    assert all(x['error_count']==0 and x['exact_match'] is True and x['emulator']==x['command_groups']=='STOPPED_CONFIRMED' for x in final['cleanup'].values())
    assert context['environment_sha256']==final['device_before_sha256']==final['device_after_sha256']
    assert [x['sequence'] for x in block['ordered']]==list(range(3)) and [x['marker'] for x in block['ordered']]==[x['marker'] for x in records]
    # The official parser owns exact FINAL/cleanup/source/APK/environment semantic validation.
    # Reconstruct the compact raw_result format as a second records-file identity check.
    assert hashlib.sha256((json.dumps(records,ensure_ascii=False,separators=(',',':'))+'\n').encode()).hexdigest()==context['records_sha256']
    return {'external_analysis_status':report['analysis_status'],'expected':expected,'method_context':context,
            'final':report['state']['final'],'extracted_record_binding':extracted}

def analyze(records, contract, validator):
    assert type(records) is list and len(records)==3
    assert all(set(r)=={'marker','data'} and r['marker']=='RASTER_CAUSAL_DIAGNOSTIC' for r in records)
    for r in records:validator.validate_shape(r['data'],contract['schemas'][r['marker']])
    validator.validate_raster(records)
    groups=[]
    for record in records:
        d=record['data']; result=d['result']; n=result['layers']
        assert d['fixture']['label']==f'raster-{n}-before' and d['fixture']['seed']==4101
        expected={(role,phase,layer):revision for role,phase,revision in PHASES for layer in range(n)}
        keys=[(f['role'],f['phase'],f['layer']) for f in d['frames']]
        assert len(keys)==len(set(keys)) and set(keys)==set(expected), 'Missing/duplicate phase-layer frame'
        meta_keys=[(m['phase'],m['layer']) for m in d['metadata']]
        assert len(meta_keys)==len(set(meta_keys)) and set(meta_keys)=={(phase,layer) for _,phase,layer in expected}, 'Incomplete metadata correlation'
        metadata={(m['phase'],m['layer']):m for m in d['metadata']}
        frames=[]
        for f in d['frames']:
            assert f['revision']==expected[(f['role'],f['phase'],f['layer'])]
            t=f['timesNanos']; assert all(t[a]<=t[b] for a,b in zip(CORE,CORE[1:]))
            assert t['WORKER_FINALLY']>=t['PUBLISHED']
            assert not {'FAILED','BUDGET_BUSY','SUPERSEDED'} & set(t)
            assert f['nativeAttempts']==f['nativeCompleted']
            if 'NOTIFY_CALLBACK' in t:assert 'NOTIFY_ENQUEUE' in t and t['NOTIFY_ENQUEUE']<=t['NOTIFY_CALLBACK']
            timing=metadata[(f['phase'],f['layer'])]
            for cpu,a,b in [('queryAndDigestThreadCpuNanos','queryStartThreadId','hexStartThreadId'),('hexAndResultThreadCpuNanos','hexStartThreadId','hexEndThreadId')]:
                if timing[cpu] is not None:assert timing[a]==timing[b], 'Invalid cross-thread CPU sample'
            row={'role':f['role'],'phase':f['phase'],'layer':f['layer'],'generation':f['generation'],'revision':f['revision'],
                 'wall':{name:ms(t[b]-t[a]) if a in t and b in t else None for name,(a,b) in SEGMENTS.items()},
                 'native':{'attempts':f['nativeAttempts'],'completed':f['nativeCompleted'],'preparationAndBuildWallMs':ms(f['nativePreparationAndBuildWallNanos']),'preparationAndBuildThreadCpuMs':ms(f['nativePreparationAndBuildThreadCpuNanos'])},
                 'metadata':dict(timing), 'notification':{k:f[k] for k in ['notificationRequests','notificationCallbacks','notificationsSuperseded']},
                 'callbackAfterReady':t['NOTIFY_CALLBACK']>t['READY_DRAW_START'] if 'NOTIFY_CALLBACK' in t else None,
                 'rawTimesNanos':t,'rawThreadIds':f['threadIds']}
            frames.append(row)
        for role in ['active','full']:
            for layer in range(n):
                generations=[f['generation'] for f in d['frames'] if f['role']==role and f['layer']==layer]
                assert all(a<b for a,b in zip(generations,generations[1:]))
        phases=[]
        for role,phase,_ in PHASES:
            fs=[f for f in frames if f['role']==role and f['phase']==phase]
            rs=[r for r in d['reads'] if r['phase']==phase]
            def cpu_sum(values):return sum(values) if all(x is not None for x in values) else None
            phases.append({'role':role,'phase':phase,'layers':n,
                'phaseFrameSpanMs':ms(max(f['rawTimesNanos']['READY_DRAW_END'] for f in fs)-min(f['rawTimesNanos']['REQUEST'] for f in fs)),
                'accumulatedLayerWallMs':{k:sum(f['wall'][k] for f in fs) if all(f['wall'][k] is not None for f in fs) else None for k in SEGMENTS},
                'nativeAttempts':sum(f['native']['attempts'] for f in fs),'nativeCompleted':sum(f['native']['completed'] for f in fs),
                'nativePreparationAndBuildWallMs':sum(f['native']['preparationAndBuildWallMs'] for f in fs),
                'nativePreparationAndBuildThreadCpuMs':cpu_sum([f['native']['preparationAndBuildThreadCpuMs'] for f in fs]),
                'nativeCpuUnavailableFrames':sum(f['native']['preparationAndBuildThreadCpuMs'] is None for f in fs),
                'metadata':{k:sum(f['metadata'][k] for f in fs) if all(f['metadata'][k] is not None for f in fs) else None for k in ['regions','preparationBatches','preparationRows','queryBatches','metadataRows','admissionAndPreparationNanos','queryAndDigestNanos','queryAndDigestThreadCpuNanos','hexAndResultNanos','hexAndResultThreadCpuNanos']},
                'readBatches':len(rs),'reads':{k:sum(r[k] for r in rs) for k in ['freezeNanos','decodeNanos','entries','payloadBytes','decodedPoints','candidateRows']},
                'notifications':{k:sum(f['notification'][k] for f in fs) for k in ['notificationRequests','notificationCallbacks','notificationsSuperseded']},
                'missingCallbackFrames':sum(f['wall']['enqueueToCallbackMs'] is None for f in fs),
                'callbackAfterReadyFrames':sum(f['callbackAfterReady'] is True for f in fs)})
        local=next(p for p in phases if p['phase']=='local')
        assert local['nativeCompleted']==result['nativeStrokeBuilds']
        assert 0<result['localDecoded']<result['fullDecoded']==321
        assert 0<result['localRebuiltTiles']<result['fullRebuiltTiles']
        groups.append({'layers':n,'fixture':d['fixture'],'result':result,'joined':d['joined'],'resources':d['resources'],
                       'phases':phases,'frames':frames,'rawReads':d['reads'],
                       'readEntriesEqualLocalDecoded':local['reads']['entries']==result['localDecoded']})
    return groups

def markdown(result):
    lines=['# Raster causal diagnostic arithmetic','',f"Status: **{result['analysis_status']}**. Scope: diagnostic only; no performance acceptance.",'',
           'Each fixture has one observation. Values below are not a repeated-sample distribution or comparison against the old public probe.','',
           '| Layers | First ms | Append ms | Forced full ms | Local/full decode | Local/full rebuild | Local native | Pixels / joined |',
           '|---:|---:|---:|---:|---:|---:|---:|---|']
    def fmt(v):return 'null' if v is None else f'{v:.6f}'
    for g in result['groups']:
        r=g['result'];lines.append(f"| {g['layers']} | {fmt(r['firstCompleteMs'])} | {fmt(r['appendCompleteMs'])} | {fmt(r['forcedFullCompleteMs'])} | {r['localDecoded']}/{r['fullDecoded']} | {r['localRebuiltTiles']}/{r['fullRebuiltTiles']} | {r['nativeStrokeBuilds']} | {r['pixelsEqual']} / {g['joined']} |")
    lines+=['','Per-phase sums are accumulated layer intervals and may overlap. CPU null stays null; no CPU is inferred for request dispatch, decode, renderer or Choreographer.','',
            '| Layers / phase | Frame span ms | Metadata wall sum ms | Query CPU sum ms | Hex CPU sum ms | Native wall / CPU sum ms | Read entries / decode wall ms |',
            '|---|---:|---:|---:|---:|---:|---:|']
    for g in result['groups']:
        for p in g['phases']:
            lines.append(f"| {g['layers']} / {p['phase']} | {fmt(p['phaseFrameSpanMs'])} | {fmt(p['accumulatedLayerWallMs']['metadataMs'])} | {fmt(ms(p['metadata']['queryAndDigestThreadCpuNanos']))} | {fmt(ms(p['metadata']['hexAndResultThreadCpuNanos']))} | {fmt(p['nativePreparationAndBuildWallMs'])} / {fmt(p['nativePreparationAndBuildThreadCpuMs'])} | {p['reads']['entries']} / {fmt(ms(p['reads']['decodeNanos']))} |")
    lines+=['','All frame intervals (ms); callback→ready is signed and can be negative when another event triggers ready first.','',
            '| Layers / phase / layer | Request→worker | Metadata | Raster | Prepare | Prepare→Main | Main publish | Enqueue→callback | Callback→ready signed | Ready draw |',
            '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for g in result['groups']:
        for f in g['frames']:
            vals=[f['wall'][k] for k in ['requestToWorkerMs','metadataMs','rasterMs','prepareMs','prepareEndToMainEntryMs','mainPublicationMs','enqueueToCallbackMs','callbackToReadyDrawSignedMs','readyDrawMs']]
            lines.append(f"| {g['layers']} / {f['phase']} / {f['layer']} | "+' | '.join(map(fmt,vals))+' |')
    lines+=['','Limits remain open:','']+['- '+x for x in LIMITS]
    return '\n'.join(lines)+'\n'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--records',required=True,type=Path)
    parser.add_argument('--integrity-report',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    try:
        contract,validator=frozen_validator();records=read(args.records);report=read(args.integrity_report)
        binding=integrity(report,args.records,records,contract)
        groups=analyze(records,contract,validator)
        result={'analysis_status':'COMPLETE_SCOPED_RASTER_DIAGNOSTIC_ARITHMETIC','scope':'DIAGNOSTIC_ONLY','performance_acceptance':'NOT_EVALUATED',
                'inputs':{'records':str(args.records),'records_sha256':sha(args.records),'integrity_report':str(args.integrity_report),'integrity_report_sha256':sha(args.integrity_report),
                          'frozen_contract_sha256':CONTRACT_SHA,'frozen_validator_sha256':VALIDATOR_SHA,'analyzer_sha256':sha(__file__)},
                'binding':binding,'groups':groups,'limitations':LIMITS}
        write(args.out/'analysis.json',result);(args.out/'analysis.md').write_text(markdown(result))
        print(json.dumps({'analysis_status':result['analysis_status'],'groups':len(groups),'frames':sum(len(g['frames']) for g in groups),'performance_acceptance':'NOT_EVALUATED'}))
        return 0
    except (AssertionError,KeyError,TypeError,ValueError,OSError) as error:
        failure={'analysis_status':'INCOMPLETE','scope':'DIAGNOSTIC_ONLY','performance_acceptance':'NOT_EVALUATED','error_type':type(error).__name__,'reason':str(error)}
        write(args.out/'analysis.json',failure);(args.out/'analysis.md').write_text('# Raster causal diagnostic arithmetic\n\nINCOMPLETE. No performance conclusion. See analysis.json.\n')
        print(json.dumps(failure));return 2

if __name__=='__main__':
    if not __debug__:raise SystemExit('Do not disable validation with python -O')
    sys.exit(main())
