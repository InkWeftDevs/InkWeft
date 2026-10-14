"""Offline real-Gradle fixture for the opt-in private task cache.

Uses no Android tasks, external plugins/dependencies, daemon reuse or networking.
Every invocation writes a fresh scratch evidence directory; existing data is kept.
The manifest hashes identify caller-verified inputs, not an automatic source audit.
The real formal driver must retain all source/APK/dependency/native checks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    authorized_root = Path('/workspace/scratch/pr20-build-cache')
    if output == authorized_root or authorized_root not in output.parents:
        parser.error('Use a new directory under /workspace/scratch/pr20-build-cache')
    output.mkdir(parents=True, exist_ok=False)
    init = Path(__file__).with_name('pr20_build_cache.init.gradle').resolve()
    gradle = args.gradle.resolve()
    java = args.java_home.resolve() / 'bin/java'
    env = os.environ.copy()
    env.update(JAVA_HOME=str(args.java_home.resolve()),
               GRADLE_USER_HOME=str(output / 'gradle-home'),
               INKWEFT_TASK_CACHE_DIR=str(output / 'task-cache'))
    # A supplied RUNNER_TEMP must not make the outside-directory negative case valid.
    env.pop('RUNNER_TEMP', None)
    version = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=True)
    (output / 'java-version.log').write_text(version.stdout)
    properties = {key.strip(): value.strip() for line in version.stdout.splitlines()
                  if ' = ' in line for key, value in [line.split(' = ', 1)]}
    build = '''
import org.gradle.api.tasks.*
@CacheableTask
abstract class FixtureOutput extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSourceFile()
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getDependencyFile()
    @Input abstract Property<String> getToolIdentity()
    @OutputFile abstract RegularFileProperty getResultFile()
    @TaskAction void produce() {
        def target = resultFile.get().asFile
        target.parentFile.mkdirs()
        target.text = sourceFile.get().asFile.text + dependencyFile.get().asFile.text + toolIdentity.get()
    }
}
tasks.register('fixtureOutput', FixtureOutput) {
    sourceFile = layout.projectDirectory.file('input.txt')
    dependencyFile = layout.projectDirectory.file('dependency.txt')
    toolIdentity = providers.gradleProperty('fixtureTool').orElse('tool-A')
    resultFile = layout.buildDirectory.file('result.txt')
}
'''
    settings = "rootProject.name = 'private-cache-fixture'\n"
    identity = dict(schema=1, gradle_version='9.4.1',
                    java_version=properties['java.version'], java_vendor=properties['java.vendor'],
                    source_spec_sha256=sha(build.encode()), probe_manifest_sha256=sha(b'fixture-no-Android-probes'),
                    toolchain_sha256=sha((gradle.parent.parent / 'lib/gradle-launcher-9.4.1.jar').read_bytes()),
                    dependency_manifest_sha256=sha(b'fixture-local-file-dependency'))
    records = []

    def invoke(name, source='source-A\n', dependency='dependency-A\n', tool='tool-A',
               expected='EXECUTED', manifest=None, overrides=None, remote=False, cache=True):
        project = output / 'projects' / name
        project.mkdir(parents=True)
        (project / 'build.gradle').write_text(build)
        settings_text = settings
        if remote:
            settings_text += "buildCache { remote(HttpBuildCache) { url = uri('http://127.0.0.1:9/'); allowInsecureProtocol = true; push = true } }\n"
        (project / 'settings.gradle').write_text(settings_text)
        (project / 'input.txt').write_text(source)
        (project / 'dependency.txt').write_text(dependency)
        identity_file = project / 'cache-identity.json'
        identity_file.write_text(json.dumps(identity if manifest is None else manifest, sort_keys=True) + '\n')
        child = env.copy()
        child.update(INKWEFT_TASK_CACHE_IDENTITY_FILE=str(identity_file),
                     INKWEFT_TASK_CACHE_IDENTITY_SHA256=sha(identity_file.read_bytes()))
        for key, value in (overrides or {}).items():
            if value is None:
                child.pop(key, None)
            else:
                child[key] = value
        command = [str(gradle), '--offline', '--no-daemon', '--max-workers=1', '--no-configuration-cache',
                   '--console=plain', '--build-cache' if cache else '--no-build-cache',
                   '-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8',
                   '--init-script', str(init), 'fixtureOutput', '-PfixtureTool=' + tool]
        start = time.monotonic()
        result = subprocess.run(command, cwd=project, env=child, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=120)
        elapsed = time.monotonic() - start
        logfile = output / (name + '.log')
        logfile.write_text(result.stdout)
        target = project / 'build/result.txt'
        outcome = 'FROM-CACHE' if '> Task :fixtureOutput FROM-CACHE' in result.stdout else (
            'EXECUTED' if '> Task :fixtureOutput\n' in result.stdout and result.returncode == 0 else 'REJECTED')
        record = dict(case=name, command=command, cwd=str(project), seconds=elapsed,
                      exit_code=result.returncode, expected=expected, actual=outcome,
                      identity_sha256=sha(identity_file.read_bytes()), log_sha256=sha(logfile.read_bytes()),
                      output_sha256=sha(target.read_bytes()) if target.exists() else None)
        records.append(record)
        (output / 'results.json').write_text(json.dumps(dict(status='RUNNING', cases=records), indent=2) + '\n')
        assert outcome == expected, record
        if expected != 'REJECTED':
            assert result.returncode == 0 and target.read_text() == source + dependency + tool
            assert 'remote=false' in result.stdout
        else:
            assert result.returncode != 0 and not target.exists()
        print(json.dumps(record), flush=True)

    invoke('01-cold')
    invoke('02-independent-checkout-hit', expected='FROM-CACHE')
    invoke('03-source-change', source='source-B\n')
    invoke('04-dependency-content-change', dependency='dependency-B\n')
    invoke('05-task-tool-input-change', tool='tool-B')
    changed_toolchain = dict(identity, toolchain_sha256=sha(b'different-declared-toolchain'))
    invoke('06-toolchain-namespace-change', manifest=changed_toolchain)
    invoke('07-remote-disabled-hit', remote=True, expected='FROM-CACHE')
    invoke('08-identity-hash-mismatch', overrides={'INKWEFT_TASK_CACHE_IDENTITY_SHA256': '0'*64}, expected='REJECTED')
    invoke('09-outside-directory', overrides={'INKWEFT_TASK_CACHE_DIR': '/tmp/not-authorized-pr20-cache'}, expected='REJECTED')
    invoke('10-missing-directory', overrides={'INKWEFT_TASK_CACHE_DIR': None}, expected='REJECTED')
    invoke('11-wrong-gradle-identity', manifest=dict(identity, gradle_version='0.0'), expected='REJECTED')
    invoke('12-cache-not-enabled', cache=False, expected='REJECTED')
    report = dict(status='PASS', scope='Offline non-Android Gradle9.4.1 synthetic task cache fixture; not Android/native performance.',
                  cache_initially_empty=True, remote_cache_used=False, cases=records,
                  toolchain_note='The actual JDK/Gradle binaries remain fixed. Cases5/6 mutate a declared task-tool input and toolchain fingerprint; case11 rejects an incompatible Gradle version declaration.',
                  script_sha256=sha(Path(__file__).read_bytes()), init_script_sha256=sha(init.read_bytes()),
                  java_version=properties['java.version'], java_vendor=properties['java.vendor'])
    (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': 'PASS', 'cases': len(records), 'output': str(output)}))


if __name__ == '__main__':
    main()
