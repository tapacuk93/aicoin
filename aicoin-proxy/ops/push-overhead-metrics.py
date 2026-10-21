#!/usr/bin/env python3
"""Turn the proxy's access log into CloudWatch metrics for the latency the proxy itself adds.

Runs every five minutes from a systemd timer on the proxy host (see ops/README.md). Each run
covers the last *completed* five-minute window [end-300s, end), where end is the most recent
multiple of 300 s, and stamps every data point inside that window, so each CloudWatch 5-minute
period gets exactly one point whatever the timer's jitter.

Namespace AICoin/Proxy, no dimensions:
  RequestCount     every request line in the window (pushed even when 0 - it is the heartbeat)
  Error5xxCount    of those, status 500-599
  PaidRequestCount POST /text, /image, /audio, /consortium and provider pass-through (X-AI set)
  OverheadP50/P95/OverheadMax, UpstreamP95   over the paid requests, in ms; only when there were any

Reads the log from the host bind mount (AICOIN_LOG_DIR). Standard library only: the host has no AWS CLI and 1 GB of RAM, and a SigV4-signed POST to the
CloudWatch query API is all PutMetricData needs. Credentials come from AWS_ACCESS_KEY_ID /
AWS_SECRET_ACCESS_KEY (the systemd unit loads them from a root-only env file).

  --dry-run         print the metrics instead of sending them
  --window SECONDS  with --dry-run: summarise the last SECONDS up to now instead
"""
import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.parse
import urllib.request

# The container's /opt/aicoin-proxy/logs, bind-mounted from the host so it survives redeploys.
LOG_DIR = os.environ.get("AICOIN_LOG_DIR", "/home/ubuntu/aicoin/logs")
TAIL_LINES = 50000
REGION = os.environ.get("AWS_REGION", "us-east-1")
NAMESPACE = "AICoin/Proxy"
PERIOD = 300
PAID_PATHS = {"/text", "/image", "/audio", "/consortium"}


def _tail(path, n):
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            # Files rotate at 10 MB and only the last few minutes matter: read the tail only.
            f.seek(max(0, size - 8 * 1024 * 1024))
            return f.read().decode("utf-8", "replace").splitlines()[-n:]
    except FileNotFoundError:
        return []


def read_lines():
    # .1 first: right after a rotation the start of the window is in the older file.
    lines = _tail(os.path.join(LOG_DIR, "access.log.1"), TAIL_LINES) + \
        _tail(os.path.join(LOG_DIR, "access.log.0"), TAIL_LINES)
    for line in lines:
        if not line.startswith("{"):
            continue
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if entry.get("kind"):  # routing decisions etc. - not requests
            continue
        yield entry


def epoch(at):
    # "2026-10-21T04:29:33.877493011Z" - second resolution is plenty for 5-minute windows.
    return dt.datetime.strptime(at[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=dt.timezone.utc).timestamp()


def is_paid(entry):
    if entry.get("provider"):
        return True
    return entry.get("method") == "POST" and entry.get("path") in PAID_PATHS


def pct(values, p):
    """Nearest-rank percentile."""
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(-(-p * len(s) // 100)) - 1))
    return s[k]


def summarise(start, end):
    total = errors = 0
    overhead, upstream = [], []
    for e in read_lines():
        try:
            t = epoch(e["at"])
        except (KeyError, ValueError):
            continue
        if not (start <= t < end):
            continue
        total += 1
        if 500 <= int(e.get("status", 0)) <= 599:
            errors += 1
        if is_paid(e) and "overhead_ms" in e:
            overhead.append(int(e["overhead_ms"]))
            upstream.append(int(e.get("upstream_ms", 0)))
    metrics = [("RequestCount", total, "Count"), ("Error5xxCount", errors, "Count"),
               ("PaidRequestCount", len(overhead), "Count")]
    if overhead:
        metrics += [("OverheadP50", pct(overhead, 50), "Milliseconds"),
                    ("OverheadP95", pct(overhead, 95), "Milliseconds"),
                    ("OverheadMax", max(overhead), "Milliseconds"),
                    ("UpstreamP95", pct(upstream, 95), "Milliseconds")]
    return metrics


def _sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def put_metrics(metrics, stamp):
    access, secret = os.environ["AWS_ACCESS_KEY_ID"], os.environ["AWS_SECRET_ACCESS_KEY"]
    host = "monitoring.%s.amazonaws.com" % REGION
    ts = dt.datetime.fromtimestamp(stamp, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    params = {"Action": "PutMetricData", "Version": "2010-08-01", "Namespace": NAMESPACE}
    for i, (name, value, unit) in enumerate(metrics, 1):
        params["MetricData.member.%d.MetricName" % i] = name
        params["MetricData.member.%d.Value" % i] = str(value)
        params["MetricData.member.%d.Unit" % i] = unit
        params["MetricData.member.%d.Timestamp" % i] = ts
    body = urllib.parse.urlencode(sorted(params.items()), quote_via=urllib.parse.quote)

    now = dt.datetime.now(dt.timezone.utc)
    amz_date, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    ctype = "application/x-www-form-urlencoded; charset=utf-8"
    canonical = "\n".join(["POST", "/", "", "content-type:" + ctype, "host:" + host,
                           "x-amz-date:" + amz_date, "", "content-type;host;x-amz-date",
                           hashlib.sha256(body.encode()).hexdigest()])
    scope = "%s/%s/monitoring/aws4_request" % (day, REGION)
    to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode()).hexdigest()])
    key = _sign(_sign(_sign(_sign(("AWS4" + secret).encode(), day), REGION), "monitoring"), "aws4_request")
    signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request("https://%s/" % host, data=body.encode(), method="POST", headers={
        "Content-Type": ctype, "X-Amz-Date": amz_date,
        "Authorization": "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=content-type;host;x-amz-date, "
                         "Signature=%s" % (access, scope, signature)})
    with urllib.request.urlopen(req, timeout=30) as resp:
        resp.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--window", type=int, default=0)
    args = ap.parse_args()

    now = time.time()
    if args.window:
        start, end = now - args.window, now
    else:
        end = (int(now) // PERIOD) * PERIOD
        start = end - PERIOD
    metrics = summarise(start, end)
    line = " ".join("%s=%s" % (n, v) for n, v, _ in metrics)
    if args.dry_run:
        print("window %s .. %s: %s" % (dt.datetime.fromtimestamp(start, dt.timezone.utc).isoformat(),
                                       dt.datetime.fromtimestamp(end, dt.timezone.utc).isoformat(), line))
        return
    put_metrics(metrics, end - 1)
    print("pushed %s" % line)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # surfaced in the journal; the heartbeat alarm catches persistent failure
        print("push-overhead-metrics failed: %s" % exc, file=sys.stderr)
        sys.exit(1)
