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

# Two modes, because this runs in two places that have different ideas of "what changed".
#
#   (no args)              the pre-commit hook: diff the INDEX, i.e. what is staged right now.
#   --range BASE HEAD      CI: diff a commit range. A CI checkout has no staged content at all,
#                          so the hook's `--cached` would find nothing and the job would pass
#                          vacuously -- which is worse than not running it, because the green
#                          check reads as coverage.
#
# BASE may be empty or unresolvable on a branch's first push (github.event.before is all-zeros for
# a new branch). That falls back to diffing HEAD against its parent rather than silently scanning
# nothing.
MODE_RANGE=""
if [ "$1" = "--range" ]; then
  MODE_RANGE="yes"
  BASE="$2"
  HEAD="${3:-HEAD}"
fi

if [ -n "$MODE_RANGE" ]; then
  if [ -z "$BASE" ] || [ "$BASE" = "0000000000000000000000000000000000000000" ] \
      || ! git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null; then
    BASE="$HEAD^"
    git rev-parse --verify --quiet "$BASE^{commit}" >/dev/null || BASE=""
  fi
  if [ -z "$BASE" ]; then
    echo "check-fixture-hygiene: no usable base commit; nothing to compare." >&2
    exit 0
  fi
  DIFF_ARGS="$BASE $HEAD"
  staged=$(git diff --name-only --diff-filter=ACM $DIFF_ARGS)
else
  DIFF_ARGS="--cached"
  staged=$(git diff --cached --name-only --diff-filter=ACM)
fi
# Lockfiles are excluded deliberately, and they are the one exception worth making: they are
# generated dependency metadata that no human pastes into, they routinely contain maintainer
# email addresses (npm records them), and being strict JSON they cannot carry a synthetic-ok
# marker to annotate the false positive away. Left in scope, every lockfile change would trip the
# block, and a check that fires on routine commits is a check people learn to bypass.
#
# The extension list is an ALLOWLIST, and it used to omit .csv and .pdf -- the two formats this
# product exists to parse. Scoping by extension makes the same assumption the directory scoping
# above already got wrong: that people only paste customer data into one kind of file. The
# likeliest leak path in this repo is "reproduce a parsing bug from a customer's statement" ->
# save it under src/test/resources/, and the two file types that arrives as were the two this
# check could not see. .py/.sh/.properties were missing for no reason at all.
#
# A PDF also defeats the fallback that caught the original incident. That was PII in readable Java
# and still went unnoticed for weeks; nobody reviews a binary diff at all.
targets=$(printf '%s\n' "$staged" \
  | grep -E '\.(java|ts|tsx|js|jsx|sql|yml|yaml|json|md|txt|trace|csv|py|sh|properties|env|xml|html|kt|swift)$' \
  | grep -vE '(^|/)(package-lock\.json|npm-shrinkwrap\.json|yarn\.lock|pnpm-lock\.yaml)$')

# PDFs are handled separately from the text scan below, because grepping a PDF is close to
# useless: the content is compressed, so a real account number rarely appears as matchable bytes
# and a clean scan would be false reassurance rather than evidence. The honest control for a
# format this check cannot read is to require a human to say so -- a committed PDF must carry a
# synthetic-ok marker in the same commit (in its own path or anywhere in the commit message's
# staged files), or it blocks. Statement fixtures belong in redacted extraction traces (.trace,
# which IS scannable) rather than as original documents; see docs/engineering/trace-lifecycle.md.
staged_pdfs=$(printf '%s\n' "$staged" | grep -iE '\.pdf$' || true)

[ -z "$targets" ] && [ -z "$staged_pdfs" ] && exit 0

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

# PDFs: a path-level decision, since the bytes cannot be meaningfully scanned (see the comment on
# staged_pdfs above). A filename that says it is synthetic is accepted; anything else has to be
# stated explicitly by a human in the same commit.
if [ -n "$staged_pdfs" ]; then
  printf '%s\n' "$staged_pdfs" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    if is_placeholder "$(basename "$f")"; then
      echo "$f: PDF with a synthetic-looking name (allowed)" >> "$warn"
    else
      echo "$f: PDF added -- contents cannot be scanned for customer data" >> "$block"
    fi
  done
fi

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
  # $DIFF_ARGS is "--cached" for the hook and "BASE HEAD" in CI -- see the mode selection at the
  # top. Deliberately unquoted so the two-word range form expands to two arguments.
  # shellcheck disable=SC2086
  added=$(git diff $DIFF_ARGS -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+ (a/|b/|/dev/null)' | grep -v 'synthetic-ok' | cut -c2-)
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
  echo "  For a PDF: its bytes cannot be scanned, so there is no line to mark. Capture a redacted" >&2
  echo "  extraction trace instead (scripts/trace-capture.sh -- see docs/engineering/" >&2
  echo "  trace-lifecycle.md), which is reviewable and IS scanned. If the document is genuinely" >&2
  echo "  synthetic, name the file so it says so (e.g. sample_statement.pdf)." >&2
  echo "" >&2
  exit 1
fi

exit 0
