"""Exact REST predicates for rows adopted by the V03 read-only ledger; no I/O here."""
from decimal import Decimal
from urllib.parse import urlencode

from couple_invitation_evidence import A, canonical, instant, need
from finance_form_evidence import FIELDS, decimal


def normalize(table, row):
    need(table in FIELDS and isinstance(row, dict) and set(row) == FIELDS[table], "CLEANUP_ROW_FIELDS_INVALID")
    value = dict(row)
    for field in ("amount", "exchange_rate_eur"):
        if field in value:
            value[field] = format(decimal(str(value[field])).normalize(), "f")
    for field in ("created_at", "updated_at", "deleted_at"):
        if field in value and value[field] is not None:
            value[field] = instant(value[field]).isoformat()
    return value


def exact_path(table, row, run, baseline_ids, adopted_ids):
    canonical(run)
    value = normalize(table, row)
    identifier = canonical(value["id"])
    need(value["user_id"] == A and identifier in adopted_ids and identifier not in baseline_ids,
         "CLEANUP_NOT_OWNED")
    marker = "android-finance-form-" + run
    if table == "categories":
        need(value["name"] in (marker + "-expense", marker + "-expense-renamed", marker + "-income"), "CLEANUP_MARKER_INVALID")
    elif table == "transactions":
        need(value["description"] in (marker + "-expense", marker + "-income"), "CLEANUP_MARKER_INVALID")
    else:
        need(value["category_id"] in adopted_ids and value["category_id"] not in baseline_ids, "CLEANUP_CATEGORY_NOT_OWNED")
    filters = {"select": ",".join(sorted(FIELDS[table]))}
    for field, item in sorted(value.items()):
        if item is None:
            filters[field] = "is.null"
        elif isinstance(item, (int, Decimal)) or field in ("amount", "exchange_rate_eur"):
            filters[field] = "eq." + str(item)
        else:
            need(isinstance(item, str) and not any(char in item for char in ('\x00', '\r', '\n')), "CLEANUP_FILTER_INVALID")
            # These are standalone filters; quoting a UUID here makes PostgREST reject its cast.
            filters[field] = "eq." + item
    return "/rest/v1/" + table + "?" + urlencode(filters)
