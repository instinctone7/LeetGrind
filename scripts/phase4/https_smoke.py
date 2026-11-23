"""Verify fixture journeys and the hosted profile on an isolated HTTPS stage."""

import argparse
import http.cookiejar
import json
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

PASSWORD = "staging-fixture-password"


class Client:
    def __init__(self, origin, ca):
        self.origin = origin
        self.access = None
        context = ssl.create_default_context(cafile=ca)
        self.http = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        )

    def request(self, path, method="GET", data=None, expected=200):
        headers = {"Origin": self.origin, "X-LeetGrind-Client": "browser"}
        if self.access:
            headers["Authorization"] = "Bearer " + self.access
        body = None
        if data is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(data).encode()
        request = urllib.request.Request(self.origin + path, data=body, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.status != expected:
                raise AssertionError(f"{method} {path} returned {response.status}; expected {expected}")
            content = response.read()
            if content and "application/json" in response.headers.get("Content-Type", ""):
                content = json.loads(content)
            return response.headers, content

    def api(self, path, method="GET", data=None, expected=200):
        return self.request("/api/v1" + path, method, data, expected)[1]

    def login(self, account):
        headers, body = self.request("/api/v1/auth/login", "POST", {
            "email": account["email"], "password": PASSWORD,
        })
        cookie = headers.get("Set-Cookie", "")
        assert cookie.startswith("__Host-leetgrind_refresh=")
        assert all(setting in cookie for setting in ("; Secure", "; HttpOnly", "; SameSite=Strict", "; Path=/"))
        self.access = body["accessToken"]

    def register(self, account, expected=201):
        return self.api("/auth/register", "POST", {**account, "password": PASSWORD}, expected)


def account(prefix):
    suffix = uuid.uuid4().hex[:12]
    return {"email": f"{prefix}-{suffix}@example.test", "leetcodeHandle": f"{prefix}_{suffix}", "displayName": prefix.title()}


def paths(state):
    return f"/groups/{state['groupId']}/leaderboard"


def assert_scores(owner, learner, state, demo_available=True):
    progress = learner.api("/me/progress")
    assert progress["fixtureXp"] == 25 and progress["totalXp"] == 0
    live = owner.api(paths(state) + "?mode=live")
    scores = [(live, 0)]
    if demo_available:
        scores.append((owner.api(paths(state) + "?mode=demo"), 25))
    else:
        owner.api(paths(state) + "?mode=demo", expected=400)
    for board, expected in scores:
        row = next(item for item in board["items"] if item["handle"] == state["learner"]["handle"])
        assert row["xp"] == expected
        assert set(row) == {"id", "handle", "displayName", "rank", "xp"}


def assert_hosted(client):
    denied = client.register(account("outside"), 403)
    assert denied["code"] == "signup_unavailable"
    invited = account("hosted")
    invited["email"] = "hosted-pilot@example.test"
    profile = client.register(invited)
    client.login(invited)
    client.access = None
    client.access = client.api("/auth/refresh", "POST")["accessToken"]
    assert client.api("/me")["id"] == profile["id"]
    assert client.api("/me/leetcode-link")["previewEnabled"] is False
    client.api("/me/leetcode-link/preview", expected=204)
    client.api("/me/rewards?mode=demo", expected=400)
    client.api("/me/check-in", "POST")
    client.api("/me/check-in", "POST")
    assert client.api("/me/rewards")["totalXp"] == 5
    assert client.api("/leaderboard")["items"][0]["xp"] == 5
    print("Hosted HTTPS verified pilot signup, session refresh, disabled preview and fixtures, and daily XP.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("seed", "check", "rollback", "pilot", "hosted"))
    parser.add_argument("--origin", required=True)
    parser.add_argument("--ca", required=True)
    parser.add_argument("--state", type=Path)
    args = parser.parse_args()
    if args.action != "hosted" and args.state is None:
        parser.error("--state is required for fixture journeys")
    origin = urllib.parse.urlsplit(args.origin)
    if origin.scheme != "https" or origin.hostname != "127.0.0.1" or origin.path or origin.query or origin.fragment:
        parser.error("use the isolated HTTPS staging origin on 127.0.0.1")
    owner = Client(args.origin, args.ca)
    learner = Client(args.origin, args.ca)

    _, page = owner.request("/")
    assert b"<title>LeetGrind</title>" in page
    owner.request("/actuator/health/readiness")
    owner.request("/actuator/metrics", expected=404)
    if args.action == "hosted":
        assert_hosted(owner)
    elif args.action == "seed":
        if args.state.exists():
            parser.error("use a new state file")
        state = {"owner": account("owner"), "learner": account("learner")}
        for client, role in ((owner, "owner"), (learner, "learner")):
            state[role]["handle"] = client.register(state[role])["handle"]
            client.login(state[role])
        group = owner.api("/groups", "POST", {"name": "HTTPS staging group"}, 201)
        state["groupId"] = group["id"]
        invite = owner.api(f"/groups/{group['id']}/invites", "POST", {"expiresInHours": 24, "maxUses": 1}, 201)
        learner.api("/group-memberships", "POST", {"code": invite["code"]})
        learner.api("/group-memberships", "POST", {"code": invite["code"]})
        assert owner.api(f"/groups/{group['id']}/invites")[0]["uses"] == 1
        profile = learner.api("/me")
        learner.api("/me/leetcode-link", "PUT", {"handle": "fixture-" + profile["id"]})
        for _ in range(20):
            if learner.api("/me/progress")["fixtureXp"] == 25:
                break
            time.sleep(1)
        assert_scores(owner, learner, state)
        with args.state.open("x", encoding="utf-8") as result:
            json.dump(state, result)
        print("CA-verified HTTPS journey credited one 25 XP fixture award.")
    else:
        state = json.loads(args.state.read_text())
        owner.login(state["owner"])
        learner.login(state["learner"])
        assert_scores(owner, learner, state, demo_available=args.action != "rollback")
        if args.action == "pilot":
            denied = owner.register(account("outside"), 403)
            assert denied["code"] == "signup_unavailable"
            allowed = account("pilot")
            allowed["email"] = "pilot-local@example.test"
            owner.register(allowed)
            print("Pilot registration admitted the allowlisted fixture and rejected an outsider.")
        print("HTTPS readiness, private group scores, secure cookies, and isolated metrics verified.")


if __name__ == "__main__":
    main()
