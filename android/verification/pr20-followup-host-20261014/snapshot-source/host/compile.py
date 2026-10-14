from pathlib import Path
import hashlib,json,os,subprocess,time,datetime
root=Path("/workspace/InkWeft")
out=Path(__file__).resolve().parent
command=["/workspace/.setup/tools/gradle-9.4.1/bin/gradle",":data-local:compileDebugAndroidTestKotlin",":app:compileDebugAndroidTestKotlin","--daemon","--max-workers=1","--console=plain","-Dorg.gradle.jvmargs=-Xmx2048m","-Dfile.encoding=UTF-8","-Pkotlin.compiler.execution.strategy=in-process","--offline"]
def inputs():
 paths=subprocess.check_output(["rg","--files","android","-g","!**/build/**","-g","!**/verification/**"],cwd=root,text=True).splitlines()
 return {p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in sorted(paths) if "/src/" in p or p.endswith((".gradle.kts",".gradle","gradle.properties","libs.versions.toml"))}
before=inputs(); started=time.monotonic()
(out/"compile1-start.json").write_text(json.dumps({"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"command":command,"inputs":before},indent=2)+"\n")
env=os.environ.copy();env.update(JAVA_HOME="/workspace/.setup/tools/jdk-17.0.16+8",GRADLE_USER_HOME="/workspace/.setup/gradle",ANDROID_HOME="/workspace/.setup/tools/android-sdk",ANDROID_USER_HOME="/workspace/.setup/android-user",JAVA_TOOL_OPTIONS="-Duser.home=/workspace/.setup/java-home -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts")
env["PATH"]=env["JAVA_HOME"]+"/bin:"+env.get("PATH","")
with (out/"compile1.log").open("w") as log:
 result=subprocess.run(command,cwd=root/"android",env=env,stdout=log,stderr=subprocess.STDOUT)
after=inputs(); changed=[p for p in sorted(before.keys()|after.keys()) if before.get(p)!=after.get(p)]
receipt={"exit":result.returncode,"seconds":time.monotonic()-started,"inputs_unchanged":not changed,"changed_inputs":changed,"inputs":after,"logSha256":hashlib.sha256((out/"compile1.log").read_bytes()).hexdigest(),"scope":"host Kotlin compilation only; not Android execution or native performance"}
(out/"compile1-result.json").write_text(json.dumps(receipt,indent=2)+"\n")
print(json.dumps({k:v for k,v in receipt.items() if k!="inputs"},indent=2))
raise SystemExit(result.returncode)
