# Financial Document Intelligence — Changelog

Not a Git changelog — a record of what the **engine learned**, in the vocabulary this project
uses to talk about that (see
[financial-document-intelligence-principles.md](financial-document-intelligence-principles.md)'s
"Capability lifecycle"). A Git log tells you which files changed; this tells you which real-world
document shapes the engine now understands, which it deliberately doesn't yet, and why. Each
version corresponds to one Evidence Cycle (see the
[Evidence Registry](evidence-registry.md)'s per-document entries and metrics snapshot for that
cycle's full detail) — this file is the compressed, skimmable summary of the same underlying work.

**Categories**, consistently:
- **Learned** — a new label, phrasing, or structural shape the engine now recognizes at all.
- **Improved** — an existing capability's coverage widened (still the same capability, more
  layouts).
- **Protected** — a regression guard added, either fixing a bug or explicitly preventing a future
  fix from silently producing a *wrong* value instead of an honest null.
- **Observed** — a real structural pattern noticed but not yet triaged into a backlog entry —
  genuinely too early to say more than "this exists."
- **Deferred** — evaluated and consciously not built yet, with a specific reason on record (see
  the Capability Backlog table in the principles doc for the full reasoning behind each).
- **Open** — actively tracked as the next thing to work on — either high-evidence backlog items or
  a root-caused investigation awaiting its fix.

---

## Version 1 — 2026-08-02 (Evidence Cycle 1)

### Learned
- ✓ `Customer Name` as an account-holder label synonym.
- ✓ Bare `Name` (no colon) as an account-holder label synonym.
- ✓ `Chq/Ref. No.` as a reference-number column header variant.
- ✓ A metadata grid shape where each row is `<value> <label>` — reversed from the ordinary
  `<label> <value>` line.
- ✓ The account holder's name appearing as a plain, unlabeled line within the first few lines of
  a document, with no label anywhere identifying it as such.
- ✓ Transaction narration wrapping across multiple lines both before and after the date-bearing
  row, including for the same transaction.

### Improved
- ✓ Credit Limit extraction — now accepts whole-rupee amounts with no decimal places, and no
  longer silently fails on a multi-column header line that also happens to satisfy the ordinary
  same-line label pattern.
- ✓ The Payment Due Date / Credit Limit multi-column grid fallback — label matching widened from
  "the label ends the line" to "the label appears anywhere on the line," to handle grids with more
  than one labeled column per row.
- ✓ The unlabeled-leading-name-line fallback — widened from "must be the document's literal first
  line" to a bounded search window, with a generic-title-word denylist so a document's own banner
  text isn't mistaken for a person's name.

### Protected
- ✓ `"**** End of Statement ****"` boilerplate no longer merges into the preceding real
  transaction's description.
- ✓ A scrambled, multi-row credit-summary grid is guarded against a *wrong* value: a naive fix was
  verified to silently produce ₹200 instead of the real ₹78,000, so the field intentionally stays
  null, with a regression test locking that in.
- ✓ Concurrent 401 responses from an expired session no longer trigger duplicate token-refresh
  calls — previously able to look like session theft to the backend and revoke every session for
  the affected user.
- ✓ Real customer data (names, reference numbers, account/card numbers, an IFSC code, balances)
  that had been copied verbatim into committed fixtures and tests — some from before this cycle —
  was found during this cycle's own hygiene pass and replaced with fully synthetic, structurally
  equivalent values. Formalized going forward as the Synthetic Fixture Policy (see the principles
  doc), with a pre-commit warning check added as a backstop.
- ✓ An optimistic-locking conflict during a concurrent edit now returns a proper error response
  instead of a generic server error.

### Observed
- A composite multi-account statement's account-holder name, branch, and IFSC are all absent from
  any pattern the engine currently recognizes — noticed during this cycle's validation pass, not
  yet root-caused, not yet a backlog entry.
- A glued, non-standard currency-symbol artifact in place of the Rupee symbol was re-examined and
  confirmed to already be handled by existing normalization — correcting an earlier hypothesis
  that it was a new gap.

### Deferred
- A composite account-holder line shape: a value, its own trailing label, then a second,
  unlabeled value for an unrelated field, all on one line.
- A scrambled/split multi-row credit-summary grid, where a label spans non-adjacent lines and an
  unrelated row's values sit between a label and its own value.
- A reference number embedded inside free-text transaction narration rather than appearing in its
  own column.

### Open
- Account number recognition — absent on 6 of 7 real statements reviewed this cycle; the existing
  label-based patterns don't cover the real phrasings observed (embedded mid-sentence, an
  unrelated trailing label, a masked card number never labeled "Account Number" at all).
- A real credit-card statement's transaction table never being recognized as "ended" — trailing,
  unrelated content (a rewards table, a GST entry table, a links table, a signature block) gets
  bucketed as more transaction rows. Root-caused this cycle; fix intentionally not yet attempted
  (see the Open Investigation in the principles doc).
