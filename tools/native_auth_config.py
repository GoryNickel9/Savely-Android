"""Preserve web Auth settings while adding the Android PKCE callback; secrets stay in RAM."""
import argparse
import base64
import ctypes
import json
import os
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

PROJECT = "crqnfbahytzenisospcx"
CALLBACK = "savely://auth/callback?state=*"


def management_token():
    token = os.environ.get("SUPABASE_ACCESS_TOKEN")
    if token:
        return token
    if os.name != "nt":
        raise RuntimeError("Supabase CLI management credential unavailable")
    from ctypes import wintypes

    class Credential(ctypes.Structure):
        _fields_ = [("Flags", wintypes.DWORD), ("Type", wintypes.DWORD),
                    ("TargetName", wintypes.LPWSTR), ("Comment", wintypes.LPWSTR),
                    ("LastWritten", wintypes.FILETIME), ("CredentialBlobSize", wintypes.DWORD),
                    ("CredentialBlob", ctypes.POINTER(ctypes.c_ubyte)),
                    ("Persist", wintypes.DWORD), ("AttributeCount", wintypes.DWORD),
                    ("Attributes", ctypes.c_void_p), ("TargetAlias", wintypes.LPWSTR),
                    ("UserName", wintypes.LPWSTR)]

    api = ctypes.WinDLL("advapi32", use_last_error=True)
    api.CredReadW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                             ctypes.POINTER(ctypes.POINTER(Credential))]
    api.CredReadW.restype = wintypes.BOOL
    api.CredFree.argtypes = [ctypes.c_void_p]
    # Read only the two documented Supabase CLI keys, never enumerate other credentials.
    for target in ("Supabase CLI:supabase", "Supabase CLI:access-token"):
        ptr = ctypes.POINTER(Credential)()
        if not api.CredReadW(target, 1, 0, ctypes.byref(ptr)):
            continue
        try:
            raw = ctypes.string_at(ptr.contents.CredentialBlob, ptr.contents.CredentialBlobSize)
            for encoding in ("utf-16-le", "utf-8"):
                try:
                    value = raw.decode(encoding).rstrip("\0")
                    if value.startswith("go-keyring-base64:"):
                        value = base64.b64decode(value.split(":", 1)[1], validate=True).decode()
                    if value.startswith("sbp_") and len(value) < 128:
                        return value
                except (UnicodeError, ValueError):
                    continue
        finally:
            api.CredFree(ptr)
    raise RuntimeError("Supabase CLI management credential unavailable")


def request_config(token, patch=None):
    request = Request(f"https://api.supabase.com/v1/projects/{PROJECT}/config/auth",
                      data=None if patch is None else json.dumps(patch).encode(),
                      headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                      method="GET" if patch is None else "PATCH")
    try:
        with urlopen(request, timeout=30) as response:
            raw = response.read(1_048_577)
        if len(raw) > 1_048_576:
            raise RuntimeError("Management response too large")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise RuntimeError("Invalid management response")
        return value
    except HTTPError as error:
        raise RuntimeError(f"Supabase Auth management HTTP {error.code}; response redacted") from None


def merged_redirects(current):
    if not isinstance(current, str):
        raise RuntimeError("Unexpected Auth allow-list format")
    redirects = [value.strip() for value in current.split(",") if value.strip()]
    if CALLBACK not in redirects:
        redirects.append(CALLBACK)
    return ",".join(redirects)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--add-callback", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    token = management_token()
    before = request_config(token)
    if "uri_allow_list" not in before:
        raise RuntimeError("Auth allow-list unavailable; nothing changed")
    after = before
    changed = False
    if args.add_callback:
        proposed = merged_redirects(before["uri_allow_list"])
        if proposed != before["uri_allow_list"]:
            # Only this additive field is sent. Never change site URL, SMTP, signup or hooks.
            request_config(token, {"uri_allow_list": proposed})
            changed = True
        after = request_config(token)
        if CALLBACK not in after["uri_allow_list"].split(","):
            raise RuntimeError("Native callback not present after update")
        if set(before["uri_allow_list"].split(",")) - set(after["uri_allow_list"].split(",")):
            raise RuntimeError("Previous redirect missing; investigate before release")
        protected = ("site_url", "disable_signup", "smtp_host", "smtp_port", "smtp_user", "smtp_pass",
                     "smtp_admin_email", "smtp_sender_name", "mailer_autoconfirm")
        if any(before.get(key) != after.get(key) for key in protected):
            raise RuntimeError("Other Auth settings changed concurrently; release verification blocked")
    report = {"project": PROJECT, "callback": CALLBACK, "callbackConfigured": CALLBACK in
              after["uri_allow_list"].split(","), "changed": changed,
              "previousRedirectsPreserved": True,
              "siteUrl": after.get("site_url"), "smtpConfigured": bool(after.get("smtp_host")),
              "signupDisabledAuthSetting": after.get("disable_signup"),
              "emailAutoconfirm": after.get("mailer_autoconfirm"),
              "scope": "auth-config-only-no-email-no-user-data-no-web-source-changes"}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Credential and HTTP payloads must never enter logs or tracebacks.
        print(f"Auth configuration blocked ({type(error).__name__}). No credentials printed.")
        raise SystemExit(1)
