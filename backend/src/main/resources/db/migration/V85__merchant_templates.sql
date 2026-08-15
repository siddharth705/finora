-- Phase C5.2. The experiment the design review asked for: does a declarative pattern, edited as a
-- row rather than a deployment, hold up against a real merchant's receipts as well as a
-- hand-written parser does? Uber is the test case; Ola (C5.2's other merchant) stays hand-written
-- as AmazonEmailParser's direct comparison.
--
-- Deliberately narrow. This is not the "Merchant Intelligence Platform" proposal's Layer 2 --
-- there is no admin UI, no field beyond amount/date, no versioning, no AI fallback. It is the
-- smallest thing that can produce a real answer to "is this worth building further": one row per
-- merchant, one pattern per field, edited by hand (an INSERT/UPDATE, same as this migration) until
-- there is evidence templating is worth an admin surface.
CREATE TABLE merchant_templates (
    id UUID PRIMARY KEY,

    -- Matches TrustedSenderDomain.domain -- routing reuses the same authenticated-domain signal
    -- every hand-written parser routes on (MerchantEmailParser's own doc comment: never content).
    merchant_domain VARCHAR(253) NOT NULL,
    merchant_name VARCHAR(120) NOT NULL,

    -- Literal substring (not a regex) that must appear before this template is even attempted --
    -- AmazonEmailParser's "Order #" check, generalized. Kept as plain text specifically so editing
    -- it later needs no regex literacy, matching the whole point of this experiment.
    receipt_marker VARCHAR(255) NOT NULL,

    -- Exactly one {amount} / {date} placeholder each, everything else literal text matched
    -- verbatim (regex-escaped by the compiler, never interpreted). See
    -- MerchantTemplate.compileAmountPattern/compileDatePattern for the compiler itself.
    amount_pattern VARCHAR(255) NOT NULL,
    date_pattern VARCHAR(255) NOT NULL,

    enabled BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One template per merchant, mirroring TrustedSenderDomain's own one-row-per-domain shape.
    CONSTRAINT uq_merchant_template_domain UNIQUE (merchant_domain)
);

CREATE INDEX idx_merchant_templates_domain_enabled ON merchant_templates (merchant_domain) WHERE enabled;

-- Seeded from a real Uber trip-receipt shape (genericized): "Trip Fare" section, a rupee total, a
-- trip date line. See UberTemplateParserTest / the gmail/uber fixtures for the exact shapes this
-- was built and verified against.
INSERT INTO merchant_templates (id, merchant_domain, merchant_name, receipt_marker, amount_pattern, date_pattern, enabled)
VALUES (
    gen_random_uuid(),
    'uber.com',
    'Uber',
    'Trip Fare',
    'Total: Rs. {amount}',
    'Trip Date: {date}',
    true
);
