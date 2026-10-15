"""V06 fixed QA observations. This module performs no login or backend mutation."""
import copy
import datetime as dt
import json
from decimal import Decimal, ROUND_HALF_UP

import recurring_form_evidence as recurring
import finance_form_evidence as finance
from couple_invitation_evidence import A, QA, QA_SQL, canonical, instant, need

TABLES = ("portfolio_assets", "asset_price_history", "manual_price_updates", "price_update_logs", "net_worth_snapshots")
SELECTS = {
    "portfolio_assets": "id,user_id,name,symbol,type,quantity::text as quantity,purchase_price::text as purchase_price,current_price::text as current_price,currency,purchase_date,notes,created_at,updated_at,sold_at,sold_price::text as sold_price",
    "asset_price_history": "id,asset_id,user_id,price::text as price,recorded_at,created_at",
    "manual_price_updates": "id,user_id,updated_at,assets_updated,assets_checked,created_at",
    "price_update_logs": "id,user_id,updated_at,assets_updated,assets_checked,errors",
    "net_worth_snapshots": "id,user_id,date,net_worth::text as net_worth,components::text as components,created_at",
}
FIELDS = {table: {column.split(" as ")[-1] for column in columns.split(",")} for table, columns in SELECTS.items()}
ROWS_SQL = ",".join(f"'{table}',(select coalesce(jsonb_agg(to_jsonb(t) order by id),'[]'::jsonb) from "
    f"(select {SELECTS[table]} from public.{table} where user_id in ({QA_SQL}) order by id limit 101) t)" for table in TABLES)
COUNTS_SQL = ",".join(f"'{table}',(select count(*) from public.{table} where user_id in ({QA_SQL}))" for table in TABLES)
SNAPSHOT_SQL = f"with base as ({recurring.SNAPSHOT_SQL}) select jsonb_build_object('finance',snapshot," \
    f"'rows',jsonb_build_object({ROWS_SQL}),'counts',jsonb_build_object({COUNTS_SQL})) as snapshot from base"
READS = {"create-cancel", "edit-cancel", "single-close-cancel", "group-close-cancel", "delete-cancel", "closed-read"}
CREATES = {"create-single": "single", "create-first": "first", "create-second": "second"}
DELETES = {"delete-single": "single", "delete-first": "first", "delete-second": "second"}


def validate(value):
    need(isinstance(value, dict) and set(value) == {"finance", "rows", "counts"}, "PORTFOLIO_SNAPSHOT_INVALID")
    recurring.validate_snapshot(value["finance"])
    need(isinstance(value["rows"], dict) and isinstance(value["counts"], dict) and
         set(value["rows"]) == set(TABLES) == set(value["counts"]), "PORTFOLIO_TABLES_INVALID")
    now = instant(value["finance"]["now"])
    for table in TABLES:
        rows, count = value["rows"][table], value["counts"][table]
        need(isinstance(rows, list) and type(count) is int and 0 <= count <= 100 and len(rows) == count,
             "PORTFOLIO_ROWS_TRUNCATED")
        ids = set()
        for row in rows:
            need(isinstance(row, dict) and set(row) == FIELDS[table], "PORTFOLIO_FIELDS_INVALID")
            identifier = canonical(row["id"])
            need(identifier not in ids and row["user_id"] in QA, "PORTFOLIO_OWNER_INVALID")
            ids.add(identifier)
            for key in ("created_at", "updated_at"):
                if key in row:
                    need(instant(row[key]) <= now, "PORTFOLIO_TIMESTAMP_INVALID")
            if "created_at" in row and "updated_at" in row:
                need(instant(row["created_at"]) <= instant(row["updated_at"]), "PORTFOLIO_TIMESTAMP_INVALID")
            for key in ("quantity", "purchase_price", "current_price", "sold_price", "price", "net_worth"):
                if key in row and row[key] is not None:
                    finance.decimal(row[key])
            for key in ("purchase_date", "sold_at", "recorded_at", "date"):
                if key in row and row[key] is not None:
                    need(dt.date.fromisoformat(row[key]).isoformat() == row[key], "PORTFOLIO_DATE_INVALID")
            for key in ("assets_checked", "assets_updated"):
                if key in row:
                    need(type(row[key]) is int and row[key] >= 0, "PORTFOLIO_COUNT_INVALID")
            if table == "asset_price_history":
                canonical(row["asset_id"])
            if table == "price_update_logs":
                need(isinstance(row["errors"], list), "PORTFOLIO_ERRORS_INVALID")
    return copy.deepcopy(value)


def snapshot(execute):
    result = execute(SNAPSHOT_SQL, read_only=True)
    need(isinstance(result, list) and len(result) == 1 and set(result[0]) == {"snapshot"}, "PORTFOLIO_RESPONSE_INVALID")
    return validate(result[0]["snapshot"])


def baseline(value):
    validate(value)
    finance._session(value["finance"], absent=True)
    # Daily snapshots are existing QA data. Preserve them; never clear them for this fixture.
    need(all(value["counts"][table] == 0 for table in TABLES if table != "net_worth_snapshots"), "PORTFOLIO_QA_NOT_EMPTY")


def same_environment(base, value, binding=None, *, absent=False):
    validate(value)
    finance._same_accounts(base["finance"], value["finance"])
    finance._unchanged_records(base["finance"], value["finance"], {})
    return finance._session(value["finance"], binding, absent=absent)


def _payload(row, expected):
    for key, wanted in expected.items():
        if key in ("quantity", "purchase_price", "current_price", "sold_price") and wanted is not None:
            need(finance.decimal(row[key]) == Decimal(wanted), "PORTFOLIO_PAYLOAD_CHANGED")
        else:
            need(row[key] == wanted, "PORTFOLIO_PAYLOAD_CHANGED")


def transition(op, before, after, registry, run):
    """Adopt only the expected mutation, preserving every other QA row byte for byte."""
    canonical(run)
    validate(before)
    validate(after)
    start, end = instant(before["finance"]["now"]), instant(after["finance"]["now"])
    need(start <= end and before["finance"]["day"] == after["finance"]["day"], "PORTFOLIO_CLOCK_CHANGED")
    result, expected = copy.deepcopy(registry), copy.deepcopy(before["rows"])
    marker, day = f"android-portfolio-form-{run}", before["finance"]["day"]

    def find(key):
        need(key in result and not result[key]["removed"], "PORTFOLIO_ADOPTION_MISSING")
        row = result[key]["row"]
        need(row["user_id"] == A and row["name"] == marker + "-" + key and
             row in before["rows"]["portfolio_assets"], "PORTFOLIO_PREIMAGE_CHANGED")
        return row

    def replace(key, changes):
        old = find(key)
        rows = [row for row in after["rows"]["portfolio_assets"] if row["id"] == old["id"]]
        need(len(rows) == 1, "PORTFOLIO_ROW_MISSING")
        row = rows[0]
        need(start <= instant(row["updated_at"]) <= end, "PORTFOLIO_WRITE_WINDOW_INVALID")
        _payload(row, {**old, **changes, "updated_at": row["updated_at"]})
        expected["portfolio_assets"].remove(old)
        expected["portfolio_assets"].append(row)
        result[key]["row"] = copy.deepcopy(row)

    if op in CREATES:
        key = CREATES[op]
        need(key not in result, "PORTFOLIO_CREATION_REPLAY")
        ids = {row["id"] for row in before["rows"]["portfolio_assets"]}
        added = [row for row in after["rows"]["portfolio_assets"] if row["id"] not in ids]
        need(len(added) == 1, "PORTFOLIO_CREATION_NOT_UNIQUE")
        row = added[0]
        _payload(row, {"user_id": A, "name": marker + "-" + key, "notes": marker,
            "symbol": "AAPL", "type": "stock", "currency": "EUR", "purchase_date": day,
            "quantity": "3" if key == "second" else "2", "purchase_price": "0.01", "current_price": "0.02",
            "sold_at": None, "sold_price": None})
        need(start <= instant(row["created_at"]) <= instant(row["updated_at"]) <= end,
             "PORTFOLIO_WRITE_WINDOW_INVALID")
        result[key] = {"row": copy.deepcopy(row), "removed": False}
        expected["portfolio_assets"].append(row)
    elif op == "edit-single":
        replace("single", {"current_price": "0.03"})
    elif op in ("close-single", "close-group"):
        for key in (("single",) if op == "close-single" else ("first", "second")):
            need(find(key)["sold_at"] is None, "PORTFOLIO_ALREADY_CLOSED")
            replace(key, {"sold_at": day, "sold_price": "0.03"})
    elif op in DELETES:
        key = DELETES[op]
        expected["portfolio_assets"].remove(find(key))
        result[key]["removed"] = True
        expected["asset_price_history"] = [row for row in expected["asset_price_history"] if row["asset_id"] != result[key]["row"]["id"]]
    elif op == "prices":
        need(set(result) == {"single", "first", "second"} and all(not item["removed"] for item in result.values()),
             "PORTFOLIO_PRICE_SCOPE_INVALID")
        owned = {entry["row"]["id"] for entry in result.values()}
        need({row["id"] for row in before["rows"]["portfolio_assets"]} == owned, "PORTFOLIO_PRICE_SCOPE_INVALID")
        for key in result:
            new = next((row for row in after["rows"]["portfolio_assets"] if row["id"] == find(key)["id"]), None)
            need(new is not None, "PORTFOLIO_ROW_MISSING")
            if new != find(key):
                replace(key, {"current_price": new["current_price"]})
        for table in ("manual_price_updates", "price_update_logs", "asset_price_history"):
            prior = before["rows"][table]
            added = [row for row in after["rows"][table] if row["id"] not in {old["id"] for old in prior}]
            need(all(row["user_id"] == A for row in added), "PORTFOLIO_PRICE_OWNER_CHANGED")
            if table == "asset_price_history":
                need(len(added) <= 3 and len({row["asset_id"] for row in added}) == len(added) and
                     all(row["asset_id"] in owned and row["recorded_at"] == day and start <= instant(row["created_at"]) <= end for row in added),
                     "PORTFOLIO_HISTORY_INVALID")
                for history in added:
                    asset = next(row for row in after["rows"]["portfolio_assets"] if row["id"] == history["asset_id"])
                    raw = finance.decimal(history["price"])
                    need(raw > 0 and raw.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP) == finance.decimal(asset["current_price"]),
                         "PORTFOLIO_HISTORY_QUOTE_MISMATCH")
            else:
                need(len(added) == 1 and added[0]["assets_checked"] == 3 and added[0]["assets_updated"] <= 3 and
                     start <= instant(added[0]["updated_at"]) <= end, "PORTFOLIO_PRICE_LOG_INVALID")
            expected[table].extend(added)
        manual = next(row for row in after["rows"]["manual_price_updates"] if row not in before["rows"]["manual_price_updates"])
        log = next(row for row in after["rows"]["price_update_logs"] if row not in before["rows"]["price_update_logs"])
        need(manual["assets_updated"] == log["assets_updated"] and
             len(after["rows"]["asset_price_history"]) == log["assets_updated"], "PORTFOLIO_PRICE_COUNTS_CHANGED")
    elif op not in READS:
        need(False, "PORTFOLIO_OPERATION_INVALID")
    need(all(sorted(after["rows"][table], key=lambda row: row["id"]) == sorted(expected[table], key=lambda row: row["id"])
             for table in TABLES), "PORTFOLIO_UNRESERVED_CHANGE")
    return result


def cleanup_sql(table, row, base, price_observation):
    """Return a CAS for one adopted log only; the caller journals and verifies its outcome."""
    need(table in ("manual_price_updates", "price_update_logs"), "PORTFOLIO_CLEANUP_TABLE_INVALID")
    validate(base)
    validate(price_observation)
    need(isinstance(row, dict) and set(row) == FIELDS[table] and row.get("user_id") == A,
         "PORTFOLIO_CLEANUP_OWNER_INVALID")
    identifier = canonical(row["id"])
    need(all(old["id"] != identifier for old in base["rows"][table]) and
         row in price_observation["rows"][table] and
         len([item for item in price_observation["rows"][table] if item not in base["rows"][table]]) == 1,
         "PORTFOLIO_CLEANUP_NOT_ADOPTED")
    need(instant(base["finance"]["now"]) <= instant(row["updated_at"]) <= instant(price_observation["finance"]["now"]),
         "PORTFOLIO_CLEANUP_WINDOW_INVALID")
    def quote(value):
        return "'" + value.replace("'", "''") + "'"
    payload = {key: value for key, value in row.items() if key not in ("created_at", "updated_at")}
    timestamps = "".join(f" and t.{key}={quote(row[key])}::timestamptz" for key in ("created_at", "updated_at") if key in row)
    return f"delete from public.{table} t where t.id='{identifier}'::uuid and t.user_id='{A}'::uuid" \
        f" and (to_jsonb(t)-'created_at'-'updated_at')={quote(json.dumps(payload, separators=(',', ':')))}::jsonb" \
        f"{timestamps} returning id,user_id"
