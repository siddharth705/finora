/**
 * A key identifying ONE logical write attempt, resent unchanged on any retry of that attempt.
 *
 * Two server-side protections exist for this and, until now, **neither had a client sending a
 * key**, so both were inert:
 *
 * - `V97__transaction_idempotency_key.sql` — `POST /transactions`. Written after a security review
 *   found a double-click or a retried request creates two rows AND moves the account balance twice.
 * - `V133__reimport_confirmation_claim.sql` — the re-import confirm, which otherwise posts a whole
 *   statement's transactions a second time.
 *
 * The key identifies the *attempt*, never the content. That distinction is the whole reason V97
 * uses a client-supplied key rather than hashing the transaction's fields: two identical coffees on
 * the same day are a completely ordinary, legitimate pair, and a content hash would silently reject
 * the second real one.
 *
 * Mirrors `mobile/src/lib/idempotencyKey.ts`. The two cannot share a module (separate builds), so
 * they are kept deliberately identical in shape and reasoning — a divergence here would mean one
 * client protected and the other not, which is exactly the state this fixes.
 *
 * Not crypto-random on purpose: the value is not a secret and grants nothing. It is scoped to one
 * user server-side, and the worst case for a collision is that user seeing a spurious "already
 * submitted" — visible and recoverable. What it needs is to not repeat within a user, which a
 * millisecond timestamp plus 10 random base-36 characters comfortably satisfies.
 */
export function newIdempotencyKey(): string {
  const random = Math.random().toString(36).slice(2, 12);
  return `${Date.now().toString(36)}-${random}`;
}
