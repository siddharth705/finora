Hi Team,

Excellent work on completing the remaining Financial Intelligence Engine tasks. 🎉

We've now finished all planned implementation items for this phase, including:

* ✅ Merchant Management UI
* ✅ Rules & Relationships UI
* ✅ Confirm Category & Undo APIs
* ✅ All Rule Action implementations
* ✅ Merchant Analytics
* ✅ Testcontainers integration tests
* ✅ Bug fixes around stale category propagation and test coverage

With these completed, I consider the Financial Intelligence Engine v1 feature complete.

## One architectural issue we should address before closing this phase

During implementation, we identified an architectural inconsistency around subscription detection.

Currently, `MARK_SUBSCRIPTION` rules are evaluated correctly, but the `Transaction.recurring` flag only gets updated when `RecurringService` runs, and that service is currently triggered when the Recurring page is opened.

This creates an undesirable dependency between the UI and backend state.

Current flow:

```text
Transaction Imported
        ↓
Rule Engine marks as Subscription
        ↓
Transaction Saved
        ↓
Recurring flag remains unchanged
        ↓
User opens Recurring page
        ↓
RecurringService executes
        ↓
Recurring flag finally updates
```

This means the same transaction can have different states depending on whether a user has visited a particular page, which violates our goal of keeping backend data deterministic and self-consistent.

## Proposed solution

Recurring detection should become part of the backend processing pipeline instead of being triggered from the UI.

A better flow would be:

```text
Transaction Import/Create
        ↓
Merchant Resolution
        ↓
Rule Engine
        ↓
Learning Engine
        ↓
Recurring Detection
        ↓
Persistence
```

Alternatively, we could publish a domain event (e.g., `TransactionSaved`) and let `RecurringService` recompute recurring status asynchronously.

This ensures:

* No UI dependency for backend state.
* `Transaction.recurring` is always accurate.
* Rule-driven subscription detection becomes immediately effective.
* Consistent behavior across imports, manual transactions, and API calls.

I recommend we address this before declaring the Financial Intelligence Engine fully closed. Once this is resolved, we can freeze v1 and begin the next major phase: Financial Evidence, which will introduce the evidence model, reconciliation with external sources (such as Gmail receipts in the future), and eventually the Financial Knowledge Graph.

Great work, everyone! 🚀
