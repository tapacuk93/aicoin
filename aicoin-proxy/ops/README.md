# Proxy latency metrics and alerts

What: how much latency the proxy itself adds (`overhead_ms` in the access log,
see the main README's *Access log* section), pushed to CloudWatch every five
minutes, with alarms that email **tapacuk93@gmail.com** via SNS.

## Pieces and where they live

| Piece | Where |
|---|---|
| `push-overhead-metrics.py` | repo: this directory; box: `/opt/aicoin-proxy-metrics/push-overhead-metrics.py` |
| `aicoin-proxy-metrics.service` / `.timer` | repo: this directory; box: `/etc/systemd/system/` (timer enabled, fires at `*:0/5:10`) |
| Credentials | box only: `/etc/aicoin-proxy-metrics.env` (root, mode 600) — `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` |
| IAM user | `aicoin-proxy-metrics`, inline policy `PutMetricDataAICoinProxy`: only `cloudwatch:PutMetricData` with `cloudwatch:namespace = AICoin/Proxy` |
| Log it reads | `~/aicoin/logs/access.log.{1,0}` on the box — the container's `/opt/aicoin-proxy/logs`, bind-mounted by the box-only `docker-compose.prod.yml` so deploys no longer wipe it |
| Metrics | CloudWatch `us-east-1`, namespace `AICoin/Proxy`, no dimensions |
| Topic | SNS `aicoin-proxy-alerts` (`arn:aws:sns:us-east-1:798762732010:aicoin-proxy-alerts`), email subscription |

The script uses only the Python standard library (it signs the CloudWatch query
API request itself): the box has no AWS CLI and little RAM to spare.

## Metrics (one data point per 5-minute window)

Each run summarises the last *completed* window `[end-300s, end)` and stamps the
points at `end-1s`, so each CloudWatch 5-minute period gets exactly one point.

- `RequestCount` — every request line (always pushed, even 0: it is the heartbeat)
- `Error5xxCount` — status 500–599, any path
- `PaidRequestCount` — `POST /text`, `/image`, `/audio`, `/consortium`, and provider pass-through (`X-AI` set)
- `OverheadP50`, `OverheadP95`, `OverheadMax`, `UpstreamP95` (ms, nearest-rank) — over the paid requests; **not pushed** in a window with none

## Alarms (all notify `aicoin-proxy-alerts` on ALARM and on OK)

| Alarm | Condition |
|---|---|
| `aicoin-proxy-overhead-p95-high` | `OverheadP95` (Maximum per 300 s) > 500 ms in 3 of 3 periods; missing data = not breaching |
| `aicoin-proxy-5xx-errors` | `Error5xxCount` Sum ≥ 5 in one 300 s period; missing = not breaching |
| `aicoin-proxy-metrics-missing` | `RequestCount` SampleCount < 1 for 3 of 3 periods (15 min); missing = **breaching** — the pusher or the box stopped |

To change a threshold, re-run `aws cloudwatch put-metric-alarm` with the same
`--alarm-name` (it replaces the alarm); `aws cloudwatch describe-alarms
--alarm-name-prefix aicoin-proxy --region us-east-1` shows the current
definitions. To add a recipient: `aws sns subscribe --topic-arn <topic>
--protocol email --notification-endpoint <addr>` (they must click the
confirmation link).

## Install / update on the box

```bash
scp -i ~/.ssh/lightsail.pem ops/push-overhead-metrics.py ops/aicoin-proxy-metrics.* ubuntu@100.51.144.4:/tmp/
ssh -i ~/.ssh/lightsail.pem ubuntu@100.51.144.4 '
  sudo install -d /opt/aicoin-proxy-metrics &&
  sudo install -m 755 /tmp/push-overhead-metrics.py /opt/aicoin-proxy-metrics/ &&
  sudo install -m 644 /tmp/aicoin-proxy-metrics.service /tmp/aicoin-proxy-metrics.timer /etc/systemd/system/ &&
  sudo systemctl daemon-reload && sudo systemctl enable --now aicoin-proxy-metrics.timer'
```

The env file is created once, from a new access key of the IAM user (`aws iam
create-access-key --user-name aicoin-proxy-metrics`), piped over ssh into
`sudo install -m 600 -o root /dev/stdin /etc/aicoin-proxy-metrics.env` so the
secret never lands in git, a shell history or a command line.

Checking it:

```bash
python3 /opt/aicoin-proxy-metrics/push-overhead-metrics.py --dry-run              # last window
python3 /opt/aicoin-proxy-metrics/push-overhead-metrics.py --dry-run --window 3600 # last hour
journalctl -u aicoin-proxy-metrics.service -n 20
```
