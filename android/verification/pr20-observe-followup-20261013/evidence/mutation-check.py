from pathlib import Path
import hashlib, json, shutil, subprocess, time
import xml.etree.ElementTree as ET

repo = Path('/workspace/InkWeft')
source = repo / 'android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchObservation.kt'
original = source.read_bytes()
assert original.count(b'.drop(1)') == 1
mutant = original.replace(b'.drop(1)', b'')
command = ['bash', '/workspace/scratch/run-inkweft-gradle-pan', '--offline',
           ':data-local:testDebugUnitTest', '--tests', 'org.inkweft.data.LibrarySearchObservationTest']
started = time.time()
record = {'command': command, 'working_directory': str(repo/'android'),
          'mutation': 'remove only .drop(1), restoring the virtual runningFold seed',
          'fixed_source_sha256': hashlib.sha256(original).hexdigest(),
          'mutated_source_sha256': hashlib.sha256(mutant).hexdigest()}
try:
    source.write_bytes(mutant)
    with open('/workspace/scratch/pr20-observe-mutation.log', 'w') as output:
        completed = subprocess.run(command, cwd=repo/'android', stdout=output, stderr=subprocess.STDOUT)
    record['gradle_exit_code'] = completed.returncode
    xml = repo/'android/data-local/build/test-results/testDebugUnitTest/TEST-org.inkweft.data.LibrarySearchObservationTest.xml'
    shutil.copyfile(xml, '/workspace/scratch/pr20-observe-mutation-junit.xml')
    result = ET.parse(xml).getroot()
    record['tests'] = int(result.attrib['tests'])
    record['failed_tests'] = [case.attrib['name'] for case in result.findall('testcase') if case.find('failure') is not None]
finally:
    source.write_bytes(original)
    record['restored_byte_identical'] = source.read_bytes() == original
    record['elapsed_seconds'] = time.time() - started
    Path('/workspace/scratch/pr20-observe-mutation.json').write_text(json.dumps(record, indent=2)+'\n')
assert record['restored_byte_identical']
assert record['gradle_exit_code'] != 0
assert {'initialRoomEventStartsOneFullDebounceWithoutASyntheticRead',
        'noReadOrVersionExistsUntilRoomEmitsItsFirstSnapshot'} <= set(record['failed_tests'])
print(json.dumps(record, indent=2))
