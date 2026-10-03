#!/usr/bin/env python3
"""Run extracted tab state/compact condition on the host, NOT a real Android VM/UI.
Uses only cached Kotlin/JDK; --ref COMMIT reads an unchanged historical source.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ref')
args = parser.parse_args()
root = Path(__file__).resolve().parents[3]
base = 'android/app/src/main/java/org/inkweft/app/'
def source(name):
    if args.ref:
        return subprocess.check_output(['git', 'show', f'{args.ref}:{base}{name}'], cwd=root, text=True)
    return (root / base / name).read_text()
session, workspace = source('StudySession.kt'), source('StudyWorkspace.kt')
lines = session.splitlines()
state = '\n'.join(line for i, line in enumerate(lines) if (
    re.match(r'    (private val restoredTab|var lastTab|fun selectTab|var compactInitialized)\b', line)
    or line.strip() == 'private set' and 'var lastTab' in lines[i - 1]))
condition = re.search(r'LaunchedEffect\(compactWindow\)\{(.+)\}', workspace).group(1)
assert 'var lastTab' in state and 'fun selectTab' in state and 'compactInitialized' in condition
print(f'Source: {args.ref or "working tree"}', flush=True)
print('Extracted production state:\n' + state + '\nExtracted compact effect:\n' + condition, flush=True)
program = '''
import kotlin.reflect.KProperty
// Only the SavedStateHandle map and Compose Int delegate are stand-ins.
class SavedStateHandle(initial: Map<String, Any?> = emptyMap()) {
    val values = initial.toMutableMap()
    @Suppress("UNCHECKED_CAST") operator fun <T> get(key: String): T? = values[key] as T?
    operator fun <T> set(key: String, value: T) { values[key] = value }
}
class IntState(var value: Int) {
    operator fun getValue(owner: Any?, property: KProperty<*>) = value
    operator fun setValue(owner: Any?, property: KProperty<*>, next: Int) { value = next }
}
fun mutableIntStateOf(value: Int) = IntState(value)
class ExtractedTabState(private val saved: SavedStateHandle) {
''' + state + '''
}
fun initialize(vm: ExtractedTabState, compactWindow: Boolean = true) { ''' + condition + ''' }
fun main() {
    fun verify(label: String, expected: Int, actual: Int) {
        check(expected == actual) { "$label: expected=$expected actual=$actual" }
    }
    // This is the original failure: saved outline tab 1 was overwritten with map tab 2.
    val restored = ExtractedTabState(SavedStateHandle(mapOf("study.tab" to 1)))
    initialize(restored)
    verify("restored tab survives compact initialization", 1, restored.lastTab)
    println("PASS restored tab=1 survives compact initialization")
    for (tab in 0..2) {
        val saved = SavedStateHandle(mapOf("study.tab" to tab, "study.map" to "synthetic-map"))
        val vm = ExtractedTabState(saved)
        repeat(2) { initialize(vm) }
        verify("restored $tab", tab, vm.lastTab)
        check(saved.values["study.map"] == "synthetic-map")
        val fresh = ExtractedTabState(SavedStateHandle(saved.values.toMap()))
        initialize(fresh)
        verify("new state instance $tab", tab, fresh.lastTab)
    }
    for (tab in listOf(null, -1, 3, Int.MIN_VALUE, Int.MAX_VALUE, "bad")) {
        val vm = ExtractedTabState(SavedStateHandle(mapOf("study.tab" to tab)))
        verify("safe initial fallback $tab", 0, vm.lastTab)
        initialize(vm, false)
        verify("full-window fallback $tab", 0, vm.lastTab)
        initialize(vm)
        verify("first compact default $tab", 2, vm.lastTab)
    }
    for (tab in 0..2) {
        val saved = SavedStateHandle()
        val vm = ExtractedTabState(saved)
        vm.selectTab(tab)
        repeat(2) { initialize(vm) }
        verify("explicit choice $tab", tab, vm.lastTab)
        val fresh = ExtractedTabState(SavedStateHandle(saved.values.toMap()))
        initialize(fresh)
        verify("explicit choice in new state instance $tab", tab, fresh.lastTab)
    }
    println("PASS restored 0/1/2, fresh/default, invalid range/type, explicit selection, new state instances")
    println("Android ViewModel, Compose runtime/UI, SavedStateRegistry and device execution: NOT_RUN")
}
'''
toolchain = Path(os.environ.get('INKWEFT_TOOLCHAIN', '/workspace/shared/inkweft-toolchain'))
cache = toolchain / 'gradle-user-home/caches/modules-2/files-2.1'
def jar(group, artifact, version):
    return next((cache / group / artifact / version).glob('*/*.jar'))
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.10')
jars = [stdlib] + [jar('org.jetbrains.kotlin', name, version) for name, version in (
    ('kotlin-compiler-embeddable', '2.3.10'), ('kotlin-script-runtime', '2.3.10'),
    ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.3.10'))]
jars += [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), jar('org.jetbrains', 'annotations', '13.0')]
java = str(toolchain / 'jdk17/bin/java')
with tempfile.TemporaryDirectory(prefix='study-tab-') as folder:
    kotlin = Path(folder) / 'TabCheck.kt'
    kotlin.write_text(program)
    output = Path(folder) / 'classes'
    subprocess.run([java, '-cp', os.pathsep.join(map(str, jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-jvm-target', '17', '-d', str(output), str(kotlin)], check=True)
    raise SystemExit(subprocess.run([java, '-cp', f'{output}{os.pathsep}{stdlib}', 'TabCheckKt']).returncode)
