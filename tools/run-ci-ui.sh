#!/usr/bin/env bash
# One action script: collect fixed synthetic diagnostics before the runner kills its emulator.
set -u -o pipefail
if [[ $# != 0 || "${GITHUB_ACTIONS:-}" != true ]]; then
  echo '::error::CI_UI_CONTEXT_REQUIRED'
  exit 64
fi
case "${SAVELY_CI_UI_SCOPE:-full}" in
  full) scope=full; selector= ;;
  portfolio-glyph)
    scope=portfolio-glyph
    selector='cc.savely.android.finance.CommonDatasetVisualTest#portfolioKeepsCurrenciesAndShowsCurrentHistoryEndpoint'
    ;;
  *) echo '::error::INVALID_UI_SCOPE'; exit 64 ;;
esac
cd -- "$(dirname -- "$0")/.." || exit 64
if [[ "$scope" == portfolio-glyph ]]; then
  # Direct instrumentation keeps synthetic files available for collection before teardown.
  args=(':app:assembleDebug' ':app:assembleDebugAndroidTest' '--no-daemon')
else
  args=(':app:connectedDebugAndroidTest' '--no-daemon')
fi
# Do not let diagnostics turn failed assertions into a successful workflow.
set +e
bash gradlew "${args[@]}"
gradle_status=$?
python3 - "$scope" "$gradle_status" "$selector" <<'PY'
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tarfile
import threading
import sys

scope, gradle_status, selector = sys.argv[1], int(sys.argv[2]), sys.argv[3]
repo = Path.cwd().resolve()
package = 'cc.savely.android'
run, attempt, source = (os.environ.get(key, '') for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA'))
if not (re.fullmatch(r'[0-9]{1,20}', run) and re.fullmatch(r'[0-9]{1,4}', attempt) and
        re.fullmatch(r'[0-9a-f]{40}', source) and scope in ('full', 'portfolio-glyph')):
    raise SystemExit('CI_DIAGNOSTIC_PROVENANCE_INVALID')
base = repo / 'app/build/reports/androidTests'
target = base / 'ci-synthetic-diagnostics' / f'{run}-{attempt}-{scope}'
if not target.resolve().is_relative_to(repo / 'app/build/reports/androidTests'):
    raise SystemExit('CI_DIAGNOSTIC_PATH_INVALID')
target.mkdir(parents=True, exist_ok=False)
manifest = {'version': 1, 'source': source, 'runId': run, 'runAttempt': attempt,
    'uiScope': scope, 'instrumentationClassSelector': selector or None, 'gradleExitCode': gradle_status,
    'executionMode': 'direct-instrumentation' if scope == 'portfolio-glyph' else 'connected-utp',
    'syntheticDirectory': 'files/common-dataset-visual', 'captures': [], 'apkFiles': [], 'issues': [],
    'phases': [],
    'privateAuthDataCollected': False, 'logcatCollected': False, 'uiTreeCollected': False,
    'apkAttestationScope': 'UI-job Gradle outputs; installed APK bytes are not pulled or independently attested'}
digest = lambda raw: hashlib.sha256(raw).hexdigest()
for name, relative, limit in (
    ('app-debug.apk', 'app/build/outputs/apk/debug/app-debug.apk', 64 * 1024 * 1024),
    ('app-debug-androidTest.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', 32 * 1024 * 1024),
):
    try:
        path = repo / relative
        details = path.lstat()
        if not stat.S_ISREG(details.st_mode) or not 0 < details.st_size <= limit or not path.resolve().is_relative_to(repo / 'app/build/outputs/apk'):
            raise ValueError()
        with path.open('rb') as source_file:
            raw = source_file.read(limit + 1)
        if len(raw) != details.st_size or not raw.startswith(b'PK\x03\x04'):
            raise ValueError()
        with (target / name).open('xb') as output:
            output.write(raw)
        manifest['apkFiles'].append({'name': name, 'sourcePath': relative, 'bytes': len(raw), 'sha256': digest(raw)})
    except Exception:
        manifest['issues'].append({'file': name, 'code': 'APK_UNAVAILABLE_OR_INVALID'})

# Exact names only: never list or archive the app's generic files, preferences or databases.
names = []
for profile in ('it-light-100', 'en-dark-200'):
    for panel in ('usd-summary', 'usd-full-stats-before-frames', 'usd-full-stats-after-frames'):
        for extension in ('png', 'json'):
            names.append(f'finance-portfolio-{profile}-{panel}.{extension}')
    names.append(f'finance-portfolio-{profile}-usd-glyph-ink.json')
serial, port = os.environ.get('ANDROID_SERIAL', ''), os.environ.get('EMULATOR_PORT', '')
valid_serial = bool(re.fullmatch(r'[0-9]{4,5}', port) and serial == f'emulator-{port}' and 5554 <= int(port) <= 5682)

def parse_single_test(raw, returncode, timed_out=False, output_limited=False):
    # Only this fixed synthetic class is invoked; retain counts, never its raw output.
    text = raw.decode('utf-8', errors='replace')
    codes = [int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', text, re.M)]
    counts = re.findall(r'^INSTRUMENTATION_STATUS: numtests=(\d+)\s*$', text, re.M)
    terminal = re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.M)
    ok = len(re.findall(r'^OK \(1 test\)\s*$', text, re.M)) == 1
    aborted = bool(re.search(r'^INSTRUMENTATION_(?:FAILED|ABORTED):|^INSTRUMENTATION_RESULT: shortMsg=', text, re.M))
    passed = (not timed_out and not output_limited and returncode == 0 and codes == [1, 0]
              and bool(counts) and all(value == '1' for value in counts) and terminal == ['-1'] and ok and not aborted)
    return {'started': codes.count(1), 'passed': codes.count(0), 'negativeStatuses': sum(value < 0 for value in codes),
            'declaredOneTest': bool(counts) and all(value == '1' for value in counts),
            'terminalSuccess': terminal == ['-1'], 'okOneTest': ok, 'timedOut': timed_out,
            'outputLimited': output_limited, 'success': passed}


def phase_run(phase, command, timeout=15, capture=False, capture_limit=1024 * 1024):
    output, code, timed_out, limited = b'', 125, False, False
    process, timer = None, None
    expired = threading.Event()
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        if capture:
            def expire():
                expired.set()
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            timer = threading.Timer(timeout, expire)
            timer.daemon = True
            timer.start()
            output = process.stdout.read(capture_limit + 1)
            limited = len(output) > capture_limit
            if limited:
                process.kill()
            code = process.wait(timeout=5)
            timed_out = expired.is_set()
        else:
            code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        if process is not None:
            process.kill()
            process.wait(timeout=5)
    except OSError:
        code = 125
    finally:
        if timer is not None:
            timer.cancel()
        if process is not None and process.stdout is not None:
            process.stdout.close()
    if timed_out:
        code = 124
    elif limited:
        code = 125
        output = b''
    manifest['phases'].append({'phase': phase, 'returncode': code})
    return output, code, timed_out, limited


execution_status = gradle_status
if scope == 'portfolio-glyph' and gradle_status == 0:
    execution_status = 1
    if valid_serial and len(manifest['apkFiles']) == 2:
        _, install_app, _, _ = phase_run('install_app', ['adb', '-s', serial, 'install', '-r', '-t',
            str(repo / 'app/build/outputs/apk/debug/app-debug.apk')], timeout=60)
        if install_app == 0:
            _, install_test, _, _ = phase_run('install_test', ['adb', '-s', serial, 'install', '-r', '-t',
                str(repo / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')], timeout=60)
            if install_test == 0:
                raw, code, timed_out, limited = phase_run('instrument', ['adb', '-s', serial, 'shell', 'am',
                    'instrument', '-w', '-r', '-e', 'class', selector,
                    'cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner'], timeout=180, capture=True)
                manifest['instrumentation'] = parse_single_test(raw, code, timed_out, limited)
                execution_status = 0 if manifest['instrumentation']['success'] else 1
    # Never uninstall/clear the package here: collect the exact synthetic allowlist first.
manifest['executionExitCode'] = execution_status


def own(command, filename, timeout=15):
    phase = 'stat_directory' if filename == 'files/common-dataset-visual' else {
        ('stat', '%F'): 'stat_file', ('stat', '%s'): 'size_file'}.get((command[0], command[-1]), 'read_file')
    capture_limit = int(command[2]) if command[0] == 'head' else 4096
    raw, code, timed_out, limited = phase_run(phase, ['adb', '-s', serial, 'exec-out', 'run-as', package,
        '/system/bin/' + command[0], *command[1:], filename], timeout=timeout, capture=True, capture_limit=capture_limit)
    if code != 0 or timed_out or limited:
        raise ValueError()
    return raw

total = 0
if not valid_serial:
    manifest['issues'].append({'code': 'ACTION_EMULATOR_SERIAL_INVALID'})
else:
    directory = 'files/common-dataset-visual'
    try:
        _, run_as_code, _, _ = phase_run('run_as', ['adb', '-s', serial, 'exec-out', 'run-as', package,
            '/system/bin/true'])
        if run_as_code != 0:
            raise ValueError()
        if own(['stat', '-c', '%F'], directory).strip() != b'directory':
            raise ValueError()
        for name in names:
            try:
                remote = directory + '/' + name
                if own(['stat', '-c', '%F'], remote).strip() != b'regular file':
                    raise ValueError()
                size = int(own(['stat', '-c', '%s'], remote).strip())
                limit = 8 * 1024 * 1024 if name.endswith('.png') else 1024 * 1024
                if not 0 < size <= limit or total + size > 48 * 1024 * 1024:
                    raise ValueError()
                # The remote read itself is capped, including a file that grows after stat.
                raw = own(['head', '-c', str(size + 1)], remote)
                if len(raw) != size:
                    raise ValueError()
                if name.endswith('.png'):
                    if not raw.startswith(b'\x89PNG\r\n\x1a\n') or not raw.endswith(b'\x00\x00\x00\x00IEND\xaeB`\x82'):
                        raise ValueError()
                else:
                    value = json.loads(raw)
                    if not isinstance(value, dict):
                        raise ValueError()
                    if name.endswith('-usd-glyph-ink.json'):
                        if value.get('scope') != 'synthetic-fixed-usd-summary-raster':
                            raise ValueError()
                    elif (value.get('scope') != 'native-composable-local-synthetic-fixture' or
                          value.get('image') != name.removesuffix('.json') + '.png' or
                          value.get('backendPolicyVerified') is not False or value.get('browserParityVerified') is not False):
                        raise ValueError()
                with (target / name).open('xb') as output:
                    output.write(raw)
                manifest['captures'].append({'name': name, 'bytes': size, 'sha256': digest(raw)})
                total += size
            except Exception:
                manifest['issues'].append({'file': name, 'code': 'SYNTHETIC_CAPTURE_MISSING_OR_INVALID'})
    except Exception:
        manifest['issues'].append({'code': 'SYNTHETIC_DIRECTORY_UNAVAILABLE'})

# Host-generated tar contains only the already validated allowlisted files, never remote wildcards.
if manifest['captures']:
    archive = target / 'portfolio-glyph-synthetic.tar'
    with tarfile.open(archive, mode='x') as output:
        for item in manifest['captures']:
            raw = (target / item['name']).read_bytes()
            if digest(raw) != item['sha256']:
                raise SystemExit('HOST_CAPTURE_CHANGED')
            entry = tarfile.TarInfo(item['name'])
            entry.size, entry.mode, entry.mtime = len(raw), 0o600, 0
            output.addfile(entry, io.BytesIO(raw))
    manifest['syntheticArchive'] = {'name': archive.name, 'bytes': archive.stat().st_size,
        'sha256': digest(archive.read_bytes()), 'members': len(manifest['captures'])}
manifest['collectionOutcome'] = 'complete' if len(manifest['captures']) == len(names) and len(manifest['apkFiles']) == 2 and not manifest['issues'] else 'partial'
if scope == 'portfolio-glyph' and execution_status == 0 and manifest['collectionOutcome'] != 'complete':
    execution_status = 1
manifest['executionExitCode'] = execution_status
with (target / 'manifest.json').open('x', encoding='utf-8') as output:
    json.dump(manifest, output, indent=2)
    output.write('\n')
print(json.dumps({'scope': scope, 'gradleExitCode': gradle_status, 'captures': len(manifest['captures']),
    'apkFiles': len(manifest['apkFiles']), 'collectionOutcome': manifest['collectionOutcome'],
    'executionExitCode': execution_status}))
raise SystemExit(execution_status if scope == 'portfolio-glyph' else 0)
PY
capture_status=$?
if [[ $capture_status != 0 ]]; then
  echo '::warning::CI_SYNTHETIC_DIAGNOSTICS_INCOMPLETE'
fi
if [[ "$gradle_status" != 0 ]]; then
  exit "$gradle_status"
fi
if [[ "$scope" == portfolio-glyph ]]; then
  exit "$capture_status"
fi
exit "$gradle_status"
