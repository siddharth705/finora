#!/bin/sh
# Warns (never blocks) when newly staged fixtures, tests, or docs look like they might contain
# real financial data copied from a real document instead of fully synthesized values -- see the
# "Synthetic Fixture Policy" in docs/engineering/financial-document-intelligence-principles.md.
#
# Deliberately imprecise (regex heuristics, not a real PII scanner) and warn-only, never blocking
# -- a hard block on an imperfect heuristic just gets bypassed with --no-verify in practice, same
# reasoning the rest of .husky/pre-commit already follows. It exists to prompt a reviewer (or the
# person committing) to look twice, not to be the last line of defense; the policy itself is that.
#
# Scope: only newly ADDED lines (git diff --cached, "+" lines) in files this policy actually
# governs -- test code, fixture builders, and markdown docs -- so it doesn't nag about unrelated
# pre-existing lines or unrelated file types every time anything nearby changes.

staged=$(git diff --cached --name-only --diff-filter=ACM)
targets=$(printf '%s\n' "$staged" | grep -E '(^|/)(test|fixtures)/|Test\.java$|\.md$')
[ -z "$targets" ] && exit 0

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

printf '%s\n' "$targets" | while IFS= read -r f; do
  [ -f "$f" ] || continue
  added=$(git diff --cached -- "$f" | grep -E '^\+[^+]')
  [ -z "$added" ] && continue

  echo "$added" | grep -oE '[0-9]{10,}' | sort -u \
    | sed "s|^|$f: long digit sequence (account/card/reference number?) - |" >> "$tmp"
  echo "$added" | grep -oE '\b[A-Z]{4}0[A-Z0-9]{6}\b' | sort -u \
    | sed "s|^|$f: IFSC-shaped code - |" >> "$tmp"
  echo "$added" | grep -oE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' | sort -u \
    | sed "s|^|$f: email address - |" >> "$tmp"
  echo "$added" | grep -oE '(\+?91[-. ]?)?[6-9][0-9]{9}\b' | sort -u \
    | sed "s|^|$f: phone-number-shaped value - |" >> "$tmp"
done

if [ -s "$tmp" ]; then
  echo "" >&2
  echo "WARNING: staged fixtures/tests/docs contain values that look like real financial data" >&2
  echo "(Synthetic Fixture Policy -- docs/engineering/financial-document-intelligence-principles.md):" >&2
  sed 's/^/  /' "$tmp" >&2
  echo "" >&2
  echo "  If these are already-synthetic placeholder values, this is a false positive and the" >&2
  echo "  commit proceeds as normal -- this check never blocks." >&2
  echo "" >&2
fi

exit 0
