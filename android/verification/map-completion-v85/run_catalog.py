import argparse,pathlib,subprocess,os,json
p=argparse.ArgumentParser();p.add_argument('--repo',required=True);p.add_argument('--kotlin-tools',required=True);p.add_argument('--java',default='java');p.add_argument('--output',required=True);a=p.parse_args()
repo=pathlib.Path(a.repo);tool=pathlib.Path(a.kotlin_tools);out=pathlib.Path(a.output);out.mkdir(parents=True,exist_ok=True)
compiler=os.pathsep.join(map(str,tool.glob('*.jar')));cp=os.pathsep.join([str(repo/'android/core-domain/build/classes/kotlin/main'),str(tool/'kotlin-stdlib-2.3.10.jar'),str(tool/'annotations-13.0.jar')])
subprocess.run([a.java,'-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(out),str(pathlib.Path(__file__).with_name('TemplateCatalogProbe.kt'))],check=True)
raw=subprocess.check_output([a.java,'-cp',str(out)+os.pathsep+cp,'TemplateCatalogProbeKt'],text=True)
data=json.loads(raw);assert len(data)==12
(out/'template-catalog.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n');print('CATALOG',len(data),'templates;',sum(len(x['nodes']) for x in data),'nodes')
