-- Credit card statement entity, roadmap item 6 follow-up (PR #451 shipped totalAmountDue/
-- paymentDueDate, which already survived staging with no new plumbing; the granular balance
-- breakdown below does not -- CreditCardSummaryEvidence is discarded after staging today, so this
-- needs a real staging-to-confirm carrier, unlike #451's two columns).
ALTER TABLE import_sessions ADD COLUMN credit_card_summary_json TEXT;

ALTER TABLE statement_imports ADD COLUMN previous_balance NUMERIC(14,2);
ALTER TABLE statement_imports ADD COLUMN purchases NUMERIC(14,2);
ALTER TABLE statement_imports ADD COLUMN cash_advances NUMERIC(14,2);
ALTER TABLE statement_imports ADD COLUMN fees NUMERIC(14,2);
ALTER TABLE statement_imports ADD COLUMN payments_and_credits NUMERIC(14,2);
