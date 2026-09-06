/**
 * A key identifying ONE logical write attempt, resent unchanged on any retry of that attempt.
 *
 * Used by the re-import confirm (Track B/B1): the server records it and refuses a second confirm
 * carrying the same key, which is what stops a double-tapped or retried "Re-import" from posting a
 * statement's transactions twice. See `V133__reimport_confirmation_claim.sql`.
 *
 * **Deliberately not a crypto-random UUID.** This value is not a secret and grants nothing —
 * it is scoped to one user server-side, and the worst case for a collision is that user seeing a
 * spurious "already confirmed" on a later re-import, which is visible and recoverable. What it
 * actually needs is to not repeat within a user, which a millisecond timestamp plus 10 random
 * base-36 characters comfortably satisfies.
 *
 * The alternative was worse: `uuid` is in package.json but unused, and v11 needs a
 * `crypto.getRandomValues` polyfill (`react-native-get-random-values`) that this app does not
 * install — importing it would either throw at runtime or add a dependency to generate a value
 * that does not need to be unguessable.
 */
export function newIdempotencyKey(): string {
  const random = Math.random().toString(36).slice(2, 12);
  return `${Date.now().toString(36)}-${random}`;
}
