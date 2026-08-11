# Fino — V2 Readiness Contract

Fino is a post-v1.0 initiative. **v1.0 does not include AI functionality.** v1 engineering may
introduce only foundational capabilities that are independently valuable to Finora on their own
merits and that happen to prepare the system for controlled AI access in v2. See
`docs/project-management/plans/project-plan-v1.0.md` §8a for the parked product proposal this
readiness plan supports.

## Acceptance criterion

> Fino Readiness means that every important financial fact can be obtained through a
> permission-aware backend service, calculated by Finora's domain logic rather than an AI model,
> returned in a structured form, traced to its source, and audited where appropriate. The same
> foundation must support both user-facing Fino and Admin Fino.

Every item below must be justifiable as valuable **even if Fino were cancelled tomorrow**. If a task
only makes sense because Fino will eventually exist, it belongs in V2, not here.

## Target architecture (unchanged from §8a)

```text
Financial Domain
       ↓
Financial Analytics
       ↓
Controlled Tool/API Layer
       ↓
       Fino
       ↓
User Fino / Admin Fino
```

## Sequencing

### NOW — v1.0 / GA (opportunistic only, no dedicated schedule slot, no date impact)

Only done when a bug-hunt fix or release-gate task is *already* touching the relevant code. Never a
scheduled task in its own right; never allowed to move the dates in plan §9.

- Extract reusable financial calculations toward a shared `FinancialAnalyticsService` when touching
  `DashboardService` or report code, instead of re-deriving numbers locally.
- Clean service boundaries (e.g. account/transaction/analytics capabilities as normal domain
  services) when refactoring code already in scope for a fix.
- Improve financial data consistency (clear `EXPENSE`/`INCOME`/`TRANSFER`/`REFUND`/`FEE`/`INVESTMENT`
  typing) when fixing transaction-model bugs that already touch this.
- Standardize analytics response DTOs when adding or fixing an analytics endpoint already in scope.
- Improve admin operational metrics when already building admin-facing fixes.
- Keep user/admin authorization boundaries clean whenever touching auth code — this one is close to
  release-gate hygiene regardless of Fino.

None of these are tracked as separate plan-doc line items with estimates; they ride inside existing
bug-hunt/release-gate work or don't happen yet.

### V1.0.1 — post-GA hardening (dedicated work, scheduled after GA)

- Financial calculation provenance (metric/period/source/filters/generated_at on important results).
- Financial event/audit trail (`STATEMENT_UPLOADED`, `IMPORT_FAILED`, `TRANSACTION_CATEGORIZED`, etc.).
- Admin analytics expansion (platform overview, import health, categorization, reconciliation,
  system health).
- Machine-readable error taxonomy (`code`, `message`, `retryable` instead of bare 500s).
- Formal Fino capability contract — naming the domain services above as the future tool surface,
  without building the tool-calling framework itself.

### V2 — Fino

- OpenAI integration (Responses API, official Java SDK), Fino backend service, tool/function calling.
- User Fino UI, conversation memory/schema.
- Financial read tools → comparative/trend analysis → proactive insights.
- Admin Fino, admin AI analytics.
- Controlled actions with explicit confirm-before-execute (budget creation, categorization correction).
- AI-specific audit trail.

### V2.1 (further out, unscoped)

- Proactive insights, anomaly detection, budget recommendations, goal planning, controlled actions
  beyond confirmation-gated basics.

## Explicitly not now

OpenAI integration, chat UI, conversation database, vector database, prompt management, agent
framework, AI actions, fine-tuning. All V2. None of this enters the bug-hunt/release-gate phase.
