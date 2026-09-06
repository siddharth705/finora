# Operational monitoring

Prometheus scrape config, alert rules and Grafana dashboards for Finora — as code, reviewable, and
runnable locally against a real backend.

Engineering context lives in [`docs/engineering/observability.md`](../../docs/engineering/observability.md).
This file is how to run it.

---

## What is here

| File | Purpose |
|---|---|
| `prometheus.yml` | Scrape config. Reads a bearer token from a gitignored file. |
| `alerts.yml` | 10 alert rules, every one on a sustained condition. |
| `grafana/dashboards/worker-health.json` | Worker + queue + infrastructure dashboard. |
| `grafana/dashboards/reconciliation.json` | Reconciliation transfer-matching and duplicate-override counters (measurement only, no alerts yet). |
| `grafana/provisioning/` | Datasource and dashboard provisioning, so the stack works on first run. |
| `docker-compose.yml` | Local Prometheus + Grafana for validating the above. |

**This is not a production deployment.** Production Prometheus and Grafana are infrastructure
decisions — retention, HA, auth, network placement — and belong outside this repository. What is
here is the configuration those instances should load, in a form that can be reviewed in a pull
request and proven to work before it is relied upon.

---

## External monitoring

Production availability is monitored externally through **Better Stack**, using an HTTP(S) monitor
against `https://api.fynora.net/actuator/health`. The monitor expects HTTP `200` and a body
containing `"status":"UP"` — a status-code-only check would pass against a misconfigured proxy or a
maintenance page serving the wrong body, so it checks both.

This is deliberately the only endpoint monitored externally. `/api/v1/**` requires authentication and
`/actuator/prometheus` / `/swagger-ui.html` are intentionally not `permitAll` (see
[Security](#security)) — pointing an unauthenticated external monitor at any of them would either
need a credential it shouldn't hold or report a permanent false "down" against a control that is
working exactly as designed.

No Better Stack credentials, API keys, or other provider-specific configuration live in this
repository — the monitor is configured directly in Better Stack's dashboard, not as code here. This
closes the gap the rest of this document is honest about above: production Prometheus/Grafana don't
exist yet, but production is no longer unwatched.

---

## Running it locally

The scrape endpoint is authenticated, so this needs a token. That is deliberate — see
[Security](#security).

```bash
# 1. Start the backend (from the repo root)
cd backend && ./mvnw spring-boot:run

# 2. Mint a token for an account you control and write it with NO trailing newline.
#    This file is gitignored.
printf '%s' '<your-jwt>' > ops/monitoring/scrape-token

# 3. Start the stack
docker compose -f ops/monitoring/docker-compose.yml up
```

- Prometheus — http://localhost:9090 (check **Status → Targets** first)
- Grafana — http://localhost:3001, dashboard under the **Finora** folder

### Generating worker activity to look at

An empty dashboard proves nothing. Confirm a merchant category in the app — that enqueues a
`merchant_learning_events` row, which the worker picks up within 30s — and watch
`finora_worker_completed_total` and `finora_worker_queue_depth` move.

To see the failure paths, the fastest route is a unit test rather than production-shaped data:
`WorkerObservabilityTest` drives every lifecycle event, and `WorkerMetricsExportIT` asserts they
reach a scrape.

### If the target is DOWN

Almost always one of three things, in order of likelihood:

1. **No token file**, or it has a trailing newline. `printf` rather than `echo`.
2. **Token expired.** Access tokens are 15 minutes by default.
3. **Backend not reachable at `host.docker.internal:8080`.** On Linux the compose file declares
   `host-gateway` for this; check the backend is bound to `0.0.0.0` rather than `127.0.0.1`.

A 401 renders identically to a down service in Prometheus' target list — check the **Error** column,
which shows the status code.

---

## Security

`/actuator/prometheus` is **authenticated**. `SecurityConfig` permits `/actuator/health` and nothing
else, and `WorkerMetricsExportIT` asserts that anonymous access fails.

That assertion exists specifically to catch the tempting fix. Adding `/actuator/**` to `permitAll`
would make Prometheus work immediately and publish queue depths, error rates and JVM internals to
the internet in the same move. **The scrape carries no customer data, but it is useful
reconnaissance.**

For production, in order of preference:

1. **Private network scraping.** Prometheus reaches the backend on Railway's internal network; the
   endpoint is never publicly routable. Best posture, and no long-lived credential to rotate.
2. **Internal service authentication.** A dedicated scrape principal scoped to metrics only.
3. **Infrastructure access control.** IP allowlist or mTLS in front of the endpoint.

A hand-minted user token is fine for local validation and **is not acceptable in production** — it
carries a real user's authorities and expires on that user's schedule.

---

## Changing a dashboard or an alert

Edit the file, not the Grafana UI. `allowUiUpdates: false` is set deliberately: a panel edited in
the UI is lost on the next container restart, and a query nobody reviewed is how a dashboard ends up
quietly showing the wrong series.

`scripts/check-dashboard-metrics.py` runs in CI and pre-commit, and fails if a query references a
metric the framework never emits. It exists because **an empty panel and a healthy system look
identical** — and because Micrometer renames on the way out, so the string a dashboard needs is
never the string in the Java source:

| In Java | On the scrape |
|---|---|
| `finora.worker.dead_letters` (counter) | `finora_worker_dead_letters_total` |
| `finora.worker.duration` (timer) | `finora_worker_duration_seconds_bucket` / `_count` / `_sum` |
| `finora.worker.oldest_pending_age` (gauge, `baseUnit("seconds")`) | `finora_worker_oldest_pending_age_seconds` |

---

## Alert thresholds are starting points, not measurements

Finora has no production traffic history, so any threshold claiming to be tuned would be invented.
They are set where a human would want to *look*, not where a human would want to be *woken*, and
should be revised against a real baseline.

The one exception is `QueueAgeExceedsSla`, which maps to a product promise — a confirmed
categorisation takes effect promptly — rather than to a rate nobody has measured.

Every rule has a `for:` clause. A rule without one fires on a scrape blip, and an alert that cries
wolf is worse than no alert: it trains people to close the tab.
