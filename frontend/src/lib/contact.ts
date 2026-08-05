/**
 * Single source of truth for Finora's support address, so a future domain migration is a one-line
 * change instead of a repo-wide grep — the last one (finora.app -> finoratech.info) missed six
 * hardcoded copies of this same string. See docs/engineering/repository-audit-findings.md.
 */
export const SUPPORT_EMAIL = 'support@finoratech.info'; // synthetic-ok: Finora's own support mailbox, not customer PII
export const SUPPORT_MAILTO = `mailto:${SUPPORT_EMAIL}`;
