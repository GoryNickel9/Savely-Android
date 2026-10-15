"""Read the three explicitly provisioned QA credentials using Windows CurrentUser DPAPI.

The returned secrets are for opt-in host runners only. Never print, persist plaintext,
embed in an APK or pass as command-line arguments. No network or backend mutations.
"""
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path

PROJECT = "crqnfbahytzenisospcx"
QA_RUN = "5ef2838c-ae5b-47d9-ac05-72dc5362b6a9"
QA_USERS = {
    "android-qa-5ef2838c-a@example.invalid": "d46cee52-3d3b-45ae-9bc3-c95931a82339",
    "android-qa-5ef2838c-b@example.invalid": "ed79c6ec-5265-4b8b-9f50-4fc1f296bc5b",
    "android-qa-5ef2838c-admin@example.invalid": "3afa4bc1-0910-4f18-81b3-9e9f69c65a6e",
}


class _Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def _decrypt(raw):
    if os.name != "nt":
        raise RuntimeError("QA vault requires the provisioning Windows user")
    buffer = (ctypes.c_ubyte * len(raw)).from_buffer_copy(raw)
    source, target = _Blob(len(raw), buffer), _Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    crypt.CryptUnprotectData.argtypes = [ctypes.POINTER(_Blob), ctypes.c_void_p,
                                        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                                        wintypes.DWORD, ctypes.POINTER(_Blob)]
    crypt.CryptUnprotectData.restype = wintypes.BOOL
    if not crypt.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise RuntimeError("QA vault decryption failed; secrets withheld")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree.restype = ctypes.c_void_p
        kernel.LocalFree(target.data)


def _validate(value):
    try:
        if value["version"] != 1 or value["project"] != PROJECT or value["runId"] != QA_RUN:
            raise ValueError()
        accounts = value["accounts"]
        if len(accounts) != 3 or {a["email"]: a["id"] for a in accounts} != QA_USERS:
            raise ValueError()
        passwords = [a["password"] for a in accounts]
        if any(not isinstance(p, str) or len(p) < 40 for p in passwords) or len(set(passwords)) != 3:
            raise ValueError()
        for account in accounts:
            expected_role = "admin-used-only-against-these-QA-accounts" if "-admin@" in account["email"] else "standard"
            if account["role"] != expected_role:
                raise ValueError()
        return value
    except (KeyError, TypeError, ValueError):
        raise RuntimeError("QA vault identity/schema mismatch; secrets withheld") from None


def load_credentials():
    path = Path.home() / "Documents/Savely-QA-Accounts" / QA_RUN / "credentials.dpapi"
    try:
        with path.open("rb") as source:
            raw = source.read(65_537)
        if not raw or len(raw) > 65_536:
            raise ValueError()
        return _validate(json.loads(_decrypt(raw).decode("utf-8")))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError):
        raise RuntimeError("QA vault unavailable or invalid; secrets withheld") from None
