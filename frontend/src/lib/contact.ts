/**
 * Single source of truth for Finora's outward-facing mailboxes, so a future domain migration is a
 * one-line change instead of a repo-wide grep — the last one (finora.app -> finoratech.info) missed
 * six hardcoded copies of this same string. See docs/engineering/repository-audit-findings.md.
 *
 * Centralising the address is only half the fix: nothing stopped the seventh copy from being typed
 * inline, and one duly survived here in Careers until after the support addresses were centralised.
 * scripts/check-contact-addresses.py is the other half — it fails the build on a hardcoded Finora
 * mailbox anywhere under src/, so the next migration cannot silently miss one again.
 */
export const SUPPORT_EMAIL = 'support@fynora.net'; // synthetic-ok: Finora's own support mailbox, not customer PII
export const SUPPORT_MAILTO = `mailto:${SUPPORT_EMAIL}`;

/**
 * Careers is a separate mailbox rather than an alias of SUPPORT_EMAIL: applications and support
 * requests go to different people, and collapsing them would route CVs into the support queue.
 */
export const CAREERS_EMAIL = 'careers@fynora.net'; // synthetic-ok: Finora's own careers mailbox, not customer PII
export const CAREERS_MAILTO = `mailto:${CAREERS_EMAIL}`;
