"""Run the Phase 8 standings and sync workload on a fresh, disposable local Compose project."""

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path
import re
import secrets
from statistics import mean
import subprocess
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SEED = Path(__file__).with_name("seed.sql")
BURST = 150
TARGETS = {"leaderboard_p95_ms": 250, "errors": 0, "sync_freshness_p95_s": 45, "burst_drain_s": 60}
PERIODS = ("weekly",) * 14 + ("monthly",) * 4 + ("all-time",) * 2

SNAPSHOT = """
SELECT COUNT(CASE WHEN j.state IN ('QUEUED','IDLE','RETRY') AND j.next_run_at <= UTC_TIMESTAMP(6)
                       OR j.state = 'RUNNING' AND j.lease_expires_at <= UTC_TIMESTAMP(6) THEN 1 END),
       COUNT(CASE WHEN j.state = 'RUNNING' THEN 1 END),
       COUNT(l.last_success_at),
       ROUND(COALESCE(MAX(CASE WHEN j.state IN ('QUEUED','IDLE','RETRY') AND j.next_run_at <= UTC_TIMESTAMP(6)
          THEN TIMESTAMPDIFF(MICROSECOND,j.next_run_at,UTC_TIMESTAMP(6))/1000000
          WHEN j.state = 'RUNNING' AND j.lease_expires_at <= UTC_TIMESTAMP(6)
          THEN TIMESTAMPDIFF(MICROSECOND,j.lease_expires_at,UTC_TIMESTAMP(6))/1000000 END),0),3)
FROM sync_jobs j JOIN leetcode_links l ON l.id = j.link_id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_rows_read', 'Innodb_buffer_pool_reads', 'Threads_running');
SELECT COALESCE(SUM(COUNT_STAR),0), COALESCE(SUM(SUM_TIMER_WAIT),0), COALESCE(SUM(SUM_ROWS_EXAMINED),0)
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = DATABASE() AND DIGEST_TEXT LIKE '%DENSE_RANK%';
"""


def percentile(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1] if values else None


class Deployment:
    def __init__(self, image, port, db_port):
        self.project = "leetgrind-phase8-" + secrets.token_hex(5)
        self.origin = f"http://127.0.0.1:{port}"
        self.token = None
        self.env = {
            **os.environ,
            "LEETGRIND_JWT_SECRET": secrets.token_hex(32),
            "LEETGRIND_DB_PASSWORD": secrets.token_hex(16),
            "LEETGRIND_MYSQL_ROOT_PASSWORD": secrets.token_hex(16),
            "LEETGRIND_PORT": str(port),
            "LEETGRIND_MYSQL_PORT": str(db_port),
            "LEETGRIND_FIXTURES_ENABLED": "true",
        }
        self.override = json.dumps({"services": {
            "app": {"image": image, "cpus": 2.0, "mem_limit": "1g"},
            "db": {"cpus": 2.0, "mem_limit": "2g"},
        }})

    def compose(self, *args, stdin=None, capture=True):
        return subprocess.run(["docker", "compose", "-p", self.project, "-f", "compose.yaml", "-f", "-", *args],
                              input=self.override if stdin is None else stdin, cwd=ROOT, env=self.env,
                              text=True, stdout=subprocess.PIPE if capture else None, check=True, timeout=150).stdout

    def exec(self, service, *args, stdin=""):
        # The base Compose file is sufficient to address an existing project for exec.
        command = ["docker", "compose", "-p", self.project, "exec", "-T", service, *args]
        return subprocess.run(command, cwd=ROOT, env=self.env, input=stdin, text=True, check=True,
                              stdout=subprocess.PIPE, timeout=30).stdout

    def sql(self, statement):
        return self.exec("db", "sh", "-ec",
                         'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -u root --batch --skip-column-names "$MYSQL_DATABASE"',
                         stdin=statement).strip()

    def request(self, path, method="GET", body=None):
        headers = {"Origin": self.origin, "X-LeetGrind-Client": "browser", "Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = Request(self.origin + "/api/v1" + path, headers=headers, method=method,
                          data=None if body is None else json.dumps(body).encode())
        with urlopen(request, timeout=10) as response:
            return json.load(response)

    def bootstrap(self):
        if self.sql("SELECT COUNT(*) FROM accounts") != "0":
            raise RuntimeError("The capacity workload requires a fresh project with an empty account table")
        identity = {"email": "capacity-owner@example.test", "password": secrets.token_hex(16)}
        self.request("/auth/register", "POST", {**identity, "leetcodeHandle": "capacity_owner", "displayName": "Capacity Owner"})
        self.token = self.request("/auth/login", "POST", identity)["accessToken"]
        group = self.request("/groups", "POST", {"name": "Capacity test group"})["id"]
        if not re.fullmatch(r"[1-9][0-9]*", group):
            raise RuntimeError("Unexpected fixture group ID")
        self.sql(f"SET @bench_group = {group};\n" + SEED.read_text())
        return group

    def metric_count(self, name):
        output = self.exec("app", "curl", "--silent", "http://127.0.0.1:8080/actuator/metrics/" + name)
        if not output:
            return None
        payload = json.loads(output)
        if "measurements" not in payload:
            return None
        return next((row["value"] for row in payload["measurements"] if row["statistic"] == "COUNT"), None)

    def snapshot(self):
        rows = [line.split("\t") for line in self.sql(SNAPSHOT).splitlines()]
        due, running, completed, age = rows[0]
        db = {key: int(value) for key, value in rows[1:-1]}
        queries, picoseconds, examined = map(int, rows[-1])
        return {"due": int(due), "running": int(running), "completed": int(completed), "oldest_due_s": float(age),
                **db, "ranking_queries": queries, "ranking_picoseconds": picoseconds, "ranking_rows_examined": examined}


def run_load(deployment, paths, duration, rps):
    with ThreadPoolExecutor(max_workers=16) as executor:
        list(executor.map(deployment.request, (paths[PERIODS[i % len(PERIODS)]] for i in range(60))))
    before = deployment.snapshot()
    first_fetch = deployment.metric_count("leetgrind.sync.provider.fetch.duration") or 0
    first_outcome = deployment.metric_count("leetgrind.sync.outcomes") or 0
    release = deployment.sql("""
        START TRANSACTION;
        SET @released = UTC_TIMESTAMP(6);
        UPDATE sync_jobs SET next_run_at = @released WHERE state = 'QUEUED';
        UPDATE leetcode_links SET activated_at = @released, verified_at = @released WHERE state = 'VERIFIED';
        UPDATE sync_control SET paused = FALSE WHERE id = 1;
        COMMIT;
        SELECT @released;
        """)
    start = time.monotonic()
    stop = threading.Event()
    samples = []

    def sample():
        while not stop.is_set():
            samples.append({"elapsed_s": round(time.monotonic() - start, 3), **deployment.snapshot()})
            stop.wait(2)

    with ThreadPoolExecutor(max_workers=1) as sampler, ThreadPoolExecutor(max_workers=24) as clients:
        monitor = sampler.submit(sample)
        pending = threading.BoundedSemaphore(48)
        requests = []
        errors = Counter()

        def request(period, scheduled):
            try:
                deployment.request(paths[period])
                return period, (time.monotonic() - scheduled) * 1000, None
            except (HTTPError, URLError, TimeoutError, ConnectionError) as failure:
                return period, None, str(getattr(failure, "code", type(failure).__name__))
            finally:
                pending.release()

        try:
            for i in range(duration * rps):
                scheduled = start + i / rps
                time.sleep(max(0, scheduled - time.monotonic()))
                if pending.acquire(blocking=False):
                    requests.append(clients.submit(request, PERIODS[i % len(PERIODS)], scheduled))
                else:
                    errors["client_queue_full"] += 1
            time.sleep(max(0, start + duration - time.monotonic()))
            latencies = defaultdict(list)
            for future in requests:
                period, milliseconds, error = future.result()
                if error:
                    errors[error] += 1
                else:
                    latencies[period].append(milliseconds)
        finally:
            stop.set()
        monitor.result()
    after = deployment.snapshot()
    fetch_count = deployment.metric_count("leetgrind.sync.provider.fetch.duration")
    completed_outcomes = deployment.metric_count("leetgrind.sync.outcomes")
    if not re.fullmatch(r"[0-9:\- .]+", release):
        raise RuntimeError("Unexpected fixture release timestamp")
    freshness = [float(line) for line in deployment.sql(f"""
        SELECT TIMESTAMPDIFF(MICROSECOND, '{release}', last_success_at)/1000000
        FROM leetcode_links WHERE state = 'VERIFIED' AND last_success_at IS NOT NULL
        """).splitlines()]
    if len(freshness) != after["completed"]:
        raise RuntimeError("The sync snapshot changed after the workload. Increase the measurement duration.")
    all_latencies = [value for series in latencies.values() for value in series]
    queries = after["ranking_queries"] - before["ranking_queries"]
    latencies["all"] = all_latencies
    result = {
        "http": {period: {"count": len(series), "p50_ms": percentile(series, .50), "p95_ms": percentile(series, .95),
                          "p99_ms": percentile(series, .99)} for period, series in latencies.items()},
        "errors": dict(errors),
        "sync": {"completed": len(freshness), "pending_end": after["due"], "running_end": after["running"],
                 "max_due": max([before["due"], *(row["due"] for row in samples)]),
                 "max_sampled_oldest_due_s": max((row["oldest_due_s"] for row in samples), default=0),
                 "freshness_p95_s": percentile(freshness, .95), "drain_s": max(freshness) if len(freshness) == BURST else None,
                 "provider_calls": None if fetch_count is None else fetch_count - first_fetch,
                 "recorded_outcomes": None if completed_outcomes is None else completed_outcomes - first_outcome},
        "database": {"ranking_queries": queries,
                     "mean_ranking_ms": (after["ranking_picoseconds"] - before["ranking_picoseconds"]) / queries / 1e9 if queries else None,
                     "mean_ranking_rows_examined": (after["ranking_rows_examined"] - before["ranking_rows_examined"]) / queries if queries else None,
                     "innodb_rows_read": after["Innodb_rows_read"] - before["Innodb_rows_read"],
                     "buffer_pool_physical_reads": after["Innodb_buffer_pool_reads"] - before["Innodb_buffer_pool_reads"],
                     "mean_sampled_threads_running": mean(row["Threads_running"] for row in samples)},
        "samples": samples,
    }
    return result


def authoritative_check(deployment, group, path):
    rows = deployment.sql(f"""
        SELECT x.account_id, SUM(x.amount) AS xp FROM xp_entries x
        WHERE x.provider = 'FIXTURE' AND x.reason = 'BASE_SOLVE' AND EXISTS (
            SELECT 1 FROM memberships m WHERE m.group_id = {group} AND m.account_id = x.account_id
              AND x.effective_at > m.joined_at AND (m.left_at IS NULL OR x.effective_at < m.left_at))
        GROUP BY x.account_id ORDER BY xp DESC, x.account_id LIMIT 100;
        """).splitlines()
    first = deployment.request(path)
    second = deployment.request(path + "&cursor=" + first["nextCursor"])
    actual = [(row["id"], row["xp"], row["rank"]) for row in first["items"] + second["items"]]
    expected = []
    rank, previous_xp = 0, None
    for row in rows:
        account, xp = row.split("\t")
        if xp != previous_xp:
            rank += 1
        expected.append((account, int(xp), rank))
        previous_xp = xp
    if actual != expected:
        raise RuntimeError("The first two pages disagree with the authoritative ledger")
    earned = deployment.sql("""
        SELECT COUNT(*), COALESCE(SUM(x.amount),0), COUNT(DISTINCT x.account_id)
        FROM xp_entries x JOIN leetcode_links l ON l.account_id = x.account_id AND l.state = 'VERIFIED'
        WHERE x.reason = 'BASE_SOLVE' AND x.provider = 'FIXTURE';
        """).split("\t")
    if earned != [str(BURST), str(BURST * 25), str(BURST)]:
        raise RuntimeError(f"Expected one 25 XP award per burst account, observed {earned}")
    return {"ranked_rows_reconciled": len(actual), "base_awards": int(earned[0]), "base_xp": int(earned[1])}


def benchmark(args):
    subprocess.run(["docker", "image", "inspect", args.image], stdout=subprocess.DEVNULL, check=True)
    deployment = Deployment(args.image, args.port, args.db_port)
    try:
        deployment.compose("up", "--no-build", "-d", "--wait", capture=False)
        group = deployment.bootstrap()
        utc_date = datetime.now(timezone.utc).date()
        monday = utc_date - timedelta(days=utc_date.weekday())
        base = f"/groups/{group}/leaderboard?mode=demo&limit=50"
        paths = {"weekly": f"{base}&period=weekly&week={monday}",
                 "monthly": f"{base}&period=monthly&month={utc_date:%Y-%m}",
                 "all-time": f"{base}&period=all-time"}
        result = run_load(deployment, paths, args.duration, args.rps)
        result["correctness"] = authoritative_check(deployment, group, paths["all-time"])
        result["configuration"] = {"image": args.image, "app_cpus": 2, "app_memory_mib": 1024, "mysql_cpus": 2,
                                   "mysql_memory_mib": 2048, "duration_s": args.duration, "requests_per_s": args.rps,
                                   "history_accounts": 600, "history_solves": 108000, "history_bonuses": 21600,
                                   "sync_jobs": BURST, "traffic_mix": dict(Counter(PERIODS)), "utc_date": str(utc_date)}
        result["local_targets"] = TARGETS
        freshness = result["sync"]["freshness_p95_s"]
        drain = result["sync"]["drain_s"]
        result["targets_met"] = {
            "leaderboard_p95": all(period in result["http"] and result["http"][period]["p95_ms"] <= TARGETS["leaderboard_p95_ms"]
                                   for period in paths),
            "errors": sum(result["errors"].values()) == TARGETS["errors"],
            "sync_freshness_p95": freshness is not None and freshness <= TARGETS["sync_freshness_p95_s"],
            "burst_drain": drain is not None and drain <= TARGETS["burst_drain_s"],
        }
        return result
    finally:
        deployment.compose("down", "--volumes", "--timeout", "10", capture=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Already built local application image")
    parser.add_argument("--output", required=True, type=Path, help="New JSON file for this run")
    parser.add_argument("--port", type=int, default=38188)
    parser.add_argument("--db-port", type=int, default=33388)
    parser.add_argument("--duration", type=int, default=100, help="Measured request window in seconds")
    parser.add_argument("--rps", type=int, default=24, help="Offered leaderboard requests per second")
    args = parser.parse_args()
    if not 90 <= args.duration <= 600 or not 1 <= args.rps <= 100:
        parser.error("Choose a duration from 90 to 600 seconds and a rate from 1 to 100 requests/sec")
    if args.output.exists() or args.output.is_symlink():
        parser.error("Choose a new output file")
    result = benchmark(args)
    with args.output.open("x", encoding="utf-8") as report:
        json.dump(result, report, indent=2)
        report.write("\n")
    print(f"Capacity results written to {args.output}")


if __name__ == "__main__":
    main()
