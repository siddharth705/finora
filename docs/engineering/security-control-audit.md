# Security control audit: what exists, and how completely

**Purpose.** Nine controls were spotted incidentally during the PII sanitization sweeps. That list
was observation, not an audit, and it was at risk of being read as coverage. This states each one's
status with the evidence, and says *Not verified* where the evidence is a count rather than a proof.

**The rule this applies.** "The class exists" is not evidence that "the control is complete."
`PhoneMasking` existing says nothing about whether every log site uses it.

| Control | Status | Evidence |
|---|---|---|
| Log masking (`PhoneMasking`, `EmailMasking`) | **Partial** | Both exist with tests. Referenced in 8 main-source files, but only **3 are log statements** (`TwoFactorSmsProvider` ×2, `NoOpSmsProvider` ×1); the rest use them for DTO display. Nothing prevents a new unmasked log. |
| Sentry scrubbing | **Implemented, completeness not verified** | `observability/SentryScrubber.java`, 14 scrubbed keys. A fixed key list cannot cover a field it does not know; no test asserts an unknown-field default. |
| Rate limiting | **Not verified** | `config/RateLimitFilter.java` matches request paths at runtime, but **no endpoint literal appears in the file** — patterns come from configuration not read here. `importStageLimiter` is referenced from `StatementUpload`, so upload *is* bounded. Which of `/login`, `/register`, `/forgot-password`, `/export` are covered is unestablished. |
| Upload validation | **Partial, and self-documented** | `StatementUpload`'s own javadoc states: *"no emptiness check, no content-type check, no extension check, no magic-byte sniff."* What does exist: `max-file-size` 10 MB, `MAX_FILE_NAME_LENGTH` 120, `importStageLimiter`, and storage never trusting the client filename. No page-count cap, no decompression-bomb guard, no parser timeout verified. |
| Encrypted / malformed PDF handling | **Implemented** | `PasswordProtectedPdfTest`, 8 tests, green in the 1,745-test baseline. |
| Malware scanning | **Missing** | Zero matches for clamav / virustotal / malware / antivirus across `backend/src`. Uploads reach the parser unscanned. |
| Tenant isolation tests | **Partial, not verified** | 19 test files reference other-user identifiers (`otherUser`, `user2`, `userB`). A file count is not proof the assertions are *denials* — the horizontal-privilege matrix (A→B account, transaction, PDF, report, export) is not shown to be covered. |
| Startup config validation | **Implemented** | `ProductionConfigValidator`, 10 assertion sites, and it now runs before the web server binds (`SmartInitializingSingleton`, guarded by rule FG-031 in `StartupConfigValidationLifecycleTest`). |
| Web security headers | **Missing at the edge** | `frontend/public/_headers` sets only `Cache-Control`. No CSP, HSTS, `X-Content-Type-Options` or `Referrer-Policy`. Whether Spring Security sets equivalents at the API layer is **not verified**. |

## The three findings worth acting on first

1. **Malware scanning is absent**, and uploads are the largest untrusted-input surface. Concrete, not a judgement call.
2. **Security headers are absent from `_headers`** — CSP and HSTS on a financial web app are cheap and currently missing at the CDN edge.
3. **Masking has no enforcement.** Three log sites are masked because they were fixed by hand. A fourth added tomorrow would not be. The utilities are the easy half; a guard asserting no log statement interpolates a raw email, phone or account number is the half that makes it a control.

## What this audit does not claim

It does not say the repository is insecure, and it does not say the *Implemented* rows are sufficient.
Two rows are marked *Not verified* precisely because a count was the only evidence available, and
promoting either without reading the configuration or the assertions would repeat the mistake this
document exists to correct.

---

## Accepted as the current baseline (2026-08-08)

Accepted by the repository owner. **The classifications above do not change except on new
evidence** — not on a plausible argument that a control is probably fine.

The four-state vocabulary is the load-bearing part, and it is the rule this document exists to
enforce:

| State | What it requires |
|---|---|
| **Implemented** | evidence **and** a test |
| **Partial** | the exact gap, named |
| **Not verified** | what evidence would settle it |
| **Missing** | implementation required |

> A control is not Implemented because a utility, a class, or a test file exists.

**And a value is not synthetic because a scanner accepted it.** The same error one level down, and
it was made during this work. Two real IFSCs in a `BankRegistry` javadoc were classified as confirmed
placeholders, on the reasoning that their conspicuous runs of zeros would satisfy `is_placeholder()`.
They did not: the IFSC rule requires six *identical* characters at positions 6–11, and those branch
parts each carried a differing final digit. Both had passed CI for months only because they sat on a
pre-existing line no diff had touched — so the green history was evidence about the diff-based
scanners' reach, not about the values. The pre-commit hook caught them the moment that line was
edited; both were sanitized in `b3fc79c`.

The values are not reproduced here, and that is not squeamishness: an earlier draft of this very
paragraph quoted both, and the hook blocked the commit. Explaining a leak does not license repeating
it.

Two things follow. A predicate's acceptance is evidence about the predicate, not about the value's
origin — only the corpus comparison establishes origin. And the classification of a value must cite
the source evidence, never the scanner's verdict on it.

## Prioritised work, in order, not in parallel

**Before any of it:** finish the repository PII sanitization against the 1,745-test / 134-affected
baseline. No parser behaviour changes as part of that cleanup.

**And nothing from the document-ingestion track inside the cleanup branch** — no OCR, no parser
improvement, no ground-truth work. Both are wanted, and mixing them here destroys the one property
that makes this cleanup verifiable: if the suite moves, the cause must be unambiguously the
sanitization. A fixture edit and a parser change landing together means neither can be cleared. The
sequence is: security cleanup → green baseline → merge → document intelligence.

**P0 — upload security.** Empty-file, content-type, extension and magic-byte validation in
`StatementUpload`; malformed-PDF, decompression-bomb and page-count protection; parser timeouts;
investigate malware scanning. This is the largest untrusted-input surface and the only row in the
table that is both Missing and directly reachable by an anonymous upload.

**P1 — access and data isolation.** Tenant isolation proven by explicit **positive and negative**
authorization tests; rate limiting verified per endpoint, especially auth and the expensive
import/OCR paths; **enforcement** around masking rather than reliance on a developer remembering to
call it.

**P1 — edge and API security.** Check whether Spring Security already supplies the missing headers
*before* implementing duplicates at the CDN, then add CSP / HSTS / `X-Content-Type-Options` /
`Referrer-Policy` at whichever layer is correct.

## Why document integrity belongs in this document

**Rule, and it is release-blocking rather than advisory.** *Never classify a financial document as
successfully processed merely because the parser produced output. Success requires evidence that the financial entities, transactions, ownership and
totals extracted are consistent enough to trust.*

A parser that silently turns **Savings + RD + FD** into **Savings only** is not a parsing bug with a
UX consequence. It writes incorrect financial state into a financial system, under a success label —
and a wrong balance a user acts on is an integrity failure, not a cosmetic one. That is the same
category as an access-control failure, and it is why `PARSED_COMPLETE`, ground truth and per-section
verification sit alongside masking and rate limiting here rather than in a separate quality backlog.

See [ADR-004](../architecture/adr-004-document-pipeline-scope.md) §3: partial data under a success
label is the one categorically unacceptable outcome, because refusal is visible and silent
misattribution is not.
