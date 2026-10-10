# Original synthetic InkWeft probe; no reference-app code is executed.
import argparse,pathlib,subprocess,os,json,statistics
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--kotlin-tools',required=True);p.add_argument('--java',default='java');p.add_argument('--output',required=True);p.add_argument('--baseline',default='5e90daca5a97bf22921d4fa59ecd068d31dcf505');a=p.parse_args()
repo=pathlib.Path(a.repo);tool=pathlib.Path(a.kotlin_tools);out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True);java=a.java;compiler=os.pathsep.join(map(str,tool.glob('*.jar')));cp=os.pathsep.join([str(repo/'android/core-domain/build/classes/kotlin/main'),str(tool/'kotlin-stdlib-2.3.10.jar'),str(tool/'annotations-13.0.jar')]);results={}
for version in ['v83','v84']:
 d=out/version;d.mkdir(exist_ok=True);sources=[]
 for filename in ['StudyOutline.kt','StudyOrganization.kt']:
  relative='android/core-domain/src/main/kotlin/org/inkweft/core/'+filename
  if version=='v83':
   file=d/filename;file.write_bytes(subprocess.check_output(['git','show',a.baseline+':'+relative],cwd=repo))
  else:file=repo/relative
  sources.append(str(file))
 subprocess.run([java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(d),*sources,str(pathlib.Path(__file__).with_name('MapAlgorithmProbe.kt'))],check=True)
 raw=subprocess.check_output([java,'-cp',str(d)+os.pathsep+cp,'MapAlgorithmProbeKt'],text=True);results[version]=json.loads(raw)
results['comparison']={'scope':'WARM_HOST_JVM_SYNTHETIC_ALGORITHMS_NOT_ANDROID_UI_OR_MUBU_BENCHMARK','projectionHashesEqual':results['v83']['projectionHashes']==results['v84']['projectionHashes'],'scenarios':len(results['v84']['projectionHashes']),'medianDeepOutlineMs':{v:statistics.median(results[v]['deepOutlineMs']) for v in ['v83','v84']},'singleSidedCoordinatesUnchanged':all(x['coordinateSha256']==y['coordinateSha256'] for x,y in zip(results['v83']['layoutScenarios'],results['v84']['layoutScenarios']) if x['layout']=='right')}
assert results['comparison']['projectionHashesEqual'] and results['comparison']['singleSidedCoordinatesUnchanged']
(out/'algorithm-probes.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results['comparison'],indent=2))
