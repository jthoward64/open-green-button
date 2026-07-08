#!/usr/bin/env python3
"""Diagnostic probe for the Open Green Button proxy's /proxy/usage endpoint.

Redeems a one-time claim code, then fires a sweep of /proxy/usage requests with
different published-min/max shapes, so we can see what a Data Custodian accepts.

savagedata uses ONE-TIME refresh tokens: every call rotates the token, and the
proxy returns the rotated blob via the `OpenGB-New-*` response headers (even on
errors). We chain those into the next call. NOTE: api.opengreenbutton.org is HTTP/2,
which lowercases header names — http.client's HTTPMessage.get() is case-insensitive,
so we keep the raw message rather than dict()-ing it (that bug burned tokens early on).

We deliberately do NOT send a no-params request: without a date filter savagedata
tries to assemble everything and hangs, which never returns the rotated blob and
breaks the chain.

Usage:
    python3 proxy_probe.py <claim_code> [date_filter_base]

    <claim_code>       e.g. gb_live_...   (single use — generate a fresh one per run)
    [date_filter_base] optional; default "published". Pass "updated" to send
                       updated-min/updated-max instead (requires the proxy's
                       dateFilterParam override to be deployed).

Edit the probe() calls at the bottom to try other windows/params.
"""
import datetime
import json
import sys
import urllib.error
import urllib.request
from datetime import timedelta, timezone

BASE = "https://api.opengreenbutton.org"
APIV = "2026-05-22"

if len(sys.argv) < 2:
    sys.exit("usage: proxy_probe.py <claim_code> [date_filter_base]")
CLAIM = sys.argv[1]
FILTER_BASE = sys.argv[2] if len(sys.argv) > 2 else None  # None -> proxy default "published"


def post(url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method="POST")
    try:
        r = urllib.request.urlopen(req, timeout=120)
        return r.status, r.headers, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode(errors="replace")  # e.headers = case-insensitive


st, _, body = post(f"{BASE}/claim/{CLAIM}", headers={"OpenGB-Api-Version": APIV})
print("claim redeem:", st)
if st != 200:
    sys.exit(body[:400])
d = json.loads(body)
blob, tok = d["encryptedRefreshBlob"], d["proxyToken"]
print("subscriptionUri:", d.get("subscriptionUri"))

now = datetime.datetime.now(timezone.utc).replace(microsecond=0)
iso = lambda dt: dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def probe(label, pmin, pmax):
    global blob, tok
    b = {"encryptedRefreshBlob": blob}
    if pmin is not None:
        b["publishedMin"] = iso(pmin)
    if pmax is not None:
        b["publishedMax"] = iso(pmax)
    if FILTER_BASE:
        b["dateFilterParam"] = FILTER_BASE
    t0 = datetime.datetime.now()
    st, hh, resp = post(
        f"{BASE}/proxy/usage",
        data=json.dumps(b).encode(),
        headers={"Authorization": f"Bearer {tok}", "Content-Type": "application/json",
                 "OpenGB-Api-Version": APIV},
    )
    dt = (datetime.datetime.now() - t0).total_seconds()
    nb, nt = hh.get("OpenGB-New-Encrypted-Refresh-Blob"), hh.get("OpenGB-New-Proxy-Token")
    if nb and nt:
        blob, tok = nb, nt
        rot = "[rotated]"
    else:
        rot = "[NO-ROT — chain will break next call]"
    has_data = any(m in resp for m in ("<entry", "UsagePoint", "IntervalReading"))
    print(f"\n=== {label} (base={FILTER_BASE or 'published'}) ===")
    print(f"  min={b.get('publishedMin', '-')}  max={b.get('publishedMax', '-')}")
    print(f"  HTTP {st}  {hh.get('Content-Type', '')}  {len(resp)}B  {dt:.1f}s  {rot}  data={has_data}")
    print("  ->", resp[:280].replace("\n", " ").replace("\r", ""))
    return st != 401  # 401 == token chain broken; stop


(probe("min-only [now-1d]", now - timedelta(days=1), None)
 and probe("max-only [now]", None, now)
 and probe("both, tiny [now-1d..now]", now - timedelta(days=1), now)
 and probe("both, 30d [now-30d..now]", now - timedelta(days=30), now))
