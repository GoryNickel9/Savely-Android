"""Explicit opt-in QA A native Auth qualification. No account/email/configuration changes.

Prepare build/device separately; this helper never installs or starts an emulator.
Only --execute permits network/device use. Imports do not perform any operation.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
from urllib.request import Request, HTTPRedirectHandler, build_opener

ROOT = Path(__file__).resolve().parent.parent
PROJECT = 'crqnfbahytzenisospcx'
PROVISIONING_RUN = '5ef2838c-ae5b-47d9-ac05-72dc5362b6a9'
OWNER = 'd46cee52-3d3b-45ae-9bc3-c95931a82339'
EMAIL = 'android-qa-5ef2838c-a@example.invalid'
CLASS = 'cc.savely.android.data.RealAuthQualificationTest'
INPUT = 'auth-qualification-input.json'
RESULT = 'auth-qualification-result.json'


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # Both modules guard their side-effectful main.
    return module


def canonical(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def credential_input(value, run):
    require(canonical(run), 'RUN_INVALID')
    require(value.get('version') == 1 and value.get('project') == PROJECT and
            value.get('runId') == PROVISIONING_RUN, 'VAULT_BINDING_INVALID')
    matches = [a for a in value.get('accounts', []) if a.get('id') == OWNER and a.get('email') == EMAIL]
    require(len(matches) == 1 and isinstance(matches[0].get('password'), str) and
            12 <= len(matches[0]['password']) <= 256, 'EXACT_QA_CREDENTIAL_MISSING')
    # Only this password is transferred; B/Admin and privileged credentials remain on the host.
    return {'run_id': run, 'user_id': OWNER, 'email': EMAIL, 'project': PROJECT,
            'provisioning_run': PROVISIONING_RUN, 'server_preflight_empty': True,
            'password': matches[0]['password']}


def owned_result(value, run):
    return value.get('run_id') == run and value.get('user_id') == OWNER and value.get('email') == EMAIL and \
        value.get('project') == PROJECT and value.get('provisioning_run') == PROVISIONING_RUN


def result_passed(value, run):
    flags = ('password_login', 'totp_verified', 'others_confirmed', 'secondary_revoked',
             'current_refreshed', 'factor_removed', 'logout_primary', 'cleanup_local', 'cleanup_secondary')
    return owned_result(value, run) and value.get('stage') == 'passed' and \
        value.get('cleanup_pending') is False and all(value.get(flag) is True for flag in flags) and \
        canonical(value.get('primary_session_id', '')) and canonical(value.get('secondary_session_id', '')) and \
        value['primary_session_id'] != value['secondary_session_id'] and canonical(value.get('factor_id', ''))


def server_preflight(token):
    # Fixed read-only SQL. No password, token, email content or session rows returned.
    sql = f"""select (select count(*) from auth.sessions where user_id='{OWNER}'::uuid
        and (not_after is null or not_after > now())) as session_count,
        (select count(*) from auth.mfa_factors where user_id='{OWNER}'::uuid) as factor_count,
        (select count(*) from auth.users where id='{OWNER}'::uuid and email='{EMAIL}'
        and email_confirmed_at is not null and raw_user_meta_data->>'savely_android_qa_run'='{PROVISIONING_RUN}') as identity_count,
        (select value from public.app_config where key='registrations_enabled') as registrations_enabled"""
    request = Request(f'https://api.supabase.com/v1/projects/{PROJECT}/database/query',
                      data=json.dumps({'query': sql, 'read_only': True}).encode(),
                      headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method='POST')
    try:
        class RefuseRedirect(HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        with build_opener(RefuseRedirect()).open(request, timeout=25) as response:
            raw = response.read(8193)
        require(len(raw) <= 8192, 'PREFLIGHT_RESPONSE_TOO_LARGE')
        rows = json.loads(raw)
        require(isinstance(rows, list) and len(rows) == 1, 'PREFLIGHT_RESPONSE_INVALID')
        row = rows[0]
        require(row.get('session_count') == 0 and row.get('factor_count') == 0 and
                row.get('identity_count') == 1 and row.get('registrations_enabled') == 'false', 'QA_NOT_EMPTY_OR_REGISTRATIONS_OPEN')
    except RuntimeError:
        raise
    except Exception:
        raise RuntimeError('READ_ONLY_SERVER_PREFLIGHT_FAILED') from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--device', choices=('emulator-5558', 'emulator-5556'), default='emulator-5558')
    parser.add_argument('--avd', choices=('SavelyAndroid12', 'SavelyTest'), default='SavelyAndroid12')
    parser.add_argument('--run-id', required=True)
    args = parser.parse_args()
    require(args.execute and canonical(args.run_id), 'EXPLICIT_EXECUTION_AND_RUN_REQUIRED')
    require((args.device, args.avd) in {('emulator-5558', 'SavelyAndroid12'), ('emulator-5556', 'SavelyTest')}, 'DEVICE_PAIR_INVALID')
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk')))
    adb = sdk / 'platform-tools/adb.exe'
    destination = ROOT / 'artifacts' / ('auth-qualification-' + args.run_id + '.json')
    require(not destination.exists(), 'HOST_RESULT_PRESENT')

    def command(arguments, data=None):
        try:
            process = subprocess.run([str(adb), '-s', args.device, *arguments], input=data,
                                     capture_output=True, timeout=180)
            require(process.returncode == 0, 'DEVICE_COMMAND_FAILED_OUTPUT_WITHHELD')
            return process.stdout
        except RuntimeError:
            raise
        except Exception:
            raise RuntimeError('DEVICE_COMMAND_FAILED_OUTPUT_WITHHELD') from None

    name = command(['emu', 'avd', 'name']).decode().splitlines()[0].strip()
    require(name == args.avd, 'AVD_NAME_MISMATCH')
    # Native preflight checks debug/project and refuses preexisting ciphertext without loading it.
    preflight = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                         CLASS + '#preflightEmptyLocalStateWithoutCredentials', '-e', 'realAuthQualification', 'true',
                         '-e', 'authQualificationRunId', args.run_id,
                         'cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner'])
    require(b'OK (1 test)' in preflight, 'NATIVE_PREFLIGHT_FAILED_OUTPUT_WITHHELD')
    auth_config = load_module(ROOT / 'tools/native_auth_config.py', 'auth_qualification_management')
    server_preflight(auth_config.management_token())
    credentials = load_module(ROOT / 'tools/qa_credentials.py', 'auth_qualification_dpapi')
    payload = credential_input(credentials.load_credentials(), args.run_id)
    transferred = False
    attempted = False
    outcome = None
    try:
        # noclobber preserves any unexpected concurrent input. Neither argv nor host file contains secrets.
        transferred = True
        command(['shell', 'run-as', 'cc.savely.android', 'sh', '-c',
                 "'set -C; mkdir -p files; cat > files/auth-qualification-input.json'"], json.dumps(payload).encode())
        payload = None
        attempted = True
        output = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#passwordTotpAndOtherSessions',
                          '-e', 'realAuthQualification', 'true', '-e', 'authQualificationRunId', args.run_id,
                          'cc.savely.android.test/androidx.test.runner.AndroidJUnitRunner'])
        raw = command(['shell', 'run-as', 'cc.savely.android', 'cat', 'files/' + RESULT])
        require(len(raw) <= 16384, 'RESULT_TOO_LARGE')
        outcome = json.loads(raw)
        require(owned_result(outcome, args.run_id), 'RESULT_OWNER_MISMATCH')
        with destination.open('x', encoding='utf-8') as file:
            json.dump(outcome, file, indent=2)
            file.write('\n')
        require(b'OK (1 test)' in output and result_passed(outcome, args.run_id), 'QUALIFICATION_FAILED_INSPECT_SANITIZED_RESULT')
        print(json.dumps({'stage': 'passed', 'run_id': args.run_id, 'cleanup_pending': False,
                          'result': str(destination), 'scope': outcome['scope']}))
    finally:
        payload = None
        # Do not repeat remote logout/factor deletion, or clear any session from the host.
        # Interrupted instrumentation leaves the bound native checkpoint for explicit review.
        if transferred:
            try:
                probe = command(['shell', 'run-as', 'cc.savely.android', 'sh', '-c',
                                 "'if test -f files/auth-qualification-input.json; then cat files/auth-qualification-input.json; fi'"])
                if probe:
                    require(len(probe) <= 16384, 'PRIVATE_INPUT_INVALID_PRESERVE')
                    remaining = json.loads(probe)
                    require(remaining.get('run_id') == args.run_id and remaining.get('user_id') == OWNER and
                            remaining.get('email') == EMAIL and remaining.get('project') == PROJECT,
                            'FOREIGN_PRIVATE_INPUT_PRESERVE')
                    command(['shell', 'run-as', 'cc.savely.android', 'rm', 'files/' + INPUT])
                    remaining = None
                probe = None
            except Exception:
                print(json.dumps({'run_id': args.run_id, 'cleanup_pending': True,
                                  'reason': 'PRIVATE_INPUT_CLEANUP_UNCONFIRMED'}), file=sys.stderr)
        if attempted and outcome is None:
            print(json.dumps({'run_id': args.run_id, 'cleanup_pending': True,
                              'reason': 'NATIVE_RESULT_NOT_CONFIRMED_NO_AUTOMATIC_REPLAY'}), file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        safe = str(error) if isinstance(error, RuntimeError) else 'AUTH_QUALIFICATION_FAILED_DETAILS_WITHHELD'
        print(safe, file=sys.stderr)
        sys.exit(1)
