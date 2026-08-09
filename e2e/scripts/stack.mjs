#!/usr/bin/env node
/**
 * Brings up the throwaway stack the e2e suite runs against.
 *
 * A Postgres of its own on 5433 and a backend of its own on 8081 — deliberately NOT the 5432/8080
 * pair a developer already has running. The milestone brief asks for a fresh database and a fresh
 * backend, and the reason is not tidiness: several of these tests assert on platform-wide counts
 * and on financial totals, and a database carrying yesterday's experiments makes those assertions
 * either wrong or meaningless. Sharing the developer's own stack would also mean a test run
 * silently rewrote data they were in the middle of looking at.
 *
 *   node scripts/stack.mjs up      start (or reuse) the database and backend
 *   node scripts/stack.mjs reset   destroy the database and start again, empty
 *   node scripts/stack.mjs down    stop everything
 *
 * The backend runs from the jar in `backend/target/`, so it is whatever was last
 * built. That is on purpose: a test run should exercise the code you built, and "did you rebuild"
 * is a question you want to be able to answer, not one a script hides from you.
 */
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
// Found by pattern, not pinned by name. The jar is named from backend/pom.xml's <version>, so a
// hardcoded filename means every release has to bump a string here, in e2e/README.md and in
// .github/workflows/ci.yml -- and whichever one gets missed fails with "Unable to access jarfile",
// which says nothing about versions and sends you looking at the wrong thing.
// The `.jar$` anchor matters: `mvn package` also leaves a `finora-backend-<version>.jar.original`
// next to it, which is the pre-repackage artifact and will not boot.
const TARGET_DIR = resolve(REPO, 'backend', 'target');
const JAR = resolve(
  TARGET_DIR,
  (existsSync(TARGET_DIR) ? readdirSync(TARGET_DIR) : []).find((f) =>
    /^finora-backend-.*\.jar$/.test(f),
  ) ?? 'finora-backend.jar',
);

const CONTAINER = 'finora-e2e-db';
const DB_PORT = process.env.FINORA_E2E_DB_PORT ?? '5433';
const API_PORT = process.env.FINORA_E2E_API_PORT ?? '8081';
const HEALTH = `http://localhost:${API_PORT}/actuator/health`;

const run = (cmd, args, opts = {}) => spawnSync(cmd, args, { encoding: 'utf-8', ...opts });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function containerState() {
  const { stdout } = run('docker', ['ps', '-a', '--filter', `name=${CONTAINER}`, '--format', '{{.State}}']);
  return (stdout ?? '').trim();
}

async function waitFor(label, check, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  process.stdout.write(`  waiting for ${label}`);
  while (Date.now() < deadline) {
    if (await check()) {
      process.stdout.write(' — ready\n');
      return true;
    }
    process.stdout.write('.');
    await sleep(1000);
  }
  process.stdout.write(' — TIMED OUT\n');
  return false;
}

async function backendUp() {
  try {
    const res = await fetch(HEALTH);
    return res.ok && (await res.json()).status === 'UP';
  } catch {
    return false;
  }
}

function startDatabase() {
  const state = containerState();
  if (state === 'running') {
    console.log(`  database: reusing ${CONTAINER} on ${DB_PORT}`);
    return;
  }
  if (state) run('docker', ['rm', '-f', CONTAINER]);

  console.log(`  database: starting ${CONTAINER} on ${DB_PORT}`);
  const result = run('docker', [
    'run', '-d', '--name', CONTAINER,
    '-e', 'POSTGRES_DB=finora', '-e', 'POSTGRES_USER=finora', '-e', 'POSTGRES_PASSWORD=finora',
    '-p', `${DB_PORT}:5432`, 'postgres:16-alpine',
  ]);
  if (result.status !== 0) {
    // Windows reserves scattered high port ranges for Hyper-V, and the failure reads as a
    // permissions error rather than a conflict. Worth naming, because the obvious reading is wrong.
    throw new Error(
      `Could not start ${CONTAINER} on port ${DB_PORT}:\n${result.stderr}\n` +
        `If this says "socket ... forbidden by its access permissions", the port is inside a\n` +
        `Windows-reserved range. Pick another: FINORA_E2E_DB_PORT=15433 npm run stack:up`
    );
  }
}

async function startBackend() {
  if (await backendUp()) {
    console.log(`  backend: already healthy on ${API_PORT}`);
    return;
  }
  if (!existsSync(JAR)) {
    throw new Error(
      `No backend jar at ${JAR}.\nBuild it first:  cd backend && ./mvnw -DskipTests package`
    );
  }

  console.log(`  backend: starting on ${API_PORT} against the test database`);
  const child = spawn('java', ['-jar', JAR], {
    cwd: resolve(REPO, 'backend'),
    detached: true,
    stdio: 'ignore',
    env: {
      ...process.env,
      SPRING_PROFILES_ACTIVE: 'dev',
      SERVER_PORT: API_PORT,
      DB_PORT,
      // The suite registers an account per test (isolation) and stages a statement in most of
      // them, so the production per-IP ceilings -- 5 registrations / 5 min, 10 stages / 10 min --
      // stop the run partway through every time, regardless of whether the product works. Raised
      // here and nowhere else: these are the app's defence against credential stuffing, spam
      // registration and unbounded import_sessions growth, and this is a throwaway stack on
      // localhost. See the BH-050 note below on the one that is
      // deliberately left alone.
      RATE_LIMIT_REGISTER_MAX: '10000',
      RATE_LIMIT_LOGIN_MAX: '10000',
      RATE_LIMIT_IMPORT_STAGE_MAX: '10000',
      // BH-050: forgot-password is deliberately NOT raised, and must stay that way. The
      // negative phase asserts that rate limiting is actually ENFORCED, and it needs one
      // endpoint whose ceiling it can reach -- otherwise its assertion is unreachable and
      // the test proves nothing. That is precisely what had happened: this script raised
      // all six while ci.yml raised three, the test hammered /auth/login 40 times against a
      // ceiling of 10000, and it had never once asserted anything in either environment.
      // Nothing else in the suite spends this budget.
      RATE_LIMIT_PASSWORD_CHANGE_MAX: '10000',
      RATE_LIMIT_RESET_PASSWORD_MAX: '10000',
    },
  });
  child.unref();
}

async function up() {
  console.log('Bringing up the e2e stack:');
  startDatabase();

  const dbReady = await waitFor('database', async () =>
    run('docker', ['exec', CONTAINER, 'pg_isready', '-U', 'finora', '-d', 'finora']).status === 0
  );
  if (!dbReady) throw new Error('The database never became ready.');

  await startBackend();
  // Flyway runs 60+ migrations against an empty schema on first boot, so the first `up` is
  // meaningfully slower than later ones.
  const apiReady = await waitFor('backend (migrations run on first boot)', backendUp);
  if (!apiReady) {
    throw new Error(
      `The backend never reported healthy at ${HEALTH}.\n` +
        `It runs detached, so check for a port clash on ${API_PORT} or a migration failure by\n` +
        `starting it in the foreground:\n` +
        `  cd backend && SERVER_PORT=${API_PORT} DB_PORT=${DB_PORT} java -jar target/finora-backend-*.jar`
    );
  }

  console.log(`\nReady.  API ${HEALTH.replace('/actuator/health', '')}   DB localhost:${DB_PORT}\n`);
}

function down() {
  console.log('Stopping the e2e stack:');
  run('docker', ['rm', '-f', CONTAINER]);
  console.log(`  database: removed`);
  console.log(
    `  backend: left running on ${API_PORT} — it was started detached, so stop it yourself if you\n` +
      `           want the port back. It is harmless once its database is gone.`
  );
}

async function reset() {
  console.log('Resetting to an empty database:');
  run('docker', ['rm', '-f', CONTAINER]);
  console.log('  database: destroyed');
  console.log(
    '  backend: must be restarted too — it holds a connection pool to the database that no\n' +
      '           longer exists, and Flyway only runs at boot. Stop it, then run stack:up.'
  );
}

const command = process.argv[2] ?? 'up';
const actions = { up, down, reset };
if (!actions[command]) {
  console.error(`Unknown command "${command}". Use: up | down | reset`);
  process.exit(1);
}
Promise.resolve(actions[command]()).catch((err) => {
  console.error(`\n${err.message}\n`);
  process.exit(1);
});
