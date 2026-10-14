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
args=(':app:connectedDebugAndroidTest' '--no-daemon')
if [[ -n "$selector" ]]; then
  args+=("-Pandroid.testInstrumentationRunnerArguments.class=$selector")
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
    'syntheticDirectory': 'files/common-dataset-visual', 'captures': [], 'apkFiles': [], 'issues': [],
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

def own(command, filename, timeout=15):
    result = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as', package,
        '/system/bin/' + command[0], *command[1:], filename], stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, timeout=timeout, check=False)
    if result.returncode != 0:
        raise ValueError()
    return result.stdout

total = 0
if not valid_serial:
    manifest['issues'].append({'code': 'ACTION_EMULATOR_SERIAL_INVALID'})
else:
    directory = 'files/common-dataset-visual'
    try:
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
with (target / 'manifest.json').open('x', encoding='utf-8') as output:
    json.dump(manifest, output, indent=2)
    output.write('\n')
print(json.dumps({'scope': scope, 'gradleExitCode': gradle_status, 'captures': len(manifest['captures']),
    'apkFiles': len(manifest['apkFiles']), 'collectionOutcome': manifest['collectionOutcome']}))
PY
capture_status=$?
if [[ $capture_status != 0 ]]; then
  echo '::warning::CI_SYNTHETIC_DIAGNOSTICS_INCOMPLETE'
fi
exit "$gradle_status"
