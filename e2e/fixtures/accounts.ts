import { query, one } from './db';
import { API_BASE } from './config';

/**
 * Creates the accounts the suite needs, on a database that starts empty.
 *
 * Registration goes through the **real** `POST /auth/register` — password hashing, phone
 * normalisation, scope uniqueness, the default category set every new user gets, all of it. Only
 * two things are done in SQL afterwards, and both are things the product genuinely cannot do here:
 *
 *  1. `phone_verified = true`. Firebase's client SDK sends the OTP and `FirebaseConfig` returns
 *     null without credentials, so `POST /phone/verify` cannot succeed locally at all. This is the
 *     one step a fixture stands in for, and it stands in for exactly it.
 *  2. Granting an admin role. Setup is a one-way door — it suspends the only pre-verified account
 *     in the same transaction that promotes the new one — so there is no sequence of API calls that
 *     leaves a usable admin behind. See E2E_TEST_REPORT.md Issue 01.
 *
 * Everything a test is actually about is then done through the product.
 *
 * **A user per test, not a shared fixture.** Tests run in parallel and this milestone's state is
 * per-user by design (merchants, learning, duplicates all scope to a user). Sharing one account
 * would make every duplicate-detection test depend on what its neighbours had already imported —
 * and would quietly turn Phase 10's isolation checks into tautologies.
 */

export interface TestUser {
  id: string;
  email: string;
  password: string;
  fullName: string;
  phone: string;
  token: string;
}

const PASSWORD = 'E2eSeedPass2026';

/**
 * A phone number that is obviously synthetic and, in practice, unique.
 *
 * Uniqueness matters more than it looks. Phone numbers are unique per account scope, so a collision
 * fails registration with CONFLICT partway through a run — which reads as a product defect and is
 * not one. And the test database persists between runs, so anything derived from pid or a counter
 * eventually collides with a number an earlier run already claimed.
 *
 * So: a wide random space, and a retry when it collides anyway. Retrying is the honest answer to a
 * probabilistic generator; widening the space alone only makes the failure rarer and therefore
 * harder to recognise when it happens.
 *
 * The 987 prefix keeps it recognisable as a fixture to the repo's own hygiene hook.
 */
function syntheticPhone(): string {
  return `+91987${String(Math.floor(Math.random() * 100_000_000)).padStart(8, '0')}`;
}

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}@finora.test`;
}

async function post(path: string, body: unknown, token?: string) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json().catch(() => null);
  return { status: response.status, payload } as {
    status: number;
    payload: { success: boolean; message: string; errorCode: string | null; data: Record<string, unknown> | null } | null;
  };
}

/** Signs in and returns a fresh access token. Used after seeding, and again by any test that needs
 *  to arrange state over the API rather than through the UI. */
export async function login(email: string, password = PASSWORD, scope: 'USER' | 'ADMIN' = 'USER') {
  const { payload } = await post('/auth/login', { identifier: email, password, scope });
  if (!payload?.success) {
    throw new Error(`Seed login failed for ${email}: ${payload?.errorCode} ${payload?.message}`);
  }
  return payload.data!.token as string;
}

/**
 * Registers through the real endpoint, retrying only a phone-number collision.
 *
 * Deliberately narrow: any other failure throws immediately with the server's own error code,
 * because a seeding helper that retries indiscriminately turns a real product regression into a
 * slow timeout with no explanation.
 */
async function register(email: string, fullName: string): Promise<string> {
  let last = '';
  for (let attempt = 0; attempt < 5; attempt++) {
    const phone = syntheticPhone();
    const { payload } = await post('/auth/register', { email, password: PASSWORD, fullName, phoneNumber: phone });
    if (payload?.success) return phone;

    last = `${payload?.errorCode} ${payload?.message}`;
    const phoneTaken = payload?.errorCode === 'CONFLICT' && /mobile number/i.test(payload?.message ?? '');
    if (!phoneTaken) break;
  }
  throw new Error(`Seed registration failed for ${email}: ${last}`);
}

/** A verified customer with an empty ledger. */
export async function createUser(prefix = 'user'): Promise<TestUser> {
  const email = uniqueEmail(prefix);
  const fullName = 'Eve Tester';
  const phone = await register(email, fullName);

  // Standing in for Firebase, and only for Firebase.
  await query(`update users set phone_verified = true where email = $1 and account_scope = 'USER'`, [email]);

  const row = await one<{ id: string }>(
    `select id from users where email = $1 and account_scope = 'USER'`, [email]
  );
  if (!row) throw new Error(`Seeded user ${email} was not found after registration`);

  return { id: row.id, email, password: PASSWORD, fullName, phone, token: await login(email) };
}

/**
 * An operator who can reach the Learning Queue and the Merchant Review Center.
 *
 * The role grant is by name rather than by inserting permissions directly, so the account gets
 * whatever SUPER_ADMIN actually carries in this database — including V61/V63/V64's per-permission
 * grants. A fixture that hand-picked permissions would keep passing after someone changed what the
 * role means, which is the opposite of what an admin-authorization test is for.
 */
export async function createAdmin(prefix = 'admin'): Promise<TestUser> {
  const email = uniqueEmail(prefix);
  const fullName = 'Ada Operator';
  const phone = await register(email, fullName);

  await query(
    `update users
        set phone_verified = true, account_scope = 'ADMIN', role = 'SUPER_ADMIN', status = 'ACTIVE'
      where email = $1`,
    [email]
  );
  await query(
    `insert into user_roles (user_id, role_id)
     select u.id, r.id from users u, roles r
      where u.email = $1 and r.name = 'SUPER_ADMIN'
     on conflict do nothing`,
    [email]
  );

  const row = await one<{ id: string }>(`select id from users where email = $1`, [email]);
  if (!row) throw new Error(`Seeded admin ${email} was not found after registration`);

  return { id: row.id, email, password: PASSWORD, fullName, phone, token: await login(email, PASSWORD, 'ADMIN') };
}

/** A user with no admin role at all — for asserting that an admin surface refuses, rather than
 *  assuming it does because nobody tried. */
export async function createPlainUserToken(): Promise<string> {
  const user = await createUser('unprivileged');
  return user.token;
}
