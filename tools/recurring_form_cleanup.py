"""Exact V04 REST cleanup predicates; this module performs no I/O or requests."""
import datetime as dt
from decimal import Decimal
import re
from urllib.parse import urlencode

import finance_form_evidence as finance
from recurring_form_evidence import RECURRING_FIELDS
from couple_invitation_evidence import A, EvidenceError, canonical, instant, need

FIELDS = {"transactions": finance.FIELDS["transactions"], "recurring_expenses": RECURRING_FIELDS}
_TIMESTAMP = re.compile(r"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2})")


def _text(value):
    need(isinstance(value, str) and len(value) <= 256 and
         not any(ord(char) < 32 or 127 <= ord(char) <= 159 or char in "\u2028\u2029" for char in value), "CLEANUP_FILTER_INVALID")
    return value


def _decimal(value):
    need(type(value) in (str, int, Decimal), "CLEANUP_DECIMAL_INVALID")
    raw = _text(str(value))
    number = finance.decimal(raw)
    need(-128 <= number.as_tuple().exponent <= 128 and
         (number == 0 or -128 <= number.adjusted() <= 128), "CLEANUP_DECIMAL_INVALID")
    # Decimal.normalize() uses the active precision context and can round a CAS preimage.
    result = format(number, "f")
    if "." in result:
        result = result.rstrip("0").rstrip(".")
    return "0" if number == 0 else result


def _instant(value):
    match = _TIMESTAMP.fullmatch(_text(value))
    need(match is not None, "CLEANUP_TIMESTAMP_INVALID")
    # Convert only whole seconds through datetime: keep every supplied fractional digit intact.
    try:
        whole = instant(match[1] + match[3]).astimezone(dt.timezone.utc)
    except (OverflowError, ValueError):
        raise EvidenceError("CLEANUP_TIMESTAMP_INVALID") from None
    fraction = (match[2] or "").rstrip("0")
    return whole.date().isoformat() + whole.strftime("T%H:%M:%S") + ("." + fraction if fraction else "") + "+00:00"


def _date(value):
    try:
        need(re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", _text(value)) is not None, "CLEANUP_DATE_INVALID")
        result = dt.date.fromisoformat(value)
        need(result.isoformat() == value, "CLEANUP_DATE_INVALID")
        return result
    except (ValueError, TypeError, OverflowError):
        raise EvidenceError("CLEANUP_DATE_INVALID") from None


def normalize(table, row):
    need(isinstance(table, str) and table in FIELDS and isinstance(row, dict) and set(row) == FIELDS[table], "CLEANUP_ROW_FIELDS_INVALID")
    value = dict(row)
    for field, item in value.items():
        if isinstance(item, str):
            _text(item)
    canonical(value["id"])
    canonical(value["user_id"])
    if value["category_id"] is not None:
        canonical(value["category_id"])
    value["amount"] = _decimal(value["amount"])
    if table == "transactions":
        value["exchange_rate_eur"] = _decimal(value["exchange_rate_eur"])
        _date(value["date"])
    else:
        need(type(value["is_active"]) is bool and type(value["week_interval"]) is int, "CLEANUP_FIELDS_INVALID")
        _date(value["next_due_date"])
    for field in ("created_at", "updated_at", "deleted_at"):
        if value[field] is not None:
            value[field] = _instant(value[field])
    need(value["created_at"] is not None and value["updated_at"] is not None and
         value["created_at"] <= value["updated_at"], "CLEANUP_TIMESTAMP_INVALID")
    return value


def _ids(values):
    need(isinstance(values, (set, frozenset, list, tuple)) and len(values) <= 10_000, "CLEANUP_IDS_INVALID")
    return {canonical(value) for value in values}


def exact_path(table, row, run, baseline_ids, adopted_ids, expected_day):
    run = canonical(run)
    day = _date(expected_day)
    value = normalize(table, row)
    baseline, adopted = _ids(baseline_ids), _ids(adopted_ids)
    need(value["user_id"] == A and value["id"] in adopted and value["id"] not in baseline, "CLEANUP_NOT_OWNED")
    marker = "android-recurring-form-" + run
    need(value["amount"] == "0.02" and value["currency"] == "EUR" and value["category_id"] is None and
         value["deleted_at"] is None, "CLEANUP_PAYLOAD_INVALID")
    if table == "recurring_expenses":
        try:
            next_due = (day + dt.timedelta(days=7)).isoformat()
        except OverflowError:
            raise EvidenceError("CLEANUP_DATE_INVALID") from None
        need(value["name"] == marker and value["frequency"] == "weekly" and value["week_interval"] == 1 and
             value["is_active"] is False and value["next_due_date"] == next_due, "CLEANUP_RECURRING_INVALID")
    else:
        need(value["description"] == marker and value["type"] == "expense" and value["date"] == expected_day and
             finance.decimal(value["exchange_rate_eur"]) > 0, "CLEANUP_TRANSACTION_INVALID")
    query = {"select": ",".join(sorted(FIELDS[table]))}
    for field, item in sorted(value.items()):
        if item is None:
            query[field] = "is.null"
        elif type(item) is bool:
            query[field] = "eq." + str(item).lower()
        elif type(item) is int:
            query[field] = "eq." + str(item)
        else:
            query[field] = "eq." + _text(item)
    return "/rest/v1/" + table + "?" + urlencode(query)
