# Secrets and IAM audit

**Date:** 2026-08-08
**Scope:** where every credential lives, who can reach it, and whether it is scoped and rotated.

This is a different question from [`deployment-guide.md`](../operations/deployment/deployment-guide.md)'s environment-variable
table, which answers *what to set*. This one asks *whether what is set is safe* — the half that no
amount of reading `application.yml` can settle.

**On method, because it decides how much each answer is worth.** Everything in §1 was verified
against the repository and the GitHub API and can be re-checked by anyone. Everything in §2 needs a
console this audit had no access to (Railway, Cloudflare, Firebase), so those are stated as questions
with the exact way to answer them, not as findings. A checklist that pretends inference is
verification is worse than one that says which is which.

---

## 1. Verified

### No credential is in the repository

Nine secrets exist. Every one is read from an environment variable, and none has a real value
committed anywhere:

`JWT_SECRET` · `DB_PASSWORD` · `RESEND_API_KEY` · `TWO_FACTOR_API_KEY` · `FINORA_SETUP_KEY` ·
`R2_ACCESS_KEY_ID` · `R2_SECRET_ACCESS_KEY` · `SENTRY_DSN` · `GOOGLE_APPLICATION_CREDENTIALS`

Scanned for AWS keys, GitHub/Slack tokens, `sk-`/`AIza` API keys, PEM private-key blocks and JWTs
across all tracked files. One hit, and it is a test asserting that a bearer token gets scrubbed
before reaching Sentry.

Two placeholder values *are* committed on purpose — the `JWT_SECRET` default in `application.yml` and
the one in `docker-compose.yml`. Both are rejected at startup under the `prod` profile by
`ProductionConfigValidator`, which checks a list of known placeholders *and* a marker-substring rule
so a third one cannot slip through by not being on the list.

### CI holds no credentials at all

| Store | Count |
|---|---|
| GitHub Actions secrets | **0** |
| Dependabot secrets | **0** |
| Environment secrets (all 4 environments) | **0** |

`ci.yml` contains no `secrets.*` reference. The only credential it touches is an RSA service-account
key it generates for the packaged-jar classpath check and throws away in the same step.

So there is nothing in GitHub to rotate, and no CI-shaped path to a production credential. That is a
genuinely good posture and worth not regressing: the moment a deploy step needs a token, it belongs
in an *environment* secret with a protection rule, not a repository-wide one.

`GITHUB_TOKEN` itself is `contents: read` at workflow level.

### There is no secret manager

Every production value is an environment variable in Railway (backend) or Cloudflare Pages
(frontends). No Vault, no Doppler, no cloud secret manager, and therefore:

- no rotation history, no versioning, and no audit trail of who read or changed a value;
- the blast radius of one compromised Railway account login is every backend secret at once.

Defensible at this size. It is the thing to revisit before the first person outside the core team
gets console access, not after.

---

## 2. Needs a console — findings and questions

### 2.1 A superseded Railway project probably still holds a full credential set — **check this first**

The repository is wired to **two** Railway projects, visible as four GitHub deployment environments:

| Project | Environments | Last deploy |
|---|---|---|
| `enchanting-caring` | Dev, production | **2026-08-03** |
| `Finora Tech` | Dev, Production | 2026-08-08 |

`enchanting-caring` is Railway's auto-generated project name. It stopped receiving deploys on
2026-08-03 — the same day `Finora Tech` was created. The obvious reading is that the first project
was set up, then replaced by a properly named one, and abandoned rather than deleted.

Why that matters: a Railway project keeps its environment variables whether or not it is deploying.
If that project was ever configured for production, it still holds a `JWT_SECRET`, a `DB_PASSWORD`,
a `RESEND_API_KEY` and a Firebase key — and, if its Postgres service still exists, possibly a
database with real rows in it. A signing key that still validates tokens is a live credential no
matter how long ago its service last deployed.

**RESOLVED 2026-08-08 — closed by the repository owner after a manual Railway console review.**
The project was checked and found clean; no credential exposure was identified. Recorded on the
owner's report, not independently verified here, because establishing it requires reading environment
variables in the Railway console and credential handling is deliberately outside what this audit does.

Reopen only on concrete evidence — a credential found in the repository, or an exposure identified in
a live environment. The rest of this section is kept as the original reasoning, and the caveat below
is worth keeping in mind if the project is ever revisited: if two projects share a `JWT_SECRET`,
deleting one does not retire the key, so a comparison must precede a deletion.

**Original question:** open Railway, and for `enchanting-caring` establish (a) whether any service is still
running, (b) whether a Postgres volume still exists and what is in it, and (c) which variables are
set. Then either delete the project or, if it is deliberately kept, record why here. If its
`JWT_SECRET` matches the live one, rotate the live one — two projects sharing a signing key means
deleting one does not retire the key.

*Confidence:* the two projects and their deploy dates are verified from the GitHub API. Everything
about what the old project *contains* is inference, and only Railway can confirm it.

### 2.2 Questions this audit could not answer

| Question | How to answer | Why it matters |
|---|---|---|
| Is the Cloudflare R2 token scoped to one bucket, or account-wide? | R2 → Manage API Tokens → check the token's permissions and bucket scope | `R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY` are the only credentials here that can reach *stored customer statements*. An account-scoped token turns a backend compromise into every bucket. |
| What roles does the Firebase service account hold? | GCP IAM → find the service account → review roles | The backend only needs to *verify* ID tokens. If it holds Firebase Admin or Editor, it can also mint tokens, read Firestore and change project config. |
| How old is the Firebase key, and has it ever been rotated? | GCP IAM → Keys tab → creation date | A service-account JSON key does not expire. Age is the only signal, and one that has never rotated is one nobody has practised rotating. |
| Is the Resend key send-only? | Resend → API Keys → check permission | A full-access key can also read the sending domain's configuration and mail logs. |
| Has *any* secret ever been rotated? | Each provider's console | If the answer is no across the board, the first rotation will be discovered under incident pressure. |
| Does anyone besides the owner have Railway/Cloudflare console access? | Each provider's members list | Determines whether "no secret manager" is currently acceptable. |

### 2.3 No rotation procedure exists

Nothing in the repository documents how to rotate any of these — which value to change, in what
order, and what breaks in between. `JWT_SECRET` is the sharp one: rotating it invalidates every
issued access *and* refresh token at once, signing every user out mid-session. That is survivable
and worth knowing **before** the rotation, not during it.

Worth writing as a short runbook next to this file. Not written here, because a rotation procedure
nobody has executed is a guess, and it should be produced by doing the first rotation.

---

## 3. What would change these answers

- **A second person gets console access** → the "no secret manager" trade stops holding; the lack of
  an audit trail becomes the problem rather than the missing tooling.
- **A deploy step needs a token in CI** → §1's "CI holds no credentials" stops being true. Use an
  environment secret with a protection rule, and add it to this file.
- **Object storage goes live** (`STATEMENT_STORAGE_PROVIDER=r2`) → the R2 token scope in §2.2 moves
  from a question to the highest-severity item here, because it becomes the credential guarding
  customer statement bytes.
- **The repository goes public** → every assumption in this document is re-opened, along with the
  self-hosted runner's (see [`../infrastructure/self-hosted-runner.md`](../architecture/infrastructure/self-hosted-runner.md)).
