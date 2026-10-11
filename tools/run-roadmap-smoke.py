"""Opt-in Android contracts on the existing QA account. Administrative credentials remain on the host."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import subprocess
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parent.parent
PROJECT = "crqnfbahytzenisospcx"
QA_ID = "9daffd41-39bf-4b1e-be68-abf12549c4a7"
QA_EMAIL = "e2e-savely@example.com"
PACKAGE = "cc.savely.android"
TEST_CLASS = PACKAGE + ".data.RealRoadmapSmokeTest"


def jwt_session_id(token):
    try:
        value = json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "==="))
        session = value["session_id"]
        if str(uuid.UUID(session)) != session:
            raise ValueError()
        return session
    except (ValueError, KeyError, IndexError):
        raise RuntimeError("Invalid QA session identity") from None


def qa_identity(value):
    if value.get("id") != QA_ID or value.get("email") != QA_EMAIL or not value.get("email_confirmed_at"):
        raise RuntimeError("Unexpected QA identity; nothing permitted")
    if value.get("factors"):
        raise RuntimeError("Existing MFA factors must be preserved")


def http(path, public_key, token=None, body=None):
    headers = {"apikey": public_key, "Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request(f"https://{PROJECT}.supabase.co{path}", headers=headers,
                      data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(request, timeout=30) as response:
            raw = response.read(1_048_577)
        if len(raw) > 1_048_576:
            raise RuntimeError("QA response too large")
        return json.loads(raw) if raw else None
    except HTTPError as error:
        raise RuntimeError(f"QA HTTP {error.code}; response body redacted") from None


def sanitized_report(report, run, session):
    if report.get("run_id") != run or report.get("session_id") != session or report.get("user_id") != QA_ID:
        raise RuntimeError("Mismatching private result; preserve it for recovery")
    if report.get("project_host") != f"{PROJECT}.supabase.co":
        raise RuntimeError("Mismatching project")
    def validate(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if any(part in key.lower() for part in ("token", "password", "secret", "payload", "body")):
                    raise RuntimeError("Unexpected sensitive result field; result not copied")
                validate(child)
        elif isinstance(value, list):
            for child in value:
                validate(child)
    validate(report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=("emulator-5556", "emulator-5558"), default="emulator-5558")
    parser.add_argument("--avd", choices=("SavelyTest", "SavelyAndroid12"), default="SavelyAndroid12")
    parser.add_argument("--run-id")
    parser.add_argument("--session-id")
    parser.add_argument("--date")
    parser.add_argument("--recover", action="store_true")
    args = parser.parse_args()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"

    def device(*parts, data=None, timeout=90):
        result = subprocess.run([str(adb), "-s", args.device, *parts], input=data, capture_output=True,
                                text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("ADB step failed; output withheld")
        return result.stdout

    avd = device("emu", "avd", "name").splitlines()[0].strip()
    if avd != args.avd:
        raise RuntimeError("Wrong dedicated AVD; device preserved")
    run = args.run_id or str(uuid.uuid4())
    if str(uuid.UUID(run)) != run:
        raise RuntimeError("Invalid run ID")
    date = args.date or datetime.date.today().isoformat()
    datetime.date.fromisoformat(date)
    out = ROOT / "artifacts/roadmap-smoke" / run
    out.mkdir(parents=True, exist_ok=False if not args.recover else True)
    bootstrap = None
    session = args.session_id
    transferred = False
    completed = False
    public_key = None
    result = None
    try:
        installed = device("shell", "dumpsys", "package", PACKAGE)
        if "versionCode=9" not in installed or "DEBUGGABLE" not in installed:
            raise RuntimeError("Fresh debug code9 installation required; no uninstall performed")
        if not args.recover:
            preflight = device("shell", "am", "instrument", "-w", "-r", "-e", "class",
                               TEST_CLASS + "#preflightEmptyLocalStateWithoutCredentials", "-e", "roadmapPreflight", "true",
                               PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=180)
            (out / "preflight.txt").write_text(preflight, encoding="utf-8")
            if "OK (1 test)" not in preflight:
                raise RuntimeError("Preflight refused; no credential created")
            settings = dict(line.split("=", 1) for line in (ROOT / "savely.properties").read_text().splitlines() if "=" in line)
            if settings.get("SUPABASE_URL", "").rstrip("/") != f"https://{PROJECT}.supabase.co":
                raise RuntimeError("Wrong public backend configuration")
            public_key = settings["SUPABASE_ANON_KEY"]
            keys = json.loads(subprocess.run(["supabase", "projects", "api-keys", "--project-ref", PROJECT,
                                              "--reveal", "--output", "json"], capture_output=True, text=True, check=True).stdout)
            admin = next(key["api_key"] for key in keys if key["name"] == "service_role")
            qa_identity(http("/auth/v1/admin/users/" + QA_ID, admin, admin))
            link = http("/auth/v1/admin/generate_link", admin, admin, {"type": "magiclink", "email": QA_EMAIL})
            link_user = link.get("user", link)
            if link_user.get("id") != QA_ID or link_user.get("email") != QA_EMAIL:
                raise RuntimeError("Wrong link identity")
            hashed = link.get("hashed_token") or link.get("properties", {}).get("hashed_token")
            if not hashed:
                raise RuntimeError("Link token unavailable")
            bootstrap = http("/auth/v1/verify", public_key, body={"type": "magiclink", "token_hash": hashed})
            qa_identity(bootstrap["user"])
            session = jwt_session_id(bootstrap["access_token"])
            admin = keys = link = hashed = None
            transferred = True
            device("shell", "run-as", PACKAGE, "sh", "-c",
                   "'set -C; cat > files/roadmap-smoke-session.json'", data=json.dumps(bootstrap))
        else:
            if not session or str(uuid.UUID(session)) != session or not args.date:
                raise RuntimeError("Recovery requires exact existing run, session and date")
        method = "recoverExactRoadmapRun" if args.recover else "qaNativeContractsAndIsolatedCrud"
        flag = "realRoadmapRecovery" if args.recover else "realRoadmapSmoke"
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS + "#" + method,
                   "-e", flag, "true", "-e", "roadmapRunId", run, "-e", "roadmapSessionId", session,
                   "-e", "roadmapDate", date, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
        instrumentation = device(*command, timeout=900)
        # The class reports constant phases/status only. Never append backend response bodies.
        (out / ("recovery-instrumentation.txt" if args.recover else "instrumentation.txt")).write_text(instrumentation, encoding="utf-8")
        prefix = "roadmap-smoke-recovery-result" if args.recover else "roadmap-smoke-result"
        raw = device("shell", "run-as", PACKAGE, "cat", f"files/{prefix}-{run}.json")
        result = sanitized_report(json.loads(raw), run, session)
        (out / ("recovery-result.json" if args.recover else "result.json")).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        completed = result.get("cleanup_complete") is True and result.get("cleanup_pending") is False
        if "OK (1 test)" not in instrumentation or result.get("stage") != "passed" or not completed:
            raise RuntimeError("Native roadmap smoke incomplete; use only exact-run recovery")
        report_name = "recovery-result.json" if args.recover else "result.json"
        print(f"QA smoke passed; result: {out / report_name}")
    finally:
        if bootstrap and not transferred:
            http("/auth/v1/logout?scope=local", public_key, bootstrap["access_token"], {})
        summary = {"run_id": run, "session_id": session, "date": date, "device": args.device,
                   "avd": args.avd, "credential_transfer_attempted": transferred,
                   "cleanup_verified": completed, "scope": "existing-QA-native-contracts-isolated-UUIDs"}
        host_name = "recovery-host.json" if args.recover else "host.json"
        (out / host_name).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        bootstrap = None


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"QA smoke stopped ({type(error).__name__}); inspect sanitized run evidence. No credentials printed.")
        raise SystemExit(1)
