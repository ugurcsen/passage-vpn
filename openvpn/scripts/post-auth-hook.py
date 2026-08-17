#!/usr/bin/env python3
"""Example post-auth hook for the OpenVPN management panel.

Runs in the backend container after a successful VPN login when the server
setting `post_auth_script` names this file (e.g. `post-auth-hook.py`). The
script is best-effort: a failure is audited but never drops the connection.

Environment provided by the backend:
    username      authenticated account name
    common_name   certificate common name (equals the username here)
    remote_ip     client source address

This example appends a JSON line per login to /var/log/passage/post-auth.log
(PASSAGE_LOG_DIR, mounted into the backend container). Replace the body with
your own logic (SIEM push, device registration, ...).
"""

import json
import logging
import os

LOG_DIR = os.environ.get("PASSAGE_LOG_DIR", "/var/log/passage")
LOG_FILE = os.path.join(LOG_DIR, "post-auth.log")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("post-auth-hook")


def main() -> None:
    entry = {
        "event": "vpn_login",
        "username": os.environ.get("username", ""),
        "common_name": os.environ.get("common_name", ""),
        "remote_ip": os.environ.get("remote_ip", ""),
    }
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry) + "\n")
        log.info("recorded post-auth event for %s", entry["username"])
    except OSError as exc:
        log.error("cannot append to %s: %s", LOG_FILE, exc)


if __name__ == "__main__":
    main()
