# Audit: The Unused `RelationshipType` Enum Values

**Question:** `TransactionRelationship.RelationshipType` defines `EMI`, `SALARY`,
`LOAN_REPAYMENT`, `INVESTMENT_TRANSFER`, `CASH_WITHDRAWAL`, `CASH_DEPOSIT` alongside `TRANSFER`,
`REFUND`, `REVERSAL`, `DUPLICATE`, `CC_PAYMENT` — but no pass in `ReconciliationService` ever
constructs an edge of the first six types. Which of three explanations is it: dead code, a hidden
roadmap, or a UI dependency that could break?

**Verdict: none of the three, exactly as framed — it's a fourth, better answer. This is a
documented, deliberate, already-decided roadmap deferral, not a mystery.**

---

## 1. Where the answer already lives

`docs/proposals/reconciliation-evolution-roadmap-proposal.md` (this project's own reconciliation
design doc) states this explicitly, twice:

> **Phase 2 (transaction graph & confidence engine):** "Ships with all ten relationship types
> *defined* in the enum (free — it's a schema value), but real detection logic is built for only
> four: `TRANSFER`, `REFUND`, `REVERSAL`, `DUPLICATE`... `CC_PAYMENT` follows in Phase 3;
> `EMI`/`SALARY`/`LOAN_REPAYMENT`/`INVESTMENT_TRANSFER`/`CASH_WITHDRAWAL`/`CASH_DEPOSIT` stay
> enum-only, no matching service, until Phase 4 gives a reason to build one."
>
> **Phase 4 (Account Aggregator, "only once reconciliation is trustworthy," explicitly quarter+ out):**
> "EMI / salary / loan / investment / cash-movement detection — Real matching logic for the six
> relationship types that shipped enum-only in Phase 2 — **built once actual usage data (not a
> guess) shows which of the six users actually need first.**"

This is the identical evidence-first discipline this whole benchmarking exercise has been
following (measure before building) — the project had already committed to it for this exact
question, independently, before this audit asked it.

## 2. Confirming this against the actual code (not just the doc)

- **Backend:** `grep`ing all of `backend/src` for any reference to
  `RelationshipType.EMI`/`SALARY`/`LOAN_REPAYMENT`/`CASH_WITHDRAWAL`/`CASH_DEPOSIT` outside the enum
  declaration itself returns **zero results**. No pass, no query, no test constructs one.
- **`INVESTMENT_TRANSFER` is a partial exception, and worth calling out precisely:** the
  investment-transfer pass (`ReconciliationService`, pass 2b) *does* have real, working business
  logic — it excludes Groww/Zerodha/SIP/NPS outflows from cash flow via `reconciliationStatus =
  INVESTMENT_TRANSFER` — but its own code comment explains it deliberately does NOT write a graph
  edge of that type, because "a transaction_relationships edge needs a real Transaction on both
  ends... and the user never imports the broker's own statement." So of the six "enum-only" types,
  `INVESTMENT_TRANSFER` already delivers its practical value through a different, working mechanism
  (a status flag, not a graph edge) — the other five (`EMI`, `SALARY`, `LOAN_REPAYMENT`,
  `CASH_WITHDRAWAL`, `CASH_DEPOSIT`) have no mechanism behind them at all today.

## 3. The admin-portal's TypeScript type — checked directly, not assumed

`admin-portal/src/types/index.ts`'s `ReconciliationExplorerEdge.relationshipType` is a union of all
eleven enum values, including the five unbuilt ones. This looked, before checking, like it could be
Case 3 (a UI that assumes these exist and might break). **Checked directly against the component
that actually renders it (`ReconciliationExplorer.tsx`): it is not.** The component renders
`edge.relationshipType` as a plain string label with no per-type branching, icon, or color logic —
`{edge.relationshipType}` and `label={e.relationshipType}` are the only two uses. The type union is
a complete, accurate transcription of the backend enum for type-safety, not evidence of built,
type-specific frontend behavior for the five unused values. **Nothing breaks, and nothing is
silently wrong** — the five values simply never appear in a real API response, and the component
would render them correctly (as their own label text) if they ever did.

## 4. Recommendation

**No action.** This is not dead code to clean up (the enum values are cheap, already-decided
schema, and removing them would just be churn against a documented near-term roadmap item) and not
a live bug (confirmed above — no rendering path depends on these values existing). It is a
correctly-parked, correctly-evidenced future phase, gated on exactly the kind of usage-data
question this benchmark exercise has been modeling throughout: don't build ahead of evidence. If
anything, this audit's main output is **validation** that the project's own stated practice here
matches its actual code — which is itself worth knowing, separately from any reconciliation-accuracy
finding.
