import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { API_BASE } from './config';
import { query } from './db';

/**
 * Takes a fresh platform through first-run setup, once, using the product's own flow.
 *
 * A genuinely empty database has never been set up, so `GET /setup/status` reports
 * `setupRequired: true` and the admin portal shows the installation wizard instead of a login form.
 * Every admin test would then time out against a screen it was not looking for — which is exactly
 * what happened the first time this suite ran, and is a fair thing for "fresh database" to mean.
 *
 * This drives the real path rather than inserting an admin row: sign in as the BOOTSTRAP_ADMIN
 * account with the installation key, POST the first real admin, and let `SetupService` retire the
 * key and suspend the bootstrap account exactly as it does in production. Faking it would leave the
 * suite unable to notice if that flow ever broke — and it is the one flow that, if it breaks, makes
 * a new deployment unusable with no way in.
 *
 * The one thing done afterwards in SQL is `phone_verified`, for the same reason as everywhere else:
 * the admin created by setup does not inherit the bootstrap account's verified flag, and
 * `FirebaseConfig` returns null without credentials, so verification is impossible locally. That is
 * Issue 01 in E2E_TEST_REPORT.md — a real product gap this fixture works around rather than hides.
 */

// Playwright transpiles these to CommonJS, so `import.meta.url` is unavailable and `__dirname` is
// what there is. Resolved from this file rather than from cwd so the path holds regardless of where
// the runner was invoked.
const KEY_FILE = resolve(__dirname, '..', '..', 'backend', '.finora', 'installation.key');

const SETUP_ADMIN = {
  email: 'platform.owner@finora.test',
  password: 'E2eSetupPass2026',
  fullName: 'Platform Owner',
  phoneNumber: '+919876500000',
};

async function status(): Promise<{ setupRequired: boolean; installationKeyAvailable: boolean }> {
  const response = await fetch(`${API_BASE}/setup/status`);
  const body = await response.json();
  return body.data;
}

/** The key is written to disk as a human-readable block, so it has to be picked out of prose
 *  rather than read whole. Matching on shape (a long unpadded base64-ish token on its own line)
 *  rather than on the surrounding wording, which is explanatory text and liable to be reworded. */
function readInstallationKey(): string {
  if (!existsSync(KEY_FILE)) {
    throw new Error(
      `No installation key at ${KEY_FILE}.\n` +
        `The backend writes one on first boot against an empty database. If setup has already been\n` +
        `completed it is deleted, which is correct — but then setup/status should not be asking for\n` +
        `one. Reset the stack: npm run stack:reset && npm run stack:up`
    );
  }
  const contents = readFileSync(KEY_FILE, 'utf-8');
  const match = contents.split('\n').map((l) => l.trim()).find((l) => /^[A-Za-z0-9_-]{20,}$/.test(l));
  if (!match) throw new Error(`Could not find a key in ${KEY_FILE}:\n${contents}`);
  return match;
}

export async function completeSetupIfRequired(): Promise<void> {
  const current = await status();
  if (!current.setupRequired) return;

  const key = readInstallationKey();

  const login = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifier: 'BOOTSTRAP_ADMIN', password: key, scope: 'ADMIN' }),
  });
  const loginBody = await login.json();
  if (!loginBody?.success) {
    throw new Error(
      `Could not sign in as BOOTSTRAP_ADMIN with the installation key: ` +
        `${loginBody?.errorCode} ${loginBody?.message}`
    );
  }

  const complete = await fetch(`${API_BASE}/setup/complete`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${loginBody.data.token}`,
    },
    body: JSON.stringify(SETUP_ADMIN),
  });
  const completeBody = await complete.json();
  if (!completeBody?.success) {
    throw new Error(`Setup failed: ${completeBody?.errorCode} ${completeBody?.message}`);
  }

  // Standing in for Firebase, and only for Firebase. Without it the platform owner exists but
  // cannot pass PhoneVerificationFilter, which is the one-way door Issue 01 describes.
  await query(`update users set phone_verified = true where email = $1`, [SETUP_ADMIN.email]);

  const after = await status();
  if (after.setupRequired) {
    throw new Error('Setup reported success but the platform still says it needs setting up.');
  }
}
