-- D-28 PR4-B. Billing history scaffolding per proposal §3.3/§3.4 -- payments schema only, no
-- payment gateway selected yet (§10). Nothing populates this table until a gateway is chosen, so
-- the user-facing billing history view (GET /api/v1/billing/history) is empty by construction
-- until then -- that's the "empty-state UI" half of this sub-PR's scope, not a bug.
--
-- Not soft-deletable, no @Version: a payment is a financial record, same "write it once, never
-- let it look edited" posture as audit_logs (BaseEntity's own class comment lists AuditLog as one
-- of the entities that doesn't extend it, for the same reason). subscription_id is nullable and
-- carries no ON DELETE behavior of its own: unlike subscription_events/plan_changes (which have no
-- user_id column and rely entirely on their parent's cascade), payments has its own user_id and is
-- hard-deleted directly by AccountPurgeSweepService, ordered before the subscriptions delete in
-- the same purge -- see PaymentRepository.hardDeleteByUserId's own comment.
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    subscription_id UUID REFERENCES subscriptions(id),
    amount NUMERIC(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    provider VARCHAR(30),
    provider_transaction_id VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_user_id ON payments(user_id);
