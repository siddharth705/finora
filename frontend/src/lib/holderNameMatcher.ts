/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1: does a statement's extracted
 * holder name plausibly belong to the logged-in user's profile? A client-side port of
 * `HolderNameMatcher` (backend/src/main/java/com/finora/imports/ownership/HolderNameMatcher.java)
 * -- same token-set algorithm, same reasoning, kept in sync deliberately rather than shared,
 * since this side only ever gates whether to show the review dialog before confirming; the
 * backend's own comparison (via `OwnershipMatchService`) is what actually gets persisted and is
 * the source of truth. A rare disagreement between the two (e.g. this import turns out to resolve
 * to an existing account, which only the backend knows at confirm time) means at worst one
 * unnecessary dialog on an otherwise-legitimate import -- never a missed warning becoming a
 * silent block, since neither side ever blocks anything.
 *
 * Deliberately does not split the extracted name into separate candidate holders before matching
 * -- a joint statement's shared surname must stay associated with the first name it belongs to.
 * See the Java version's own doc comment for the worked example.
 */

const CONNECTOR_WORDS = new Set(['AND', 'OR']);

export function isLikelyMatch(extractedHolderName: string | null | undefined, profileName: string | null | undefined): boolean {
  const extractedTokens = tokensOf(extractedHolderName);
  const profileTokens = tokensOf(profileName);
  if (extractedTokens.size === 0 || profileTokens.size === 0) return false;

  for (const profileToken of profileTokens) {
    const satisfied = [...extractedTokens].some((t) => tokenMatches(profileToken, t));
    if (!satisfied) return false;
  }
  return true;
}

function tokensOf(name: string | null | undefined): Set<string> {
  if (!name) return new Set();
  const tokens = name
    .trim()
    .toUpperCase()
    .split(/[^A-Z]+/)
    .filter((t) => t.length > 0 && !CONNECTOR_WORDS.has(t));
  return new Set(tokens);
}

/** Equal, or one is a single-letter initial of the other. Checked both directions -- either the
 *  statement or the profile might be the abbreviated one. */
function tokenMatches(a: string, b: string): boolean {
  if (a === b) return true;
  if (a.length === 1) return b.startsWith(a);
  if (b.length === 1) return a.startsWith(b);
  return false;
}
