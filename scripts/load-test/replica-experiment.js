// D-4 evidence: RateLimiter and ImportConcurrencyLimiter are both in-memory, scoped to a single
// JVM instance (see their own class docs). The question this script exists to answer with real
// numbers, not inference from reading the code: does a second replica behind a round-robin
// load balancer actually let each instance's independent copy of the limit fire, so the
// EFFECTIVE ceiling seen by a client hitting the load balancer address is higher than the
// configured single-instance limit -- and by roughly how much?
//
// Two scenarios, selected by the SCENARIO env var:
//
//   login   -- fires a tight burst of BURST_SIZE login attempts (default 30, three times
//              RATE_LIMIT_LOGIN_MAX's default of 10) at BASE_URL, all with zero think-time, and
//              counts how many get a normal response (200/401 -- allowed through to the app)
//              versus 429 (rejected by RateLimiter). A single instance should let through at
//              most ~10; N instances behind a naive round-robin should let through up to
//              ~10*N, since each instance's ConcurrentHashMap has no idea the others exist.
//
//   import  -- fires a tight burst of BURST_SIZE import-stage requests (default 15, two and a
//              half times app.import.max-concurrent's default of 6) against one already-
//              authenticated session, and counts 200 (accepted, permit acquired) versus 503
//              IMPORT_SYSTEM_BUSY (rejected, ImportConcurrencyLimiter's semaphore exhausted).
//              A single instance should accept at most ~6 concurrently; N instances should
//              accept up to ~6*N, for the same "each instance's Semaphore is its own" reason.
//
// Run directly with k6 (not through run.sh -- this is a single sharp burst, not a sustained
// tier, so the resource-sampling loop that script wraps around a run doesn't apply here):
//   k6 run --env SCENARIO=login  --env BASE_URL=http://localhost:8080  scripts/load-test/replica-experiment.js
//   k6 run --env SCENARIO=import --env BASE_URL=http://localhost:8080  scripts/load-test/replica-experiment.js
// Swap BASE_URL to http://localhost:18090 (the nginx front door, see
// docker-compose.multi-replica.yml) to run the same burst against 1 or 2 backend replicas.

import http from "k6/http";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SCENARIO = __ENV.SCENARIO || "login";
const BURST_SIZE = Number(__ENV.BURST_SIZE || (SCENARIO === "import" ? 15 : 30));

// One seeded load-test user (scripts/load-test/seed.py) -- real credentials, so an "allowed"
// login attempt actually succeeds (200) rather than needing to distinguish a 429 from a 401 on
// wrong credentials. Every burst VU hits the SAME identifier and the SAME source IP (all of them
// run on this one k6 process/host), which is exactly the shape a credential-stuffing burst has --
// and exactly what RateLimiter's per-IP key is meant to catch on a single instance.
const LOGIN_EMAIL = __ENV.LOGIN_EMAIL || "loadtest0@loadtest.local";
const LOGIN_PASSWORD = __ENV.LOGIN_PASSWORD || "LoadTest123!";

const allowed = new Counter("replica_experiment_allowed");
const rejected = new Counter("replica_experiment_rejected");
const unexpected = new Counter("replica_experiment_unexpected_status");

export const options = {
  scenarios: {
    burst: {
      executor: "per-vu-iterations",
      vus: BURST_SIZE,
      iterations: 1,
      maxDuration: "30s",
      // No ramp-up: every VU starts in the same k6 scheduling tick, which is what makes this a
      // burst rather than a ramped load pattern. k6 itself is the limiting factor on how
      // perfectly simultaneous BURST_SIZE requests land, not this config.
      startTime: "0s",
    },
  },
};

function csvBody(vu) {
  // A real two-row statement, not a single-cell file -- large enough that TransactionNormalizer
  // + categorization + duplicate detection take long enough per call for concurrent requests to
  // genuinely overlap inside ImportConcurrencyLimiter's held permit, not just arrive close
  // together and be handled sequentially so fast the semaphore never sees more than one at once.
  //
  // `vu` varies the amount so every burst VU produces a DISTINCT content_hash. Identical bytes
  // across concurrent VUs (all one seeded user, one session) collide on
  // idx_import_sessions_live_content's UNIQUE(user_id, content_hash) constraint under real
  // concurrency -- a genuine bug this experiment surfaced (a 500, not a clean rejection) but a
  // different one than what this script exists to measure. Distinct content sidesteps it so the
  // burst exercises ONLY ImportConcurrencyLimiter, not that race too.
  const amount = (150 + vu).toFixed(2);
  return (
    "Date,Description,Amount,Type\n" +
    `2026-08-01,REPLICA EXPERIMENT COFFEE ${vu},${amount},DEBIT\n` +
    "2026-08-02,REPLICA EXPERIMENT SALARY,50000.00,CREDIT\n"
  );
}

export function setup() {
  if (SCENARIO !== "import") return {};
  // One login outside the timed burst -- the import scenario is testing
  // ImportConcurrencyLimiter, not RateLimiter, so authentication happens once, up front, and
  // every burst VU reuses the same token. Logging in inside the burst itself would mean the
  // burst also exercises the LOGIN limiter, muddying which limiter a rejection came from.
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ identifier: LOGIN_EMAIL, password: LOGIN_PASSWORD }),
    { headers: { "Content-Type": "application/json" } },
  );
  if (res.status !== 200) {
    throw new Error(`setup login failed: ${res.status} ${res.body}`);
  }
  return { token: JSON.parse(res.body).data.token };
}

export default function (data) {
  if (SCENARIO === "login") {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ identifier: LOGIN_EMAIL, password: LOGIN_PASSWORD }),
      { headers: { "Content-Type": "application/json" }, tags: { name: "replica_login_burst" } },
    );
    if (res.status === 200) allowed.add(1);
    else if (res.status === 429) rejected.add(1);
    else unexpected.add(1);
    return;
  }

  // SCENARIO === "import"
  const file = http.file(csvBody(__VU), "statement.csv", "text/csv");
  const res = http.post(
    `${BASE_URL}/api/v1/import/csv/stage`,
    { file },
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { name: "replica_import_burst" } },
  );
  if (res.status === 200) allowed.add(1);
  else if (res.status === 503 || res.status === 429) rejected.add(1);
  else unexpected.add(1);
}

export function handleSummary(data) {
  const a = data.metrics.replica_experiment_allowed?.values?.count || 0;
  const r = data.metrics.replica_experiment_rejected?.values?.count || 0;
  const u = data.metrics.replica_experiment_unexpected_status?.values?.count || 0;
  const line =
    `\n=== D-4 replica experiment: scenario=${SCENARIO} burst=${BURST_SIZE} base_url=${BASE_URL} ===\n` +
    `allowed=${a}  rejected=${r}  unexpected=${u}\n`;
  return { stdout: line };
}
