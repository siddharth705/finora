# Incident: customer PII committed to Git history

**Date identified:** 2026-08-03
**Status:** Contained in current code. **Remediation decision pending — owner: Siddharth.**
**Severity:** To be confirmed by the exposure assessment below (§3). Provisionally Medium; becomes
High if the repository is public or has been cloned outside the core team.

This is recorded as a security incident rather than as an engineering to-do. Customer personal data
reaching a version-controlled repository is a data-handling failure with a decision trail worth
keeping, regardless of how small the blast radius turns out to be. The point of this document is
that the decision — including a decision to accept the risk — is written down with its reasoning.

---

## 1. What happened

A real customer's name and their 14-digit bank account number were committed inside a Java source
comment in `PdfTableLocator.java`. The comment documented what a real Bank of Baroda statement's
repeated page banner looked like, quoting the banner verbatim:

```
// printed at the top of EVERY page ("<HOLDER NAME> SAVINGS ACCOUNT  - <14 digits>"),
```

(shown here in its redacted form)

The data was **not** in a fixture, a test resource, or a database — it was in main source, which is
why it went unnoticed.

## 2. Timeline

| When | What |
|---|---|
| commit `6a188da` | Introduced, while documenting a real-document bug fix. |
| — | `scripts/check-fixture-hygiene.sh` did not scan it: its target filter was `test/`, `fixtures/`, `resources/`, `*Test.java` and `*.md`. A main source file matched none of those. |
| commit `2231b2f` (attempt) | The same number was copied *out* of that comment into a new synthetic fixture. The hook fired on the copy, blocking the commit. |
| commit `74a3d76` | Comment redacted to describe the shape (`<HOLDER NAME>`, `<14 digits>`) rather than the values. Hygiene scan widened from location-based to extension-based, so every `.java`/`.ts`/`.sql`/`.md` file is now scanned. Verified to fire by staging a main-source file containing an email and an IFSC. |
| 2026-08-03 | This record created. |

## 3. Exposure assessment — **to be completed before deciding**

The remediation decision depends on these, and none should be assumed:

- [ ] **Repository visibility.** Private or public? (`gh repo view --json visibility`, or the GitHub
      UI.) A public repo at any point in this window changes the severity and the response.
- [ ] **Collaborator count**, and whether any are outside the core team.
- [ ] **Clone/fork evidence.** Forks, and whether CI, code-scanning, or third-party integrations
      have mirrored the history.
- [ ] **Was the affected window ever public** — a repo made private after being public still leaves
      the earlier commits cached by forks and, potentially, by GitHub's own fork network.

## 4. Remediation options

| Option | Effect | Cost |
|---|---|---|
| **Accept** | Current code is clean. History retains the values. | Anyone with repo access can recover them. Defensible only for a private repo with a small, trusted collaborator set — and only if that is written down. |
| **Rewrite history** | `git filter-repo` removes the string from all commits; force-push. | Every commit hash from `6a188da` onward changes. Every existing clone must be re-cloned. Open branches (including the `claude/*` worktrees) must be recreated. Coordination cost across a multi-developer team. |
| **Rotate** | Not applicable — an account number is not a credential and cannot be rotated. | The data is the customer's; the only mitigations are removal and access control. |

**If the repository is or ever was public**, treat rewriting as the default and consider whether the
affected customer needs notifying under DPDP Act 2023 — a question for whoever owns that
obligation, not one to settle here.

## 5. Decision

> **Pending.** To be filled in by the owner with the option chosen and the reasoning, so the team
> has an audit trail either way. An explicit, reasoned "accept" is a valid outcome and is materially
> different from having never decided.

**Decision:**
**Decided by:**
**Date:**
**Rationale:**

## 6. Preventive measures already taken

- Hygiene scan is **extension-based, not location-based** (`74a3d76`) — main source, migrations and
  docs are now scanned, not just tests and fixtures. Verified to actually block.
- Trace capture now **validates before writing** and refuses on unmasked PII, rather than relying on
  the capturer reading the file (see [`trace-lifecycle.md`](../trace-lifecycle.md)).
- `TraceCorpusHealthTest` fails the build on unmasked PII in any committed trace.

## 7. What this says about the control that failed

The hygiene hook was scoped by *directory*, which encodes an assumption that customer data only
arrives through fixtures. It doesn't. It arrives wherever someone is documenting what a real
document looked like — which is disproportionately main source, because that is where the
explanatory comments live.

The wider lesson, and the reason the redaction workflow was rebuilt alongside this: **"the author
will check before committing" is not a control.** It was the stated control in `PdfTraceRedactor`'s
own doc comment ("the person capturing the trace is the last reviewer standing between a customer's
statement and the repository") and it failed here in both directions — PII got in, and separately,
evidence got silently stripped out of three traces without anyone noticing. Both are now automated
checks.
