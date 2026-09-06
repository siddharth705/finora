-- Subscription billing V2 (docs/superpowers/plans/2026-09-05-subscription-billing-v2-upgrade-downgrade-admin.md).
-- Nullable schema prep for tax (GST) and invoice references, requested during product review before
-- any GST or invoice-download feature is actually built. No writer populates these yet -- adding the
-- columns now avoids a breaking migration once one is needed. "amount" on payments keeps its
-- existing meaning (the total actually charged); base_amount/tax_amount are an optional breakdown
-- of that same total, not a replacement for it.

ALTER TABLE payments ADD COLUMN base_amount NUMERIC(10, 2);
ALTER TABLE payments ADD COLUMN tax_amount NUMERIC(10, 2);
ALTER TABLE payments ADD COLUMN invoice_id VARCHAR(50);
ALTER TABLE payments ADD COLUMN invoice_url VARCHAR(500);

-- Percentage (e.g. 18.00 for 18%), not an amount -- billing_prices.price stays GST-exclusive until
-- a real tax calculation exists; this column only records the rate that would apply, for whenever
-- that calculation is built.
ALTER TABLE billing_prices ADD COLUMN gst_rate NUMERIC(5, 2);
