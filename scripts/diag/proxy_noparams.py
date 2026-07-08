#!/usr/bin/env python3
"""Validate the proxy's behaviour on a NO-PARAMS /proxy/usage request (the full ~44 MB dump).

Redeeming the claim first also wakes the scale-to-zero machine, so the subsequent no-params call
runs warm — isolating the "savagedata assembles everything" cost from Fly cold-start. The client
timeout is set high (330s) so WE never cut it: whatever ends the request is the proxy or Fly's edge.

  python3 scripts/diag/proxy_noparams.py <fresh_claim_code>

Read the result:
  - HTTP 200, ~44 MB, ~10s        -> proxy + timeouts handle the full dump fine.
  - HTTP 502 EMPTY body, no OpenGB-New-* headers -> Fly edge gave up (app never emitted a byte).
  - HTTP 5xx with JSON utility_upstream_error   -> OUR client timeout / error path fired.
"""
import datetime
import json
import sys
import urllib.error
import urllib.request

BASE = "https://api.opengreenbutton.org"
APIV = "2026-05-22"

if len(sys.argv) < 2:
    sys.exit("usage: proxy_noparams.py <claim_code>")
CLAIM = sys.argv[1]


def call(url, data=None, headers=None, timeout=330):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method="POST")
    t0 = datetime.datetime.now()
    try:
        r = urllib.request.urlopen(req, timeout=timeout)
        return r.status, r.headers, r.read(), (datetime.datetime.now() - t0).total_seconds()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read(), (datetime.datetime.now() - t0).total_seconds()
    except Exception as e:  # noqa: BLE001
        return None, None, str(e).encode(), (datetime.datetime.now() - t0).total_seconds()


st, _, body, dt = call(f"{BASE}/claim/{CLAIM}", headers={"OpenGB-Api-Version": APIV})
print(f"claim redeem (also warms the machine): HTTP {st} in {dt:.1f}s")
if st != 200:
    sys.exit(body.decode(errors="replace")[:300])
d = json.loads(body)
blob, tok = d["encryptedRefreshBlob"], d["proxyToken"]

print("\n→ POST /proxy/usage with NO params (full dump)…")
st, h, body, dt = call(
    f"{BASE}/proxy/usage",
    data=json.dumps({"encryptedRefreshBlob": blob}).encode(),
    headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json", "OpenGB-Api-Version": APIV},
)
print(f"  HTTP {st}  in {dt:.1f}s  body={len(body)}B")
if h is not None:
    for k in ("Content-Type", "OpenGB-New-Encrypted-Refresh-Blob", "OpenGB-New-Proxy-Token", "Server", "Via"):
        v = h.get(k)
        if v:
            print(f"  {k}: {v[:70]}")
print("  body head:", body[:200].decode(errors="replace").replace("\n", " ").replace("\r", ""))
