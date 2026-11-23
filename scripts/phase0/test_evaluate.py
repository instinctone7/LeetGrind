import json
import unittest
from pathlib import Path

from evaluate import Result, evaluate


class EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = json.loads(Path(__file__).with_name("fixtures.json").read_text(encoding="utf-8"))

    def test_every_fixture(self):
        expected = {
            "complete_mixed": Result("complete", True, 1, 1),
            "complete_empty": Result("complete_empty", True),
            "incomplete_record": Result("partial", accepted_candidates=1, unresolved_records=1),
            "out_of_order_overlap": Result("complete", True, 3, duplicate_records=1),
            "conflicting_submission": Result("partial", unresolved_records=1),
            "partial_error_with_data": Result("partial", accepted_candidates=1),
            "partial_empty": Result("partial"),
            "capped_feed": Result("coverage_unknown", accepted_candidates=1),
            "throttled": Result("throttled"),
            "timeout": Result("timeout"),
            "connection_error": Result("connection_error"),
            "access_denied": Result("access_denied"),
            "server_error": Result("http_error"),
            "upstream_error_without_data": Result("upstream_error"),
            "shape_drift": Result("schema_error"),
            "mismatched_identity": Result("identity_mismatch"),
        }
        self.assertEqual(set(expected), set(self.cases))
        for name, result in expected.items():
            with self.subTest(name=name):
                case = self.cases[name]
                self.assertEqual(evaluate(case["expected_profile_id"], case["evidence"]), result)

    def test_timezone_is_required(self):
        case = self.cases["complete_mixed"]
        record = case["evidence"]["body"]["data"]["submissions"][0]
        self.assertEqual(evaluate("fixture-profile-a", {
            "transport": "http",
            "status_code": 200,
            "body": {"data": {
                "profile_id": "fixture-profile-a",
                "range_state": "complete",
                "submissions": [dict(record, occurred_at="2026-01-05T10:05:00")],
            }},
        }), Result("partial", unresolved_records=1))

    def test_incomplete_duplicate_blocks_ambiguous_submission(self):
        case = self.cases["incomplete_record"]
        first, second = case["evidence"]["body"]["data"]["submissions"]
        evidence = {
            "transport": "http",
            "status_code": 200,
            "body": {"data": {
                "profile_id": "fixture-profile-a",
                "range_state": "complete",
                "submissions": [first, dict(second, submission_id=first["submission_id"])],
            }},
        }
        self.assertEqual(evaluate("fixture-profile-a", evidence), Result("partial", unresolved_records=1))


if __name__ == "__main__":
    unittest.main()
