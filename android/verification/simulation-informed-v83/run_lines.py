import json,pathlib,subprocess,os
import argparse
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--capture',required=True);p.add_argument('--output',required=True);p.add_argument('--kotlin-tools',required=True);p.add_argument('--java',default='java');p.add_argument('--baseline',default='a778addac0e9e0cd9e741248269efafe05aea33b');a=p.parse_args()
repo=pathlib.Path(a.repo);cap=pathlib.Path(a.capture);out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True);tool=pathlib.Path(a.kotlin_tools);java=a.java;compiler=os.pathsep.join(map(str,tool.glob('*.jar')));classpath=os.pathsep.join([str(repo/'android/core-domain/build/classes/kotlin/main'),str(tool/'kotlin-stdlib-2.3.10.jar'),str(tool/'annotations-13.0.jar')]);cases=json.loads((cap/'reports/REC-02-model-analysis.json').read_text())['cases'];rows=[]
for c in cases:
 f=json.loads((cap/'fixtures/recognition-input/cases/REC-02'/f"{c['id']}.json").read_text());ids={s['fixtureStrokeId']:s['nativeStrokeId'] for s in c['sourceMapping']};groups={}
 for s in f['expected']['recognition']['strokes']:groups.setdefault(s['lineId'],[]).append(ids[s['strokeId']])
 expected=';'.join(sorted(','.join(sorted(g)) for g in groups.values()))
 rel=c['sourceResult']['path'].replace('\\','/').split('/Simulation-V80/')[1];source=(cap/rel).with_name('source.inkweft')
 if not source.exists():raise Exception(source)
 rows.append('\t'.join([c['id'],str(source),expected]))
(out/'line-input.tsv').write_text('\n'.join(rows)+'\n')
results={}
for version in ['v82','v83']:
 d=out/version;d.mkdir(exist_ok=True)
 if version=='v82':
  source=out/'HandwritingLines-v82.kt';source.write_bytes(subprocess.check_output(['git','show',a.baseline+':android/core-domain/src/main/kotlin/org/inkweft/core/HandwritingLines.kt'],cwd=repo))
 else:source=repo/'android/core-domain/src/main/kotlin/org/inkweft/core/HandwritingLines.kt'
 subprocess.run([java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',classpath,'-d',str(d),str(source),str(pathlib.Path(__file__).with_name('LineProbe.kt'))],check=True)
 raw=subprocess.check_output([java,'-cp',str(d)+os.pathsep+classpath,'LineProbeKt',str(out/'line-input.tsv')],text=True)
 result=[]
 for line in raw.splitlines():
  c,e,g,p,h=line.split('\t');eligible=next(x for x in cases if x['id']==c)['eligibility']['eligibleForSyntheticTextAccuracy'];result.append({'case':c,'eligibleForLineTruth':eligible,'partitionMatchesFixture':(e=='true') if eligible else None,'groups':int(g),'sourceIdsConserved':p=='true','unchangedAuthorSha256':h})
 results[version]=result
(out/'line-replay.json').write_text(json.dumps({'scope':'ACTUAL_CORE_SPLITTER_ON_CAPTURED_V80_INK_NOT_MODEL_OR_ANDROID_TEST','versions':results},indent=2)+'\n')
for i,a in enumerate(results['v82']):
 b=results['v83'][i]
 if a['partitionMatchesFixture']!=b['partitionMatchesFixture']:print(a['case'],a['partitionMatchesFixture'],'->',b['partitionMatchesFixture'])
print('correct', {k:sum(x['partitionMatchesFixture'] is True for x in v if x['eligibleForLineTruth']) for k,v in results.items()})
