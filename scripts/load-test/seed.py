#!/usr/bin/env python3
"""Seeds load-test users and realistic transaction data against a local docker-compose stack.

Registers N users through the real /api/v1/auth/register endpoint (so passwords are hashed
by the app's own BCryptPasswordEncoder, not guessed at), then flips phone_verified directly
in Postgres -- PhoneVerificationFilter blocks every other endpoint otherwise, and there is no
dev-mode bypass for it (see the architecture-audit finding this script exists to unblock).
Accounts and transactions are inserted set-based in one SQL script rather than looped in
Python, because 100 users x 300 transactions as 30,000 individual INSERTs would dominate the
seed time and this data's shape (not its insert path) is what the load test needs to be
realistic.

Idempotent: safe to re-run. Existing users 409, existing accounts/transactions are left alone
by a NOT EXISTS guard on the user's account count.

Requires: local docker-compose stack up (`docker compose up -d`), backend healthy on :8080.
Stdlib only, matching the convention in scripts/requirements.txt.
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"
USER_COUNT = 100
TXNS_PER_USER = 300
PASSWORD = "LoadTest123!"
EMAIL_DOMAIN = "loadtest.local"


def register(i: int) -> str:
    email = f"loadtest{i}@{EMAIL_DOMAIN}"
    body = json.dumps({
        "email": email,
        "password": PASSWORD,
        "fullName": "Load Test User",
        "phoneNumber": f"+9190000{i:05d}",
    }).encode()
    req = urllib.request.Request(
        f"{BASE_URL}/api/v1/auth/register",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        urllib.request.urlopen(req, timeout=10)
        return "created"
    except urllib.error.HTTPError as e:
        if e.code == 409:
            return "exists"
        raise RuntimeError(f"register {email} failed: {e.code} {e.read().decode()[:200]}")


def run_sql(sql: str) -> None:
    result = subprocess.run(
        ["docker", "exec", "-i", "finora-postgres-1", "psql", "-U", "finora", "-d", "finora", "-v", "ON_ERROR_STOP=1"],
        input=sql.encode(),
        capture_output=True,
    )
    if result.returncode != 0:
        sys.exit(f"psql failed:\n{result.stderr.decode()}")
    print(result.stdout.decode().strip())


def main() -> None:
    print(f"Registering {USER_COUNT} load-test users...")
    created = exists = 0
    for i in range(USER_COUNT):
        outcome = register(i)
        created += outcome == "created"
        exists += outcome == "exists"
    print(f"  {created} created, {exists} already existed")

    print("Verifying phones, seeding accounts + transactions (set-based, one script)...")
    run_sql(f"""
UPDATE users SET phone_verified = true
WHERE email LIKE 'loadtest%@{EMAIL_DOMAIN}' AND phone_verified = false;

WITH target_users AS (
    SELECT id FROM users
    WHERE email LIKE 'loadtest%@{EMAIL_DOMAIN}'
      AND id NOT IN (SELECT DISTINCT user_id FROM accounts)
),
new_accounts AS (
    INSERT INTO accounts (id, user_id, name, account_type, balance)
    SELECT gen_random_uuid(), id, 'Primary Savings', 'SAVINGS', 50000 + (random() * 100000)::numeric(14,2)
    FROM target_users
    RETURNING id, user_id
)
INSERT INTO transactions (id, user_id, account_id, txn_date, description, merchant, amount, txn_type, source)
SELECT
    gen_random_uuid(),
    na.user_id,
    na.id,
    (CURRENT_DATE - ((n * 3) || ' days')::interval)::date,
    'Load test transaction ' || n,
    'Merchant ' || (n % 25),
    (random() * 2000 + 10)::numeric(14,2),
    CASE WHEN n % 5 = 0 THEN 'INCOME' ELSE 'EXPENSE' END,
    'MANUAL'
FROM new_accounts na, generate_series(1, {TXNS_PER_USER}) AS n;
""")
    print("Seed complete.")
    print(f"Users: loadtest0@{EMAIL_DOMAIN} .. loadtest{USER_COUNT - 1}@{EMAIL_DOMAIN}, password: {PASSWORD}")


if __name__ == "__main__":
    main()
