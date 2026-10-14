"""GitHub repositories, phase 1 (wiki: github-repos): checks the sandbox GitHub App can create repositories (empty and
from a template), mark and list templates, and give a team access, using the calls the portal makes.

Needs Repository -> Administration: read and write on the sandbox App, and its installation on all repositories.
Reads sandbox.env (the key may be inline or a file path); never prints a secret. Refuses any org but the sandbox.
Everything it creates is named portal-repo-check-* and deleted at the end.

Usage (from Membership-Project-Portal/; needs PyJWT with cryptography): python3 scripts/github-repo-check.py [sandbox.env]
"""
import json, sys, time, urllib.request, urllib.error
import jwt

ENV = sys.argv[1] if len(sys.argv) > 1 else "sandbox.env"
env = {}
for line in open(ENV):
    line = line.rstrip("\n")
    if not line or line.lstrip().startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
        v = v[1:-1]
    env[k.strip()] = v

ORG = env["PORTAL_ADAPTERS_GITHUB_ORG"]
APP = env["PORTAL_ADAPTERS_GITHUB_APPID"]
INST = env["PORTAL_ADAPTERS_GITHUB_INSTALLATIONID"]
raw_key = env["PORTAL_ADAPTERS_GITHUB_PRIVATEKEY"]
import os
key = open(raw_key).read() if os.path.exists(raw_key) else raw_key.replace("\\n", "\n")  # inline or a file, like the app
API = "https://api.github.com"
assert ORG == "Sandbox-H4I-Stage", "not the sandbox org"


def call(method, path, body=None, token=None, bearer=None, ok=(200, 201, 202, 204)):
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Authorization", f"Bearer {bearer or token}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            msg = json.loads(raw).get("message")
        except Exception:
            msg = raw[:200]
        return e.code, {"message": msg}


now = int(time.time())
app_jwt = jwt.encode({"iat": now - 60, "exp": now + 540, "iss": APP}, key, algorithm="RS256")
status, inst = call("GET", f"/app/installations/{INST}", bearer=app_jwt)
print("installation:", status, "repository_selection =", inst.get("repository_selection"))
print("permissions:", json.dumps(inst.get("permissions"), sort_keys=True))
status, tok = call("POST", f"/app/installations/{INST}/access_tokens", bearer=app_jwt)
assert status == 201, (status, tok)
T = tok["token"]

results = []
def step(name, status, body, expect=(200, 201, 202, 204)):
    good = status in expect
    results.append((name, good))
    print(("OK  " if good else "FAIL"), name, status, "" if good else (body or {}).get("message"))
    return good

team, empty, template, generated = "portal-repo-check", "portal-repo-check-empty", "portal-repo-check-template", "portal-repo-check-from-template"
try:
    s, b = call("POST", f"/orgs/{ORG}/teams", {"name": team, "privacy": "closed"}, T)
    step("create team", s, b)
    slug = (b or {}).get("slug", team)

    s, b = call("POST", f"/orgs/{ORG}/repos", {"name": empty, "private": True, "auto_init": True, "description": "portal repo check (deleted after)"}, T)
    step("create empty private repo (POST /orgs/{org}/repos, auto_init)", s, b)
    if s == 201:
        print("     private =", b.get("private"), "default_branch =", b.get("default_branch"))

    s, b = call("POST", f"/orgs/{ORG}/repos", {"name": template, "private": False, "auto_init": True, "is_template": True}, T)
    step("create public template repo (is_template)", s, b)
    if s == 201:
        print("     is_template =", b.get("is_template"), "private =", b.get("private"))

    s, b = call("GET", f"/orgs/{ORG}/repos?per_page=100&type=all", token=T)
    if step("list org repos", s, b):
        print("     templates seen:", [r["name"] for r in b if r.get("is_template")])

    s, b = call("POST", f"/repos/{ORG}/{template}/generate", {"owner": ORG, "name": generated, "private": False, "description": "portal repo check (deleted after)"}, T)
    step("generate public repo from template (POST /repos/{owner}/{template}/generate)", s, b)

    for repo, perm in [(empty, "push"), (generated, "maintain")]:
        s, b = call("PUT", f"/orgs/{ORG}/teams/{slug}/repos/{ORG}/{repo}", {"permission": perm}, T)
        step(f"give team {perm} on {repo}", s, b)
    s, b = call("GET", f"/orgs/{ORG}/teams/{slug}/repos?per_page=100", token=T)
    if step("list team repos", s, b):
        print("     team repos:", {r["name"]: r.get("role_name") for r in b})
    s, b = call("PUT", f"/orgs/{ORG}/teams/{slug}/repos/{ORG}/{empty}", {"permission": "pull"}, T)
    step("change team access to pull", s, b)
    s, b = call("GET", f"/repos/{ORG}/{empty}/teams", token=T)
    if step("read a repo's teams", s, b):
        print("     teams on empty repo:", {t["slug"]: t.get("permission") for t in b})
    s, b = call("POST", f"/orgs/{ORG}/repos", {"name": empty, "private": True}, T)
    step("a taken name is refused (expect 422)", s, b, expect=(422,))
    if s == 422:
        print("     message:", b.get("message"))
finally:
    for repo in (generated, template, empty):
        s, b = call("DELETE", f"/repos/{ORG}/{repo}", token=T)
        print("cleanup delete", repo, s)
    s, b = call("DELETE", f"/orgs/{ORG}/teams/{team}", token=T)
    print("cleanup delete team", s)

print("\nall passed" if all(g for _, g in results) else "\nsome failed")
