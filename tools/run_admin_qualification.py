"""Opt-in native Admin qualification after Auth/Couple, with QA B permission restoration.

Preparation is source-only. This runner never builds/installs APKs or uses a service-role key.
Passwords enter the app over adb stdin, never a command argument, report, or host plaintext file.
Run failures preserve the private permission ledger; recovery is a separate explicit invocation.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

from qa_credentials import load_credentials, PROJECT, QA_RUN, QA_USERS

ROOT = Path(__file__).resolve().parent.parent
APP = "cc.savely.android"
TEST = "cc.savely.android.data.RealAdminQualificationTest"
INPUT = "admin-qualification-input.json"
LEDGER = "admin-qualification-ledger.json"
RESULT = "admin-qualification-result.json"


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


def remote_quote(command):
    # adb serializes shell arguments into a remote POSIX command; keep sh -c's body intact.
    return "'" + command.replace("'", "'\"'\"'") + "'"


def adb(binary, serial, *args, input_bytes=None, timeout=30):
    process = subprocess.run([str(binary), "-s", serial, *args], input=input_bytes,
                             capture_output=True, timeout=timeout)
    require(process.returncode == 0, "ADB_OPERATION_FAILED_OUTPUT_WITHHELD")
    return process.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--approved-admin-qualification", action="store_true", required=True)
    parser.add_argument("--after-auth-couple", action="store_true", required=True,
                        help="Operator confirms Auth and Couple runs/cleanup have finished.")
    parser.add_argument("--serial", choices=["emulator-5558"], default="emulator-5558")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--recover", action="store_true")
    args = parser.parse_args()
    require(str(uuid.UUID(args.run_id)) == args.run_id, "INVALID_RUN_ID")
    binary = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk"))) / "platform-tools/adb.exe"
    require(binary.is_file(), "ADB_NOT_FOUND")
    # Refuse a different device/image or a pending private input. No general app-data clear.
    require(adb(binary, args.serial, "shell", "getprop", "ro.build.version.sdk").strip() == b"31", "API31_REQUIRED")
    require(adb(binary, args.serial, "emu", "avd", "name").splitlines()[:1] == [b"SavelyAndroid12"], "DEDICATED_AVD_REQUIRED")
    ledger_check = ("( test -e files/" + LEDGER + " || test -e files/" + LEDGER + ".bak )") if args.recover else (
        "test ! -e files/" + LEDGER + " && test ! -e files/" + LEDGER + ".bak")
    check = (
        "test ! -e files/" + INPUT + " && test ! -e files/" + RESULT +
        " && " + ledger_check
    )
    adb(binary, args.serial, "shell", "run-as", APP, "sh", "-c", remote_quote(check))
    # Importing this shared helper only decrypts and validates the exact three reserved identities.
    value = load_credentials()
    require(value["project"] == PROJECT and value["runId"] == QA_RUN and
            {row["email"]: row["id"] for row in value["accounts"]} == QA_USERS, "QA_IDENTITY_CHANGED")
    payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
    require(0 < len(payload) <= 65_536, "PRIVATE_INPUT_SIZE")
    # noclobber plus private umask; avoid PowerShell/text encoding and stdin truncation.
    inject = "umask 077; set -C; cat > files/" + INPUT
    adb(binary, args.serial, "shell", "run-as", APP, "sh", "-c", remote_quote(inject), input_bytes=payload)
    payload = value = None
    method = "recoverExactPermissionChange" if args.recover else "approvedAdminReadDenialAndBPermissionsRestoration"
    command = [str(binary), "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
               "-e", "class", TEST + "#" + method,
               "-e", "adminQualification", "approved-after-auth-couple",
               "-e", "adminRunId", args.run_id,
               APP + ".test/androidx.test.runner.AndroidJUnitRunner"]
    process = subprocess.run(command, capture_output=True, timeout=240)
    # Instrumentation output is deliberately not persisted: no accidental API bodies/user listing.
    output = process.stdout.decode("utf-8", errors="replace")
    require(process.returncode == 0 and "OK (1 test)" in output and "INSTRUMENTATION_STATUS_CODE: -2" not in output,
            "NATIVE_QUALIFICATION_FAILED_PRIVATE_LEDGER_PRESERVED")
    raw = adb(binary, args.serial, "shell", "run-as", APP, "cat", "files/" + RESULT)
    require(0 < len(raw) <= 16_384, "RESULT_SIZE_INVALID")
    report = json.loads(raw)
    allowed = {"schemaVersion", "runId", "status", "scope", "recovery", "permissionsRestored", "localLogout",
               "deniedUnexpectedRequests", "permissionPatchRequests", "emailRequests", "deleteRequests",
               "registrationWrites", "otherUserWrites", "fullUserListPersisted", "qaLoginAuditRequests"}
    require(set(report) == allowed and report["runId"] == args.run_id and report["status"] == "passed" and
            report["permissionsRestored"] is True and report["localLogout"] is True and
            report["fullUserListPersisted"] is False and
            all(report[key] == 0 for key in ("deniedUnexpectedRequests", "emailRequests", "deleteRequests", "registrationWrites", "otherUserWrites")),
            "RESULT_NOT_SANITIZED")
    destination = ROOT / "artifacts" / ("admin-qualification-" + args.run_id + ("-recovery" if args.recover else "") + ".json")
    with destination.open("x", encoding="utf-8") as target:
        target.write(json.dumps(report, indent=2) + "\n")
    adb(binary, args.serial, "shell", "run-as", APP, "rm", "files/" + RESULT)
    print(json.dumps(report, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        code = str(error) if isinstance(error, RuntimeError) else "ADMIN_QUALIFICATION_ERROR_DETAILS_WITHHELD"
        print(json.dumps({"status": "blocked-or-pending", "reason": code,
                          "action": "Inspect exact-run private ledger; never repeat normal mode after a reserved write."}))
        sys.exit(1)
