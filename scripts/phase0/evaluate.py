"""Evaluate sanitized integration evidence without contacting LeetCode."""

import argparse
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Result:
    status: str
    can_advance_checkpoint: bool = False
    accepted_candidates: int = 0
    rejected_submissions: int = 0
    unresolved_records: int = 0
    duplicate_records: int = 0


def identifier(value):
    return isinstance(value, str) and bool(value) and value == value.strip()


def event_time(value):
    if not isinstance(value, str) or "T" not in value:
        return None
    try:
        timestamp = datetime.fromisoformat(value)
        return timestamp.astimezone(timezone.utc) if timestamp.utcoffset() is not None else None
    except (ValueError, OverflowError):
        return None


def submission_signature(record):
    timestamp = event_time(record.get("occurred_at"))
    if not identifier(record.get("problem_id")) or timestamp is None:
        return None
    if record.get("outcome") not in ("accepted", "rejected"):
        return None
    if record.get("difficulty") not in ("easy", "medium", "hard"):
        return None
    return record["problem_id"], timestamp, record["outcome"], record["difficulty"]


def count_records(records):
    signatures_by_id = {}
    unresolved = duplicates = 0
    for record in records:
        if not isinstance(record, dict) or not identifier(record.get("submission_id")):
            unresolved += 1
            continue
        signatures = signatures_by_id.setdefault(record["submission_id"], set())
        signature = submission_signature(record)
        if signature is None:
            unresolved += 1
        elif signature in signatures:
            duplicates += 1
        signatures.add(signature)

    accepted = rejected = 0
    for signatures in signatures_by_id.values():
        concrete = signatures - {None}
        if len(concrete) > 1:
            unresolved += 1
        if len(signatures) != 1 or not concrete:
            continue
        _, _, outcome, _ = next(iter(concrete))
        if outcome == "accepted":
            accepted += 1
        else:
            rejected += 1
    return accepted, rejected, unresolved, duplicates


def evaluate(expected_profile_id, evidence):
    if not identifier(expected_profile_id) or not isinstance(evidence, dict):
        return Result("schema_error")

    transport = evidence.get("transport")
    if transport in ("timeout", "connection_error"):
        return Result(transport)
    if transport != "http":
        return Result("schema_error")

    status = evidence.get("status_code")
    if type(status) is not int or not 100 <= status <= 599:
        return Result("schema_error")
    if status == 429:
        return Result("throttled")
    if status in (401, 403):
        return Result("access_denied")
    if status != 200:
        return Result("http_error")

    body = evidence.get("body")
    if not isinstance(body, dict):
        return Result("schema_error")
    errors = body.get("errors", [])
    if not isinstance(errors, list) or any(not isinstance(error, dict) for error in errors):
        return Result("schema_error")

    data = body.get("data")
    if data is None and errors:
        return Result("upstream_error")
    if not isinstance(data, dict) or not identifier(data.get("profile_id")):
        return Result("upstream_error" if errors else "schema_error")
    if data["profile_id"] != expected_profile_id:
        return Result("identity_mismatch")

    range_state = data.get("range_state")
    records = data.get("submissions")
    if range_state not in ("complete", "partial", "unknown") or not isinstance(records, list):
        return Result("upstream_error" if errors else "schema_error")

    accepted, rejected, unresolved, duplicates = count_records(records)
    can_advance = range_state == "complete" and not errors and unresolved == 0
    if can_advance:
        outcome = "complete" if records else "complete_empty"
    elif errors or unresolved or range_state == "partial":
        outcome = "partial"
    else:
        outcome = "coverage_unknown"
    return Result(outcome, can_advance, accepted, rejected, unresolved, duplicates)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", nargs="?", type=Path, default=Path(__file__).with_name("fixtures.json"))
    args = parser.parse_args()
    try:
        cases = json.loads(args.fixtures.read_text(encoding="utf-8"))
        if not isinstance(cases, dict) or not cases:
            parser.error("expected a non-empty JSON object of named cases")
        for case in cases.values():
            if not isinstance(case, dict) or not {"expected_profile_id", "evidence"} <= case.keys():
                parser.error("each case needs expected_profile_id and evidence")
    except (OSError, ValueError):
        parser.error("could not read the fixture file as JSON")
    results = {
        name: asdict(evaluate(case["expected_profile_id"], case["evidence"]))
        for name, case in cases.items()
    }
    print(json.dumps(results, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
