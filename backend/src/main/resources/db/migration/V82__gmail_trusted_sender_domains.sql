-- The trusted sender registry -- Phase C3 of docs/proposals/gmail-transaction-sync-proposal.md §12.2.
--
-- ORDERED BEFORE MESSAGE FETCHING, DELIBERATELY
-- ---------------------------------------------
-- Nothing in Finora can read a mailbox yet: C2 added a profile read and stopped there. The gate
-- lands first so there is never a build in which Finora CAN fetch messages but cannot yet decide
-- which senders it will parse. Building the fetcher first would create exactly that window.
--
-- WHAT THIS DEFENDS AGAINST
-- -------------------------
-- A parser must never trust the From: header. Anyone can send mail claiming to be Amazon, and an
-- attacker who knows a target uses Finora could send a fabricated "order confirmation" for a large
-- amount to that person's connected mailbox. Even routed through review (which every Gmail-sourced
-- row is), that is a fabricated financial record entering a finance product's pipeline, and a user
-- bulk-approving a queue may well not catch it.
--
-- Two independent conditions, and BOTH are required before a message is parsed:
--   1. the message passed DKIM/SPF/DMARC -- established by Gmail, read from Authentication-Results
--   2. the AUTHENTICATED domain appears in this table
--
-- Either alone is insufficient. Authentication proves a message really came from the domain it
-- claims; it says nothing about whether that domain is one Finora should read receipts from --
-- anyone can DKIM-sign mail from a domain they own. The registry answers the second question.

CREATE TABLE gmail_trusted_sender_domains (
    id UUID PRIMARY KEY,

    -- Stored lower-case, matched EXACTLY. Never a pattern, never a suffix match: a suffix rule for
    -- "amazon.in" would also accept "amazon.in.attacker.example", which is a domain an attacker can
    -- register today. Subdomains that genuinely send receipts get their own row.
    domain VARCHAR(253) NOT NULL,

    -- Which merchant this domain belongs to. Display and grouping only -- never used for matching,
    -- so a wrong label here cannot widen what is trusted.
    merchant_name VARCHAR(120) NOT NULL,

    -- ACTIVE   -- messages from this domain may be parsed
    -- DISABLED -- kept for the audit trail, treated exactly as if absent
    --
    -- Disabling rather than deleting matters: "when did we stop trusting this domain, and who
    -- decided that" is the question asked after an incident, and a deleted row cannot answer it.
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',

    -- Who added it. Adding a row here grants parse-trust to a new sender, which makes it a
    -- security-relevant action rather than routine configuration -- see the admin-only guard and
    -- the audit entries on the management endpoints.
    added_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per domain, regardless of status. Prevents a DISABLED row and an ACTIVE row for the same
-- domain existing together, which would make "is this trusted?" depend on which one a query
-- happened to read first.
CREATE UNIQUE INDEX uq_gmail_trusted_sender_domain ON gmail_trusted_sender_domains (domain);

-- The lookup the gate performs per message: exact domain, active only.
CREATE INDEX idx_gmail_trusted_sender_active ON gmail_trusted_sender_domains (domain, status);


-- The initial merchant set from the design proposal (§5, §8). Seeded rather than left empty so the
-- gate is not vacuously closed on first deploy -- an empty registry rejects everything, which is
-- safe but indistinguishable from a broken one.
--
-- India-first, matching the proposal's initial parser set. Deliberately narrow: every entry here is
-- a domain Finora will read financial detail from, so the list earns additions one at a time rather
-- than by pattern.
INSERT INTO gmail_trusted_sender_domains (id, domain, merchant_name, status) VALUES
    (gen_random_uuid(), 'amazon.in',      'Amazon',       'ACTIVE'),
    (gen_random_uuid(), 'myntra.com',     'Myntra',       'ACTIVE'),
    (gen_random_uuid(), 'uber.com',       'Uber',         'ACTIVE'),
    (gen_random_uuid(), 'olacabs.com',    'Ola',          'ACTIVE'),
    (gen_random_uuid(), 'zomato.com',     'Zomato',       'ACTIVE'),
    (gen_random_uuid(), 'booking.com',    'Booking.com',  'ACTIVE');
