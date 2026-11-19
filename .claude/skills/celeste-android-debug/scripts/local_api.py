"""Local Celeste stack helper for emulator tests.

usage:
  python3 -I local_api.py mkuser            -> registers a throwaway user + guild, writes $SCRATCH/user.json
  python3 -I local_api.py messages <cid>    -> last 5 messages of a channel (proves the app's send was persisted)
  python3 -I local_api.py send <cid> <text> -> posts as the test user (proves realtime receive in the app)
"""
import json
import os
import sys
import time

import requests

API = os.environ.get("API_BASE", "http://localhost:5002/api/v9")
STATE = os.path.join(os.environ.get("SCRATCH", os.path.expanduser("~/.cache/celeste-android")), "user.json")


def load():
    return json.load(open(STATE))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "mkuser":
        suffix = str(int(time.time()))
        email = f"android_test_{suffix}@example.com"
        r = requests.post(f"{API}/auth/register", json={
            "username": f"android_{suffix}", "email": email, "password": "password123",
            "date_of_birth": "2000-01-01", "consent": True, "global_name": "Android Test"}, timeout=10)
        r.raise_for_status()
        tok = r.json()["token"]
        g = requests.post(f"{API}/guilds", headers={"Authorization": tok}, json={"name": "Android Test Guild"}, timeout=10)
        g.raise_for_status()
        chans = requests.get(f"{API}/guilds/{g.json()['id']}/channels", headers={"Authorization": tok}, timeout=10).json()
        text = next(c["id"] for c in chans if c["type"] == 0)
        out = {"email": email, "password": "password123", "token": tok, "guild_id": g.json()["id"], "channel_id": text}
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        json.dump(out, open(STATE, "w"))
        print(json.dumps({k: v for k, v in out.items() if k != "token"}))
    elif cmd == "messages":
        u = load()
        cid = sys.argv[2] if len(sys.argv) > 2 else u["channel_id"]
        r = requests.get(f"{API}/channels/{cid}/messages?limit=5", headers={"Authorization": u["token"]}, timeout=10)
        print(r.status_code, [(m["id"], m["content"]) for m in r.json()])
    elif cmd == "send":
        u = load()
        r = requests.post(f"{API}/channels/{sys.argv[2]}/messages", headers={"Authorization": u["token"]},
                          json={"content": " ".join(sys.argv[3:])}, timeout=10)
        print(r.status_code)
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
