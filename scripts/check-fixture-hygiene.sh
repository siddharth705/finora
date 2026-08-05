#!/bin/sh
# Guards the "Synthetic Fixture Policy" (docs/engineering/financial-document-intelligence-
# principles.md): real customer statement data must never enter the repository.
#
# TIERED, because precision differs enormously between these heuristics and a blanket block on a
# noisy one just trains people to reach for --no-verify (the reasoning the rest of .husky/pre-commit
# follows, and the reason this script was warn-only to begin with):
#
#   BLOCK  patterns that are specific enough that a hit is almost certainly real customer data --
#          email addresses, IFSC codes, Indian mobile numbers. A placeholder of the same shape is
#          recognised and allowed (see is_placeholder), so writing a synthetic fixture is never
#          fought by this check.
#   WARN   long digit sequences. Real account/reference numbers look exactly like timestamps, ids
#          and coordinates, so this one cannot be blocked without constant false positives.
#
# Escape hatch is deliberately NOT --no-verify: put "synthetic-ok" in a comment on the same line.
# That keeps the exception visible in the diff and reviewable, instead of silently disabling every
# check in the repo for that commit.
#
# Warn-only was not enough in practice: real account numbers, a real IFSC and real UPI references
# were committed into a test file while this script printed a warning that scrolled past unread.
# The policy is the last line of defense, but the hook should make violating it require intent.
#
# Scope: only newly ADDED lines, in every source and doc file.
#
# This used to cover test code, fixtures and docs only -- and a real customer's name and 14-digit
# account number sat in a MAIN-source doc comment for weeks, invisible to this check, because
# PdfTableLocator.java is not a test file. It was then copied out of that comment into a new
# fixture, where the check finally caught it. Real statement data reaches source comments by
# exactly the route it reaches fixtures: someone documenting what a real document looked like.
# Scoping a PII check by directory assumes people only paste customer data in one kind of file.

staged=$(git diff --cached --name-only --diff-filter=ACM)
# Lockfiles are excluded deliberately, and they are the one exception worth making: they are
# generated dependency metadata that no human pastes into, they routinely contain maintainer
# email addresses (npm records them), and being strict JSON they cannot carry a synthetic-ok
# marker to annotate the false positive away. Left in scope, every lockfile change would trip the
# block, and a check that fires on routine commits is a check people learn to bypass.
targets=$(printf '%s\n' "$staged" \
  | grep -E '\.(java|ts|tsx|js|jsx|sql|yml|yaml|json|md|txt|trace)$' \
  | grep -vE '(^|/)(package-lock\.json|npm-shrinkwrap\.json|yarn\.lock|pnpm-lock\.yaml)$')
[ -z "$targets" ] && exit 0

warn=$(mktemp)
block=$(mktemp)
trap 'rm -f "$warn" "$block"' EXIT

# A deliberate placeholder: a run of 4+ identical characters (XXXX, 999999), or a word marking it
# as fake. Note this is applied to the WHOLE token for emails/phones but only to an IFSC's 6-char
# branch part -- real Indian IFSCs routinely contain runs of zeros (HDFC0000007), so the looser
# test would wave real ones straight through.
is_placeholder() {
  printf '%s' "$1" | grep -qiE '(.)\1{3,}|example|sample|test|dummy|fake|placeholder|redacted|noreply|localhost'
}

printf '%s\n' "$targets" | while IFS= read -r f; do
  [ -f "$f" ] || continue
  # `grep -E '^\+\+\+ (a/|b/|/dev/null)'` excludes only the file-header line unified diff always
  # emits verbatim as "+++ b/<path>" (or "+++ /dev/null" for a delete) -- never a content line.
  #
  # This used to be `grep -E '^\+[^+]'`, which excluded a line by asking "is the character right
  # after diff's own '+' marker ALSO a '+'?" -- a heuristic that assumed only the header could ever
  # produce two leading '+'s. It cannot tell that guess apart from a genuinely added line whose own
  # first character happens to be '+': a phone number staged as "+919876543210" becomes
  # "++919876543210" once diff's marker is prepended, matches the same "two leading pluses" shape
  # as "+++ b/path", and was silently dropped before ever reaching the phone regex below -- a real
  # false negative in exactly the PII class this script exists to catch, not merely a cosmetic one.
  # Matching the header's actual fixed text instead of a shape it happens to share fixes both at
  # once.
  #
  # cut -c2- then drops the real per-line '+' marker so the extraction regexes below see the
  # line's own content, not the marker. Left in place, it corrupted every match starting at column
  # 0 of an added line: '+' is a legal character in an email's local part (RFC 5322), so a line
  # added as exactly "real.customer@gmail.com is their email" was reported as
  # "+real.customer@gmail.com". That half was a display bug, not a false negative -- the block/warn
  # decision already fired correctly -- but a developer fixing the flagged line off a corrupted
  # value is exactly the kind of friction that erodes trust in what this hook reports.
  added=$(git diff --cached -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+ (a/|b/|/dev/null)' | grep -v 'synthetic-ok' | cut -c2-)
  [ -z "$added" ] && continue

  echo "$added" | grep -oE '[0-9]{10,}' | sort -u | while IFS= read -r hit; do
    [ -n "$hit" ] && echo "$f: long digit sequence (account/card/reference number?) - $hit" >> "$warn"
  done

  echo "$added" | grep -oE '\b[A-Z]{4}0[A-Z0-9]{6}\b' | sort -u | while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    branch=$(printf '%s' "$hit" | cut -c6-11)
    if printf '%s' "$branch" | grep -qE '^(.)\1{5}$'; then
      echo "$f: IFSC-shaped placeholder (allowed) - $hit" >> "$warn"
    else
      echo "$f: IFSC code - $hit" >> "$block"
    fi
  done

  echo "$added" | grep -oE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' | sort -u | while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    if is_placeholder "$hit"; then
      echo "$f: example email (allowed) - $hit" >> "$warn"
    else
      echo "$f: email address - $hit" >> "$block"
    fi
  done

  echo "$added" | grep -oE '(\+?91[-. ]?)?[6-9][0-9]{9}\b' | sort -u | while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    if is_placeholder "$hit"; then
      echo "$f: placeholder phone (allowed) - $hit" >> "$warn"
    else
      echo "$f: phone number - $hit" >> "$block"
    fi
  done
done

if [ -s "$warn" ]; then
  echo "" >&2
  echo "NOTE: staged fixtures/tests/docs contain values worth a second look" >&2
  echo "(Synthetic Fixture Policy -- docs/engineering/financial-document-intelligence-principles.md):" >&2
  sed 's/^/  /' "$warn" >&2
  echo "" >&2
  echo "  These do not block. Confirm they are synthetic before pushing." >&2
  echo "" >&2
fi

if [ -s "$block" ]; then
  echo "" >&2
  echo "COMMIT BLOCKED: real customer data must never enter this repository" >&2
  echo "(Synthetic Fixture Policy -- docs/engineering/financial-document-intelligence-principles.md):" >&2
  sed 's/^/  /' "$block" >&2
  echo "" >&2
  echo "  Replace these with synthetic placeholders. Keep only the structure under test --" >&2
  echo "  an IFSC's 4-letter bank prefix is meaningful, its branch code is not: HDFC0XXXXXX." >&2
  echo "  Capture real layouts as redacted extraction traces rather than copying values by hand." >&2
  echo "" >&2
  echo "  If a value genuinely is synthetic and this is a false positive, mark that line with a" >&2
  echo "  'synthetic-ok' comment so the exception stays visible in review." >&2
  echo "" >&2
  exit 1
fi

exit 0
