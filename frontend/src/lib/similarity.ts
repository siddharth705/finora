/** Standard Levenshtein edit distance (insert/delete/substitute), O(n*m), fine for the short
 * category names this is used against (never a bulk-text-search primitive). */
function levenshteinDistance(a: string, b: string): number {
  const rows = a.length + 1;
  const cols = b.length + 1;
  const dp: number[][] = Array.from({ length: rows }, () => new Array(cols).fill(0));

  for (let i = 0; i < rows; i++) dp[i][0] = i;
  for (let j = 0; j < cols; j++) dp[0][j] = j;

  for (let i = 1; i < rows; i++) {
    for (let j = 1; j < cols; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + cost,
      );
    }
  }
  return dp[rows - 1][cols - 1];
}

/** 0 (nothing alike) to 1 (identical, case-insensitive). Used to surface "did you mean X?"
 * suggestions in CategoryCombobox before offering to create a near-duplicate category.
 *
 * Normalizes Levenshtein distance by sum-of-lengths (standard "Levenshtein ratio" convention,
 * as used by fuzzywuzzy and python-Levenshtein libraries) rather than max-of-lengths, for
 * better sensitivity across different string-length pairs. */
export function similarityRatio(a: string, b: string): number {
  const x = a.trim().toLowerCase();
  const y = b.trim().toLowerCase();
  if (x.length === 0 && y.length === 0) return 1;
  if (x.length === 0 || y.length === 0) return 0;
  const distance = levenshteinDistance(x, y);
  const sumLength = x.length + y.length;
  return 1 - distance / sumLength;
}
