// Load-testing baseline for Finora, against a local docker-compose stack.
//
// Scope is deliberately narrow (see docs/project-management/plans/project-plan-v1.0.md §5a):
// measure reality at three fixed concurrency tiers, don't chase a scale target. Each VU logs in
// as one of the 100 seeded load-test users (scripts/load-test/seed.py) and repeats a weighted mix
// of the traffic a real user actually generates -- dashboard, transaction listing, accounts, and
// occasionally a CSV import -- so the numbers reflect the app's real query shape, not a synthetic
// ping.
//
// Run via scripts/load-test/run.sh <vus> <duration>, not directly -- that script also samples
// container memory and Postgres connection usage for the duration of the run.

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const USER_COUNT = 100;
const PASSWORD = "LoadTest123!";

export const options = {
  scenarios: {
    main: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.TEST_DURATION || "60s",
      gracefulStop: "10s",
    },
  },
  // No hard thresholds that abort the run -- the point is to observe where it breaks, not to
  // pass/fail against a target that doesn't exist yet.
};

const loginTrend = new Trend("finora_login_ms");
const dashboardTrend = new Trend("finora_dashboard_ms");
const transactionsTrend = new Trend("finora_transactions_ms");
const accountsTrend = new Trend("finora_accounts_ms");
const importStageTrend = new Trend("finora_import_stage_ms");
const importConfirmTrend = new Trend("finora_import_confirm_ms");
const importRejected = new Counter("finora_import_rejected"); // 429/503 from ImportConcurrencyLimiter or RateLimitFilter
const httpErrors = new Counter("finora_http_errors");

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } };
}

function login(userIndex) {
  const email = `loadtest${userIndex}@loadtest.local`;
  const res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ identifier: email, password: PASSWORD }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "login" } },
  );
  loginTrend.add(res.timings.duration);
  const ok = check(res, { "login 200": (r) => r.status === 200 });
  if (!ok) {
    httpErrors.add(1);
    return null;
  }
  return JSON.parse(res.body).data.token;
}

function csvBody(seq) {
  // Distinct date per call so this doesn't collapse entirely into duplicate rows across VUs --
  // duplicates are handled (marked, not blocked), but a distinct date keeps the parse/dedupe path
  // representative of a real statement rather than a pathological all-duplicates case.
  const day = (seq % 28) + 1;
  return (
    "Date,Description,Amount,Type\n" +
    `2026-0${(seq % 9) + 1}-${String(day).padStart(2, "0")},LOAD TEST COFFEE,150.00,DEBIT\n` +
    `2026-0${(seq % 9) + 1}-${String(day).padStart(2, "0")},LOAD TEST SALARY,50000.00,CREDIT\n`
  );
}

export function setup() {
  // Resolve one account id per seeded user up front so the per-iteration import path doesn't
  // spend a request on GET /accounts every time -- that call is already measured separately in
  // the main mix.
  const accountIds = {};
  for (let i = 0; i < USER_COUNT; i++) {
    const token = login(i);
    if (!token) continue;
    const res = http.get(`${BASE_URL}/api/v1/accounts`, authHeaders(token));
    if (res.status === 200) {
      const accounts = JSON.parse(res.body).data;
      if (accounts && accounts.length > 0) accountIds[i] = accounts[0].id;
    }
  }
  return { accountIds };
}

// Cached per VU (module scope persists across iterations within the same VU in k6), so a
// 15-minute access token isn't re-minted -- via BCrypt, deliberately slow -- on every iteration.
// A real session logs in once and reuses the token; measuring otherwise would make login's
// intentional cost dominate every other number in the mix.
const tokenByVu = {};

export default function (data) {
  const userIndex = __VU % USER_COUNT;
  if (!tokenByVu[__VU]) {
    tokenByVu[__VU] = login(userIndex);
  }
  const token = tokenByVu[__VU];
  if (!token) {
    sleep(1);
    return;
  }
  const headers = authHeaders(token);

  // MODE overrides the weighted mix with a single traffic type, for the bottleneck investigation
  // (Q1: which endpoint holds connections longest; Q3: does import starve other endpoints) --
  // isolating one endpoint at a time makes hikaricp_connections_usage_seconds attributable,
  // which the mixed-traffic tiers in the main baseline can't provide on their own.
  const mode = __ENV.MODE || "mixed";

  // Weighted mix approximating real usage: dashboard and transaction list are the two screens a
  // user actually sits on; accounts is a quick glance; import is occasional, not constant.
  const roll = mode === "dashboard" ? 0 : mode === "transactions" ? 0.5 : mode === "accounts" ? 0.8 : mode === "import" ? 0.95 : Math.random();

  if (roll < 0.40) {
    const res = http.get(`${BASE_URL}/api/v1/dashboard/summary`, { ...headers, tags: { name: "dashboard" } });
    dashboardTrend.add(res.timings.duration);
    if (res.status !== 200) httpErrors.add(1);
  } else if (roll < 0.75) {
    const page = Math.floor(Math.random() * 10);
    const res = http.get(
      `${BASE_URL}/api/v1/transactions?page=${page}&size=20&sortField=date&sortDir=desc`,
      { ...headers, tags: { name: "transactions" } },
    );
    transactionsTrend.add(res.timings.duration);
    if (res.status !== 200) httpErrors.add(1);
  } else if (roll < 0.90) {
    const res = http.get(`${BASE_URL}/api/v1/accounts`, { ...headers, tags: { name: "accounts" } });
    accountsTrend.add(res.timings.duration);
    if (res.status !== 200) httpErrors.add(1);
  } else {
    // Import: stage, then confirm using the accountId resolved in setup().
    const seq = __ITER;
    const file = http.file(csvBody(seq), "statement.csv", "text/csv");
    const stageRes = http.post(
      `${BASE_URL}/api/v1/import/csv/stage`,
      { file },
      { headers: { Authorization: `Bearer ${token}` }, tags: { name: "import_stage" } },
    );
    importStageTrend.add(stageRes.timings.duration);

    if (stageRes.status === 429 || stageRes.status === 503) {
      importRejected.add(1);
    } else if (stageRes.status === 200) {
      const staged = JSON.parse(stageRes.body).data;
      const rows = (staged.staging.rows || []).map((r) => ({
        date: r.date,
        description: r.description,
        amount: r.amount,
        type: r.type,
        category: r.suggestedCategory,
        include: true,
        categorySource: r.categorySource,
        ruleId: r.ruleId,
        likelyDuplicate: r.likelyDuplicate,
        referenceNumber: r.referenceNumber,
        balanceAfter: r.balanceAfter,
        confirmedNotDuplicate: false,
      }));
      const accountId = data.accountIds[userIndex];
      if (accountId && rows.length > 0) {
        const confirmRes = http.post(
          `${BASE_URL}/api/v1/import/csv/confirm`,
          JSON.stringify({ sessionId: staged.sessionId, rows, existingAccountId: accountId }),
          { headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, tags: { name: "import_confirm" } },
        );
        importConfirmTrend.add(confirmRes.timings.duration);
        if (confirmRes.status !== 200) httpErrors.add(1);
      }
    } else {
      httpErrors.add(1);
    }
  }

  sleep(Math.random() * 2 + 1); // 1-3s think time, roughly human
}
