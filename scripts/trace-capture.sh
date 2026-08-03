#!/bin/sh
# Turns a real statement into a validated, committable regression fixture.
#
# The command this replaces lived only in a doc comment, took two properties, and ended with the
# instruction "read the written file before committing". That was the control in force when a
# customer's name and account number reached the repository, and it was also the control in force
# when three traces were captured with their deposit vocabulary redacted away -- neither the PII
# nor the missing evidence was noticed by reading.
#
# This script exists so the workflow is one command with the checks built in rather than a
# remembered sequence:
#
#   1. validate arguments before touching anything
#   2. capture and redact
#   3. VALIDATE the redaction (PII, required evidence, parseability, capability claims)
#   4. refuse to write anything that fails
#   5. run the regression suite against the new trace
#   6. print a summary a human can approve in seconds
#
# A trace is EVIDENCE, not test data: it exists to preserve the real-world document structure that
# motivated a capability. If a trace no longer contains that evidence, the capability it protects
# loses its grounding -- which is why requiredHeaders is a first-class argument and not an
# afterthought. See docs/engineering/trace-lifecycle.md.

set -e

usage() {
  cat >&2 <<'USAGE'
Usage:
  ./scripts/trace-capture.sh <trace-name> <path-to.pdf> [options]

Required:
  <trace-name>            capability-descriptive, never a bank name on its own
                          e.g. composite-deposit-schedules, wrapped-description-credit-card
  <path-to.pdf>           the real statement. Never committed; never copied into the repo.

Options (all recorded into the trace, so it can explain itself later):
  --source "<text>"       what the document is, in words. NOT a file path -- a path points at a
                          customer statement someone still has.
  --capabilities "A,B"    capability names this trace exists to protect
  --requires "A,B"        structural tokens that MUST survive redaction, e.g. "Maturity Date"
                          If any is masked away, the capture is REFUSED -- this is the check that
                          would have caught the deposit-vocabulary incident.
  --regressions "#12,#14" issue identifiers this trace closes
  --why "<text>"          one sentence: what this document taught the engine

Example:
  ./scripts/trace-capture.sh composite-deposit-schedules ~/statements/hdfc-june.pdf \
    --source "HDFC combined statement: savings + FD + RD" \
    --capabilities "FINANCIAL_PRODUCT_DISCOVERY,COMPOSITE_STATEMENT" \
    --requires "Maturity Date,Rate of Interest,Narration" \
    --why "One document containing three different products under one relationship number."
USAGE
  exit 2
}

[ $# -lt 2 ] && usage

TRACE_NAME="$1"
PDF_PATH="$2"
shift 2

SOURCE="unspecified"
CAPABILITIES=""
REQUIRES=""
REGRESSIONS=""
WHY=""

while [ $# -gt 0 ]; do
  case "$1" in
    --source)       SOURCE="$2"; shift 2 ;;
    --capabilities) CAPABILITIES="$2"; shift 2 ;;
    --requires)     REQUIRES="$2"; shift 2 ;;
    --regressions)  REGRESSIONS="$2"; shift 2 ;;
    --why)          WHY="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

# --- 1. Validate arguments -----------------------------------------------------------------------

if [ ! -f "$PDF_PATH" ]; then
  echo "No such file: $PDF_PATH" >&2
  exit 1
fi

case "$TRACE_NAME" in
  *[!a-z0-9-]*)
    echo "Trace name must be lowercase letters, digits and hyphens: $TRACE_NAME" >&2
    exit 1 ;;
esac

# A path under the repo means the statement was copied in, which the Synthetic Fixture Policy
# forbids -- catch it here rather than after it has been staged for commit.
case "$(cd "$(dirname "$PDF_PATH")" && pwd)" in
  "$(cd "$(dirname "$0")/.." && pwd)"*)
    echo "" >&2
    echo "REFUSED: $PDF_PATH is inside the repository." >&2
    echo "Real customer statements must never live in the repo, even untracked -- one 'git add -A'" >&2
    echo "away from being committed. Keep the file outside and pass an absolute path." >&2
    exit 1 ;;
esac

if [ -z "$REQUIRES" ]; then
  echo "" >&2
  echo "WARNING: no --requires given." >&2
  echo "Nothing will assert that this trace still contains the evidence it was captured for, so a" >&2
  echo "future allowlist change can silently strip it while every test keeps passing. That is the" >&2
  echo "exact failure this workflow exists to prevent. Continuing anyway." >&2
  echo "" >&2
fi

# --- 2-4. Capture, redact, validate, write (or refuse) --------------------------------------------

cd "$(dirname "$0")/../backend"

echo "Capturing $TRACE_NAME from $PDF_PATH ..."
./mvnw -q -o test -Dtest='PdfPipelineDiagnostic#captureRedactedTrace' -DfailIfNoTests=false \
  -DpdfPath="$PDF_PATH" \
  -DtraceName="$TRACE_NAME" \
  -Dsource="$SOURCE" \
  -Dcapabilities="$CAPABILITIES" \
  -DrequiredHeaders="$REQUIRES" \
  -Dregressions="$REGRESSIONS" \
  -Dmotivation="$WHY"

TRACE_FILE="src/test/resources/traces/$TRACE_NAME.trace"
if [ ! -f "$TRACE_FILE" ]; then
  echo "" >&2
  echo "No trace was written -- see the blockers above." >&2
  exit 1
fi

# --- 5. Regression suite against the new trace ----------------------------------------------------

echo ""
echo "Running the trace-backed regression suite ..."
./mvnw -q -o test -Dtest='TraceCorpusHealthTest,GoldenOutputSnapshotTest,*PdfPreviewGeneratorTest,FinancialProductClassifierTest' \
  -DfailIfNoTests=false

# --- 6. Summary -----------------------------------------------------------------------------------

cat <<EOF

Captured and validated: $TRACE_FILE

Before committing:
  - the golden snapshot for this trace may have changed; read the diff, do not regenerate blindly
  - confirm the summary above lists the capabilities you expected

EOF
