-- Phase 3's other half: FD/RD were already routed to the Investments module (V49), but with no
-- way to show what makes a deposit a deposit -- a customer looking at their FD saw a name and a
-- balance, indistinguishable from a savings account sitting in the same module.
--
-- All nullable and populated only for the product types they apply to. A fixed deposit has no
-- installment_amount; a recurring deposit has no principal_amount, since its value builds up over
-- the schedule rather than starting as a lump sum -- see
-- com.finora.imports.product.ProductAttributes for the full reasoning.
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS principal_amount NUMERIC(14,2);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS interest_rate NUMERIC(6,2);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS maturity_date DATE;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS maturity_amount NUMERIC(14,2);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS installment_amount NUMERIC(14,2);
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS installments_paid INTEGER;
ALTER TABLE accounts ADD COLUMN IF NOT EXISTS installments_total INTEGER;
