#!/usr/bin/env python3
"""Vaultwarden organization-import spike (build plan step 4, wiki decision 43).

Checks, against the STAGING Vaultwarden only, that the import API does what the portal will rely on:
invite new members, replace a group's members exactly, revoke with deleted=true, restore by listing the
member again, leave everyone and everything else alone, and repeat safely. Uses throwaway
@example.test addresses and removes what it created at the end (unless --keep).

Usage (SSH tunnel to the staging server open, Mailpit on 8025):
    python3 scripts/vaultwarden-spike.py [sandbox.env] [--keep]

Reads from the env file: PORTAL_ADAPTERS_VAULTWARDEN_BASEURL, _ORGANIZATIONID, _CLIENTID, _CLIENTSECRET
(service admin's personal API key, for reads and cleanup), _ORGAPIKEY (the organization's API key, for the
import), and _TRUSTEDCERTIFICATE (staging CA). Never prints secrets or tokens.
"""
import json
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENV_FILE = next((a for a in sys.argv[1:] if not a.startswith("--")), "sandbox.env")
KEEP = "--keep" in sys.argv
MAILPIT = "http://localhost:8025"
RUN = time.strftime("%Y%m%d%H%M%S")
results = []


def load_env(path):
    values = {}
    for line in open(path):
        line = line.rstrip("\n")
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        name, raw = line.split("=", 1)
        values[name.strip()] = json.loads(raw) if raw.startswith('"') else raw
    return values


env = load_env(ENV_FILE)
P = "PORTAL_ADAPTERS_VAULTWARDEN_"
missing = [n for n in ("BASEURL", "ORGANIZATIONID", "CLIENTID", "CLIENTSECRET", "ORGAPIKEY") if not env.get(P + n, "").strip()]
if missing:
    print("Missing in", ENV_FILE + ":", ", ".join(P + n for n in missing))
    if "ORGAPIKEY" in missing:
        print("  ORGAPIKEY: in the web vault open the organization -> Settings -> API key -> View API key;")
        print("  copy the client_secret (the client_id is organization.<organization id>).")
    sys.exit(2)

BASE = env[P + "BASEURL"].rstrip("/")
ORG = env[P + "ORGANIZATIONID"]
TLS = ssl.create_default_context(cafile=env.get(P + "TRUSTEDCERTIFICATE") or None)
API = f"{BASE}/api/organizations/{ORG}"


def call(method, url, token=None, body=None, form=None):
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    context = TLS if url.startswith("https") else None
    try:
        with urllib.request.urlopen(request, context=context, timeout=30) as response:
            text = response.read().decode() or "null"
            return response.status, json.loads(text)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))
    return ok


def token(form):
    status, body = call("POST", f"{BASE}/identity/connect/token", form=form)
    return body.get("access_token") if status == 200 and isinstance(body, dict) else None, status, body


# 1. Tokens ----------------------------------------------------------------------------------
org_token, status, body = token({
    "grant_type": "client_credentials", "scope": "api.organization",
    "client_id": f"organization.{ORG}", "client_secret": env[P + "ORGAPIKEY"],
})
if not check("organization API key gives a token (scope api.organization)", org_token is not None, f"HTTP {status}" if not org_token else ""):
    print("  response:", body)
    sys.exit(1)
user_token, status, _ = token({
    "grant_type": "client_credentials", "scope": "api", "client_id": env[P + "CLIENTID"], "client_secret": env[P + "CLIENTSECRET"],
    "device_identifier": "h4i-portal-spike", "device_name": "h4i-portal-spike", "device_type": "21",
})
if not check("service admin API key gives a token (for reads and cleanup)", user_token is not None, f"HTTP {status}" if not user_token else ""):
    sys.exit(1)


# Reads ----------------------------------------------------------------------------------------
def members():
    _, body = call("GET", f"{API}/users?includeGroups=true", user_token)
    return {m["id"]: m for m in body["data"]}


def groups():
    _, body = call("GET", f"{API}/groups", user_token)
    return {g["id"]: g for g in body["data"]}


def group_members(group_id):
    _, body = call("GET", f"{API}/groups/{group_id}/users", user_token)
    ids = body["data"] if isinstance(body, dict) and "data" in body else body
    return sorted(i if isinstance(i, str) else i.get("id") for i in ids)


def by_external(external_id):
    return next((m for m in members().values() if m.get("externalId") == external_id), None)


def import_(payload, label):
    status, body = call("POST", f"{BASE}/api/public/organization/import", org_token, body=payload)
    return check(f"import accepted: {label}", status == 200, f"HTTP {status} {body}" if status != 200 else "")


before_members = members()
before_groups = {g: group_members(g) for g in groups()}
print(f"\nBefore: {len(before_members)} members, {len(before_groups)} groups. Run id {RUN}.\n")

A, B = f"spike-{RUN}-a", f"spike-{RUN}-b"
EMAIL = {A: f"vw-spike-{RUN}-a@example.test", B: f"vw-spike-{RUN}-b@example.test"}
GROUP_EXT, GROUP_NAME = f"spike-group-{RUN}", f"spike-{RUN}"


def member_entry(external_id, deleted=False):
    return {"email": EMAIL[external_id], "externalId": external_id, "deleted": deleted}


def spike_group():
    return next((g for g in groups().values() if g.get("externalId") == GROUP_EXT), None)


def others_unchanged(label):
    now = members()
    changed = [m for m in before_members if m not in now or now[m]["status"] != before_members[m]["status"]]
    check(f"existing members untouched ({label})", not changed, f"{len(changed)} changed" if changed else "")
    now_groups = {g: group_members(g) for g in before_groups}
    changed_groups = [g for g in before_groups if now_groups.get(g) != before_groups[g]]
    check(f"existing groups untouched ({label})", not changed_groups, f"{len(changed_groups)} changed" if changed_groups else "")


# 2. Invite two people and create a group with one of them ------------------------------------
import_({"members": [member_entry(A), member_entry(B)], "groups": [{"name": GROUP_NAME, "externalId": GROUP_EXT, "memberExternalIds": [A]}], "overwriteExisting": False}, "invite 2 + new group")
a, b = by_external(A), by_external(B)
check("both new people are members, matched by externalId", a is not None and b is not None)
check("new members start as Invited (status 0)", a is not None and b is not None and a["status"] == 0 and b["status"] == 0,
      f"statuses {a and a['status']}, {b and b['status']}")
check("member count grew by exactly 2", len(members()) == len(before_members) + 2)
g = spike_group()
check("group created from externalId", g is not None)
check("group holds exactly the listed member", g is not None and group_members(g["id"]) == sorted([a["id"]]))
others_unchanged("after invite")

# 3. Invite emails reach Mailpit ---------------------------------------------------------------
deadline, found = time.time() + 20, set()
while time.time() < deadline and len(found) < 2:
    try:
        with urllib.request.urlopen(f"{MAILPIT}/api/v1/messages?limit=200", timeout=10) as r:
            for message in json.loads(r.read().decode()).get("messages", []):
                for to in message.get("To", []):
                    if to.get("Address") in EMAIL.values():
                        found.add(to["Address"])
    except OSError:
        break
    time.sleep(1)
check("invite emails sent to both (seen in Mailpit)", len(found) == 2, f"{len(found)} of 2" + ("" if len(found) == 2 else "; is the 8025 tunnel open?"))

# 4. A group's members are replaced by the list sent -------------------------------------------
import_({"members": [], "groups": [{"name": GROUP_NAME, "externalId": GROUP_EXT, "memberExternalIds": [A, B]}], "overwriteExisting": False}, "group now lists both")
check("group members replaced by the new list", group_members(spike_group()["id"]) == sorted([a["id"], b["id"]]))
import_({"members": [], "groups": [{"name": GROUP_NAME, "externalId": GROUP_EXT, "memberExternalIds": [B]}], "overwriteExisting": False}, "group now lists only b")
check("a member left out of the list is removed from the group", group_members(spike_group()["id"]) == sorted([b["id"]]))

# 5. deleted=true revokes; listing again restores -------------------------------------------------
import_({"members": [member_entry(B, deleted=True)], "groups": [], "overwriteExisting": False}, "revoke b")
check("deleted=true revokes the member (status -1)", by_external(B)["status"] == -1, f"status {by_external(B)['status']}")
check("a member not in the payload stays (overwriteExisting=false)", by_external(A) is not None and by_external(A)["status"] == 0)
import_({"members": [member_entry(B)], "groups": [], "overwriteExisting": False}, "list b again")
check("listing a revoked member again restores them", by_external(B)["status"] >= 0, f"status {by_external(B)['status']}")

# 6. Repeating an import is safe ------------------------------------------------------------------
count = len(members())
for _ in range(2):
    import_({"members": [member_entry(A), member_entry(B)], "groups": [{"name": GROUP_NAME, "externalId": GROUP_EXT, "memberExternalIds": [A]}], "overwriteExisting": False}, "repeat")
check("repeating the same import creates nothing new", len(members()) == count and group_members(spike_group()["id"]) == sorted([by_external(A)["id"]]))
others_unchanged("at the end")

# 7. Clean up ---------------------------------------------------------------------------------------
if KEEP:
    print(f"\n--keep: left {EMAIL[A]}, {EMAIL[B]} and group {GROUP_NAME} in the organization.")
else:
    for external_id in (A, B):
        member = by_external(external_id)
        if member:
            call("DELETE", f"{API}/users/{member['id']}", user_token)
    group = spike_group()
    if group:
        call("DELETE", f"{API}/groups/{group['id']}", user_token)
    check("cleanup: organization back to its starting members and groups", set(members()) == set(before_members) and set(groups()) == set(before_groups))

failed = [name for name, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} checks passed." + (" Failed: " + "; ".join(failed) if failed else ""))
print("Not covered by the import API (by design): accepting an invite (the person does it) and confirming a member (Bitwarden CLI, step 9).")
sys.exit(1 if failed else 0)
