#!/usr/bin/env python3
"""Scans tracked repository content for identifiers that appear in the REAL statement corpus.

WHY THIS EXISTS, AND WHY IT IS DIFFERENT FROM THE OTHER TWO
-----------------------------------------------------------
check-fixture-hygiene.sh answers "does this look like customer data?" from patterns -- long digit
runs, IFSC shapes, email shapes. That is the right question for content nobody has ground truth
about, and it has a hard limit: it cannot tell a real UPI reference from an invented one, and it does
not fire at all on a real first name or a VPA like `utility@ok` that contains no suspicious digits.

This script asks a different question: "does this value actually occur in a real customer statement?"
It has the source documents, so its answer is evidence rather than heuristic. A sweep of this kind is
what found 23 real identifiers across 11 files that months of pattern-based checks had passed.

    pattern scan   ->  might be customer data      ->  runs in CI, guesses
    corpus scan    ->  IS customer data, verified  ->  local only, knows

The cost is that it needs the corpus, which lives outside the working tree by policy. So this can
never run in CI. It is a local pre-merge check, and the pattern scans are its CI-runnable
approximation -- not its replacement.

PREFIXES, NOT JUST WHOLE VALUES
-------------------------------
A real 12-digit UPI reference was quoted three times in a comment as "UPI/1240089..." -- truncated
for display. Every scan in this repository looked for 10+ digit runs, so a 7-digit fragment of a real
identifier evaded all of them, including the first version of this sweep. Whole-value matching is not
sufficient: a leak that has been shortened for readability is still a leak.

So proper prefixes of length >= 8 are matched too, because display truncation cuts the tail. Prefixes
containing a run of four or more identical characters are dropped first -- `00000000` matches every
UUID in the tree and identifies nobody. That filter is the same predicate check-fixture-hygiene.sh
already trusts, and without it this sweep reports 101 lines of noise instead of the 17 real ones.

Suffix and interior fragments are NOT matched, and that is a stated limit rather than an oversight:
they raise the false-positive rate sharply while display truncation does not produce them. If a
leaked interior fragment is ever found, this comment is where the decision to revisit that should
start.

EXIT CODES, AND ONE WAY TO LOSE THEM
------------------------------------
    0   no corpus identifier occurs in tracked content
    1   at least one does

Findings go to stderr and the clean message to stdout, so `... | tail` reports the exit status of
tail rather than of this script. That is not hypothetical -- it is how the first reading of this
scanner's own output was misreported as passing. Check the status directly, or use `set -o pipefail`.

WHAT IT DOES NOT DO
-------------------
It does not judge severity, and it does not distinguish a customer's account number from a bank's
public IFSC or a merchant's VPA -- all three occur in a statement. Classification is a human
decision against repository policy. This reports occurrence.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MIN_PREFIX = 8

# Lifted deliberately from check-fixture-hygiene.sh so the two agree on what is not identifying.
REPEATED_RUN = re.compile(r"(.)\1{3,}")

PATTERNS = [
    re.compile(r"[0-9]{10,}"),                      # account, card, transaction reference
    re.compile(r"\b[A-Z]{4}0[A-Z0-9]{6}\b"),        # IFSC
    re.compile(r"[A-Za-z0-9._%+-]{3,}@[A-Za-z0-9.-]{2,}"),   # email and UPI VPA
    re.compile(r"\b[6-9][0-9]{9}\b"),               # Indian mobile
]

SKIP = re.compile(r"^(backend/target/|.*-lock\.(json|yaml)$|.*\.pdf$)")


def corpus_text(corpus: Path) -> str:
    """Every statement's text, concatenated. Needs the backend's PDFBox on the classpath."""
    cp_file = REPO_ROOT / "backend" / "target" / "corpus-classpath.txt"
    classes = REPO_ROOT / "backend" / "target" / "classes"
    if not cp_file.is_file():
        sys.exit("No backend/target/corpus-classpath.txt. Run a corpus-run.py first, or:\n"
                 "  cd backend && ./mvnw -q -o dependency:build-classpath "
                 "-Dmdep.outputFile=target/corpus-classpath.txt -Dmdep.includeScope=test")

    src = REPO_ROOT / "backend" / "target" / "CorpusDump.java"
    src.write_text(
        'import org.apache.pdfbox.Loader; import org.apache.pdfbox.text.PDFTextStripper;\n'
        'import java.io.File;\n'
        'public class CorpusDump { public static void main(String[] a) throws Exception {\n'
        '  for (File f : new File(a[0]).listFiles((d,n)->n.toLowerCase().endsWith(".pdf"))) {\n'
        '    try (var d = Loader.loadPDF(f)) { System.out.println(new PDFTextStripper().getText(d)); }\n'
        '    catch (Exception e) { System.err.println("skip " + f.getName() + ": " + e); } } } }\n')
    cp = f"{classes}:{cp_file.read_text().strip()}"
    subprocess.run(["javac", "-cp", cp, "-d", str(REPO_ROOT / "backend" / "target" / "corpusdump"),
                    str(src)], check=True, capture_output=True)
    out = subprocess.run(["java", "-cp",
                          f"{REPO_ROOT / 'backend' / 'target' / 'corpusdump'}:{cp}",
                          "CorpusDump", str(corpus)], capture_output=True, text=True)
    if not out.stdout.strip():
        sys.exit(f"no text extracted from any PDF in {corpus}")
    return out.stdout


def needles(text: str) -> set:
    """Whole identifiers plus their proper prefixes. See the module docstring on why prefixes."""
    whole = set()
    for p in PATTERNS:
        for m in p.finditer(text):
            v = m.group()
            if len(v) >= 10 and not REPEATED_RUN.fullmatch(v):
                whole.add(v)

    out = set(whole)
    for v in whole:
        if not v.isdigit():
            continue                                # truncation of a VPA is not a distinct shape
        for n in range(MIN_PREFIX, len(v)):
            frag = v[:n]
            if not REPEATED_RUN.search(frag):       # 00000000 identifies nobody
                out.add(frag)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus", type=Path, help="directory of real statements, OUTSIDE this repository")
    args = ap.parse_args()

    corpus = args.corpus.resolve()
    if not corpus.is_dir():
        sys.exit(f"not a directory: {corpus}")
    # Same refusal as corpus-run.py and trace-capture.sh, for the same reason.
    if str(corpus).startswith(str(REPO_ROOT)):
        sys.exit(f"REFUSED: {corpus} is inside the repository.")

    ns = needles(corpus_text(corpus))
    print(f"corpus identifiers + prefixes to match: {len(ns)}", file=sys.stderr)

    listfile = REPO_ROOT / "backend" / "target" / "corpus-needles.txt"
    listfile.write_text("\n".join(sorted(ns)))
    hits = subprocess.run(["git", "grep", "-nF", "-f", str(listfile), "--", "."],
                          cwd=REPO_ROOT, capture_output=True, text=True).stdout.splitlines()
    hits = [h for h in hits if not SKIP.match(h.split(":", 1)[0])]

    if not hits:
        print("clean -- no corpus identifier occurs in tracked content.")
        return 0

    print("\nREAL CUSTOMER IDENTIFIERS FOUND IN TRACKED CONTENT:", file=sys.stderr)
    for h in hits:
        print(f"  {h[:160]}", file=sys.stderr)
    print(f"\n{len(hits)} line(s). These are verified occurrences, not pattern guesses.\n"
          "Replace with deterministic synthetic values that preserve what each test asserts.\n"
          "Do NOT paste the offending values into a file in this repository to track the work.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
