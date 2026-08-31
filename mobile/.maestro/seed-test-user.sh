#!/usr/bin/env bash
set -euo pipefail

# Creates (or re-verifies) the one fixed account the Login/Dashboard/Import Maestro flows sign in
# as. Maestro flows are static YAML -- unlike e2e/fixtures/accounts.ts, which mints a fresh random
# account per Playwright test, there is no scripting hook to generate a fresh email/password and
# feed it into a .yaml file at run time. So this account is deterministic and reused across runs,
# and every flow that would mutate shared state (the Import flow) is expected to leave the account
# in a state the next run can still exercise -- see flows/import.yaml's own comment on that.
#
# Bypasses phone verification the same way e2e/fixtures/accounts.ts does, and for the identical
# reason (see that file's own doc comment): FirebaseConfig returns null without real credentials,
# so POST /phone/verify cannot succeed locally or in CI, and this is the one step a fixture stands
# in for. Registration itself goes through the real POST /auth/register -- password hashing, phone
# normalisation, the default category set -- so only the one step the product genuinely cannot do
# here is done in SQL.

API_ORIGIN="${MAESTRO_API_ORIGIN:-http://localhost:18090}"
DB_URL="${MAESTRO_DB_URL:-postgresql://finora:finora@localhost:5434/finora}"
EMAIL="${MAESTRO_TEST_EMAIL:-maestro-test@finora.test}"
PASSWORD="${MAESTRO_TEST_PASSWORD:-MaestroSeedPass2026}"
FULL_NAME="Maestro Test"
# 987 prefix matches e2e/fixtures/accounts.ts's syntheticPhone() -- keeps this recognisable as a
# fixture to scripts/check-fixture-hygiene.sh's same convention, and to a human reading the DB.
PHONE="+919876500001"

echo "Seeding Maestro test account: ${EMAIL}"

# Every Finora API response is a 200 envelope ({success, message, data, errorCode}), win or lose --
# see mobile/src/api/client.ts. So the outcome is read from the body, the same way
# e2e/fixtures/accounts.ts's own `post()` helper does, not from the HTTP status.
register_body=$(curl -sS -X POST "${API_ORIGIN}/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"fullName\":\"${FULL_NAME}\",\"phoneNumber\":\"${PHONE}\"}")

# success=true the first time a given CI/local database is used; a CONFLICT errorCode on every
# rerun against a database that already has this account (a long-lived local Postgres, or a CI job
# re-running against a volume that wasn't reset). Both are fine -- either way the row exists by the
# time the UPDATE below runs. Anything else means registration itself is broken, which the seed
# step should surface rather than paper over.
outcome=$(python3 -c "
import json, sys
body = json.loads(sys.argv[1])
if body.get('success'):
    print('ok')
elif body.get('errorCode') == 'CONFLICT':
    print('conflict')
else:
    print(f\"FAIL: {body.get('errorCode')} {body.get('message')}\", file=sys.stderr)
    sys.exit(1)
" "${register_body}")

echo "Registration: ${outcome}"

psql "${DB_URL}" -v ON_ERROR_STOP=1 -c \
  "update users set phone_verified = true where email = '${EMAIL}' and account_scope = 'USER';"

echo "Maestro test account ready: ${EMAIL} / phone_verified=true"
