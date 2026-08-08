#!/usr/bin/env python3
"""Scans tracked repository content for identifiers that appear in the REAL statement corpus.

WHY THIS EXISTS, AND WHY IT IS DIFFERENT FROM THE OTHER TWO
-----------------------------------------------------------
check-fixture-hygiene.sh answers "does this look like customer data?" from patterns -- long digit
runs, IFSC shapes, email shapes. That is the right question for content nobody has ground truth
about, and it has a hard limit: it cannot tell a real UPI reference from an invented one, and it does
not fire at all on a real first name or a VPA like `paybill@xy` that contains no suspicious digits.

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

# ---------------------------------------------------------------- separated identifiers
#
# A card number written "1234 5678 9012 3456" and the same number written contiguously are the same
# identifier, and every digit rule in this repository saw only the second. That is not hypothetical:
# a real card number and a real account number sat in a fixture through seven green automated gates
# because their separators hid them, on BOTH sides of the comparison -- so normalisation has to apply
# to the corpus extraction as well as the repository scan, or the two disagree by construction.
#
# Deliberately narrow, because the failure mode of a broad rule here is a scanner people bypass:
#   - at least MIN_SEPARATED_DIGITS digits in total, which excludes dates (8 at most) and ordinary
#     quantities;
#   - groups joined by a SINGLE space or hyphen only, never a dot or comma, which excludes monetary
#     amounts;
#   - the UUID shape 8-4-4-4-12 is rejected outright, because an all-digit UUID would otherwise
#     qualify on length alone.
MIN_SEPARATED_DIGITS = 12
SEPARATED = re.compile(r"(?<![\d.,-])\d{2,6}(?:[ -]\d{2,6}){1,5}(?![\d.,])")
UUID_GROUPS = (8, 4, 4, 4, 12)


def separated_digits(text: str) -> set:
    """Digit-only forms of separator-split identifiers. Empty set is the normal case."""
    out = set()
    for m in SEPARATED.finditer(text):
        raw = m.group()
        groups = [len(g) for g in re.split(r"[ -]", raw)]
        if tuple(groups) == UUID_GROUPS:
            continue
        digits = re.sub(r"[ -]", "", raw)
        if len(digits) >= MIN_SEPARATED_DIGITS and not REPEATED_RUN.fullmatch(digits):
            out.add(digits)
    return out


def _self_test() -> int:
    cases = [
        ("card, spaced",      "CREDIT CARD ACCOUNT  4000 1111 2222 3333", {"4000111122223333"}),
        ("card, hyphenated",  "card 1234-5678-9012-3456",                 {"1234567890123456"}),  # synthetic-ok: sequential test pattern, absent from the corpus
        ("account, 3-6-3",    "SAVINGS ACCOUNT-RES  100-111111-002",      {"100111111002"}),
        ("uuid, all digits",  "id 12345678-1234-1234-1234-123456789012",  set()),  # synthetic-ok: sequential test pattern, absent from the corpus
        ("date",              "txn on 22/07/2026 and 15-07-2026",         set()),
        ("amount",            "Total 1,817.00 Minimum 200.00",            set()),
        ("short quantity",    "rows 12 34",                               set()),
        ("synthetic fixture", "card 0000 0000 0000 0000",                 set()),
    ]
    bad = 0
    for name, text, want in cases:
        got = separated_digits(text)
        ok = got == want
        bad += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {name:<20} -> {sorted(got) if got else '(none)'}")
    print(f"\n  {len(cases) - bad} passed, {bad} failed")
    return 1 if bad else 0


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

    out = set(whole) | separated_digits(text)
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
    ap.add_argument("corpus", type=Path, nargs="?",
                    help="directory of real statements, OUTSIDE this repository")
    ap.add_argument("--self-test", action="store_true",
                    help="verify separator normalisation and its false-positive guards; no corpus needed")
    args = ap.parse_args()

    if args.self_test:
        return _self_test()
    if args.corpus is None:
        ap.error("corpus directory required (or pass --self-test)")

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

    # SYMMETRY. The pass above greps literally, so it only catches a corpus value that is spaced in
    # the document and contiguous in the tree. The real card number was the other direction --
    # contiguous in the statement, spaced 4x4 in the fixture -- and no literal needle can see that.
    # So the repository side is normalised the same way the corpus side is, and the comparison is
    # made on digits alone. Without this, "separator-tolerant" would be true of one side only, which
    # is the same disagreement-by-construction the corpus normalisation exists to prevent.
    corpus_digits = {n for n in ns if n.isdigit() and len(n) >= MIN_SEPARATED_DIGITS}
    tracked = subprocess.run(["git", "ls-files"], cwd=REPO_ROOT,
                             capture_output=True, text=True).stdout.splitlines()
    for rel in tracked:
        if SKIP.match(rel):
            continue
        f = REPO_ROOT / rel
        try:
            body = f.read_text(errors="ignore")
        except OSError:
            continue
        for n_, line in enumerate(body.splitlines(), 1):
            if "synthetic-ok" in line:
                continue
            for d in separated_digits(line) & corpus_digits:
                hits.append(f"{rel}:{n_}: separator-split corpus identifier ({len(d)} digits)")

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
