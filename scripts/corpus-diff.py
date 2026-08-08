#!/usr/bin/env python3
"""Compares two corpus runs and reports what changed. Carries no opinion about what is correct.

WHY THIS EXISTS
---------------
scripts/corpus-run.py records where the pipeline is. This answers the only question that protects a
parser change: *what moved?* Without it, "I improved HSBC" and "I halved Mann_HDFC" are the same
commit message.

ONE RESPONSIBILITY
------------------
    before.jsonl + after.jsonl -> per-document change list -> regression / review / improvement

WHAT IT DOES NOT DO, AND WHY IT MATTERS MORE THAN WHAT IT DOES
--------------------------------------------------------------
This script contains **no expectation about any specific document**. No "HDFC has three sections", no
"HSBC yields N rows", no filename in any conditional. See ADR-004: the corpus is a regression net,
not the product boundary, and a diff tool that knows what each file *should* produce is the exact
mechanism by which "make these 16 pass" becomes the architecture.

Correctness belongs in ground-truth fixtures, which are per-document data by definition and are the
only place a filename may legitimately appear. This script only ever says "this differs from last
time", never "this is wrong".

Consequence worth stating: a change flagged `regression` here is *a change in a bad direction*, not
a proven defect -- and `improvement` is not proof of correctness either. More rows can mean a row
split in half. The verdict is a prompt to look, not a substitute for looking.

CLASSIFICATION IS RANKED BY TIER, NOT BY A TOTAL ORDER
------------------------------------------------------
DocumentClassification does not assert a total severity order -- its own comments rank
PARSED_RECONCILIATION_FAILED above PARSED_INCOMPLETE on severity while `of()` checks column
ambiguity between them for a different reason. Inventing a total order here would mean this script
asserting a ranking the enum does not, and then reporting confident direction for movement that has
none.

So classifications are grouped into three tiers by *how much of the document survived*, which is the
question a regression check actually has. Movement to a lower tier is a regression. Movement within a
tier is reported for review and deliberately not ranked. An unrecognised classification -- the enum
gained a value and this script did not -- is reported for review and never silently ranked, because
defaulting an unknown to "fine" is how a diff tool goes quiet at the moment it matters.

SECTION ALIGNMENT
-----------------
Sections are compared positionally, which is valid only while the section count is unchanged. When it
changes, index 1 in the "before" is not index 1 in the "after", and a positional walk emits a stream
of false changes that buries the one real finding (the structure changed). So a section-count change
is reported as exactly that, and per-section comparison is suppressed with the reason stated.
"""

import argparse
import json
import sys
from pathlib import Path

REGRESSION = "regression"
REVIEW = "review"
IMPROVEMENT = "improvement"

# How much of the document survived. NOT a severity order -- see the module docstring.
TIERS = {
    "DOCUMENT_INVALID": 0,          # nothing usable came out
    "SCANNED_OCR_REQUIRED": 0,
    "LAYOUT_UNSUPPORTED": 0,
    "PARSED_RECONCILIATION_FAILED": 1,  # rows, with a known problem attached
    "COLUMNS_AMBIGUOUS": 1,
    "PARSED_INCOMPLETE": 1,
    "PARSED_COMPLETE": 2,           # rows, with no signal against them
}

# A rule going from a pass to WARNING or FAILED is a regression in that rule. VERIFIED and
# NOT_APPLICABLE are both "no problem found", but they are not interchangeable: a rule that stops
# applying has stopped checking, which is a change worth a human look rather than silence.
_PASSING = {"VERIFIED", "NOT_APPLICABLE"}
_FAILING_RANK = {"FAILED": 0, "WARNING": 1}


def _c(dimension, severity, detail):
    return {"dimension": dimension, "severity": severity, "detail": detail}


def _num(before, after, dimension, decrease=REGRESSION, increase=IMPROVEMENT):
    """A counted quantity. Decrease is the bad direction for every count in a corpus record."""
    if before == after:
        return []
    return [_c(dimension, decrease if after < before else increase, f"{before} -> {after}")]


def compare_classification(b, a, out):
    bc, ac = b.get("documentClassification"), a.get("documentClassification")
    if bc == ac:
        return
    bt, at = TIERS.get(bc), TIERS.get(ac)
    if bt is None or at is None:
        # This script is behind the enum. Say so; do not guess a direction.
        out.append(_c("documentClassification", REVIEW,
                      f"{bc} -> {ac} (unrecognised classification; corpus-diff.py needs its tier "
                      "declared before direction can be judged)"))
    elif at < bt:
        out.append(_c("documentClassification", REGRESSION, f"{bc} -> {ac} (tier {bt} -> {at})"))
    elif at > bt:
        out.append(_c("documentClassification", IMPROVEMENT, f"{bc} -> {ac} (tier {bt} -> {at})"))
    else:
        out.append(_c("documentClassification", REVIEW,
                      f"{bc} -> {ac} (same tier; the enum asserts no order between these)"))


def compare_verification(b, a, out, prefix=""):
    """Per rule, over the union of rule names -- so a rule appearing or vanishing is visible."""
    for rule in sorted(set(b) | set(a)):
        before, after = b.get(rule), a.get(rule)
        if before == after:
            continue
        dim = f"{prefix}verification.{rule}"
        if before is None or after is None:
            out.append(_c(dim, REVIEW, f"{before} -> {after} (rule appeared or was removed)"))
        elif before in _PASSING and after not in _PASSING:
            out.append(_c(dim, REGRESSION, f"{before} -> {after}"))
        elif before not in _PASSING and after in _PASSING:
            out.append(_c(dim, IMPROVEMENT, f"{before} -> {after}"))
        elif before in _PASSING and after in _PASSING:
            # VERIFIED <-> NOT_APPLICABLE: the rule changed whether it applies at all.
            out.append(_c(dim, REVIEW, f"{before} -> {after} (rule applicability changed)"))
        else:
            worse = _FAILING_RANK[after] < _FAILING_RANK[before]
            out.append(_c(dim, REGRESSION if worse else IMPROVEMENT, f"{before} -> {after}"))


def compare_sections(b, a, out):
    bd, ad = b.get("sectionDetail") or [], a.get("sectionDetail") or []
    if len(bd) != len(ad):
        out.append(_c("sectionStructure", REVIEW,
                      f"{len(bd)} -> {len(ad)} sections; per-section comparison suppressed because "
                      "positional alignment is not meaningful once the structure changes"))
        return

    for i, (x, y) in enumerate(zip(bd, ad)):
        p = f"section[{i}]."
        # The reason Step 2b exists: rows moving between sections at a constant document total is
        # money attributed to the wrong product, and only this loop can see it.
        out.extend(_num(x.get("rows"), y.get("rows"), f"{p}rows"))

        for field in ("detectedProduct", "suggestedAccountType"):
            if x.get(field) != y.get(field):
                out.append(_c(f"{p}{field}", REVIEW, f"{x.get(field)} -> {y.get(field)}"))

        # Identity is the input to ProductIdentity. Losing it means re-import cannot resolve.
        bx, ay = x.get("accountNumberMasked"), y.get("accountNumberMasked")
        if bx != ay:
            sev = REGRESSION if (bx and not ay) else (IMPROVEMENT if (ay and not bx) else REVIEW)
            out.append(_c(f"{p}accountNumberMasked", sev, f"{bx} -> {ay}"))

        if x.get("productNeedsReview") != y.get("productNeedsReview"):
            was = x.get("productNeedsReview")
            out.append(_c(f"{p}productNeedsReview", IMPROVEMENT if was else REVIEW,
                          f"{was} -> {y.get('productNeedsReview')}"))

        bc, ac = x.get("productConfidence"), y.get("productConfidence")
        if bc is not None and ac is not None and abs(bc - ac) > 1e-6:
            out.append(_c(f"{p}productConfidence", REVIEW, f"{bc} -> {ac}"))

        compare_verification(x.get("verification") or {}, y.get("verification") or {}, out, p)


def compare_record(before, after):
    """One document, two runs -> a list of changes. No knowledge of which document this is."""
    out = []

    bs, as_ = before.get("status"), after.get("status")
    if bs != as_:
        if as_ != "ok":
            out.append(_c("status", REGRESSION,
                          f"ok -> {as_}: {(after.get('error') or {}).get('type')}"))
        else:
            out.append(_c("status", IMPROVEMENT, f"{bs} -> ok"))
        return out                       # Nothing below is comparable across an error boundary.
    if bs != "ok":
        bt = (before.get("error") or {}).get("type")
        at = (after.get("error") or {}).get("type")
        if bt != at:
            out.append(_c("error.type", REVIEW, f"{bt} -> {at}"))
        return out

    bo, ao = before.get("observed", {}), after.get("observed", {})

    for field in ("rows", "extractedChars", "positionedRuns"):
        out.extend(_num(bo.get(field), ao.get(field), field))

    # Page count changing for the same filename means the input changed, not the parser.
    if bo.get("pages") != ao.get("pages"):
        out.append(_c("pages", REVIEW, f"{bo.get('pages')} -> {ao.get('pages')} "
                                       "(same filename, different document?)"))

    if bo.get("sections") != ao.get("sections"):
        out.extend(_num(bo.get("sections"), ao.get("sections"), "sections", decrease=REVIEW,
                        increase=REVIEW))

    # Fingerprint and capabilities are how the pipeline decided what to do. A change here is neutral
    # in itself and is the usual explanation for everything else in the list.
    if bo.get("layoutFingerprint") != ao.get("layoutFingerprint"):
        out.append(_c("layoutFingerprint", REVIEW,
                      f"{bo.get('layoutFingerprint')} -> {ao.get('layoutFingerprint')}"))

    bcap, acap = set(bo.get("capabilities") or []), set(ao.get("capabilities") or [])
    if bcap != acap:
        parts = []
        if acap - bcap:
            parts.append("+" + ", ".join(sorted(acap - bcap)))
        if bcap - acap:
            parts.append("-" + ", ".join(sorted(bcap - acap)))
        out.append(_c("capabilities", REVIEW, "; ".join(parts)))

    if (bo.get("banks") or []) != (ao.get("banks") or []):
        out.append(_c("banks", REVIEW, f"{bo.get('banks')} -> {ao.get('banks')}"))

    compare_verification(bo.get("verification") or {}, ao.get("verification") or {}, out)
    compare_sections(bo, ao, out)
    compare_classification(before.get("derived", {}), after.get("derived", {}), out)

    bd, ad = before.get("derived", {}), after.get("derived", {})
    if bd.get("suspectedIncompleteByPageRatio") != ad.get("suspectedIncompleteByPageRatio"):
        now = ad.get("suspectedIncompleteByPageRatio")
        out.append(_c("suspectedIncompleteByPageRatio", REGRESSION if now else IMPROVEMENT,
                      f"{bd.get('suspectedIncompleteByPageRatio')} -> {now}"))

    return out


def load(path):
    records = {}
    for n, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except json.JSONDecodeError as exc:
            sys.exit(f"{path}:{n}: not valid JSON: {exc}")
        if "file" not in r:
            sys.exit(f"{path}:{n}: record has no 'file' key")
        records[r["file"]] = r
    if not records:
        sys.exit(f"{path}: no records")
    return records


def diff(before, after):
    """Matched by filename, the only stable key a corpus record has."""
    result = {"changed": {}, "added": sorted(set(after) - set(before)),
              "removed": sorted(set(before) - set(after))}
    for name in sorted(set(before) & set(after)):
        changes = compare_record(before[name], after[name])
        if changes:
            result["changed"][name] = changes
    return result


def tally(d):
    counts = {REGRESSION: 0, REVIEW: 0, IMPROVEMENT: 0}
    for changes in d["changed"].values():
        for ch in changes:
            counts[ch["severity"]] += 1
    return counts


def report(d, before_path, after_path):
    counts = tally(d)
    print(f"before: {before_path}\nafter:  {after_path}\n")

    for name, changes in d["changed"].items():
        worst = (REGRESSION if any(c["severity"] == REGRESSION for c in changes)
                 else REVIEW if any(c["severity"] == REVIEW for c in changes) else IMPROVEMENT)
        print(f"{name}  [{worst}]")
        for ch in sorted(changes, key=lambda c: (c["severity"] != REGRESSION, c["dimension"])):
            print(f"    {ch['severity']:<11} {ch['dimension']:<38} {ch['detail']}")
        print()

    # Corpus membership changed. Not a regression -- adding statements is how the net grows -- but it
    # does mean the two runs are not over the same inputs, which changes how totals read.
    for label, names in (("only in after (added)", d["added"]),
                         ("only in before (removed)", d["removed"])):
        if names:
            print(f"{label}: {', '.join(names)}\n")

    if not d["changed"] and not d["added"] and not d["removed"]:
        print("no differences")

    print(f"  {counts[REGRESSION]} regression   {counts[REVIEW]} review   "
          f"{counts[IMPROVEMENT]} improvement   "
          f"({len(d['changed'])} document(s) changed)")
    # Said every run: this tool compares runs, it does not know what is correct. Until ground truth
    # exists, "no regressions" means "nothing moved in a bad direction", not "the corpus is right".
    print("  a verdict here is a change in direction, not a proof of correctness "
          "(correctness needs ground truth)")
    return counts


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("before", type=Path)
    ap.add_argument("after", type=Path)
    ap.add_argument("--json", action="store_true", help="emit the diff as JSON instead of a report")
    ap.add_argument("--strict", action="store_true",
                    help="also exit non-zero when only review-level changes were found")
    args = ap.parse_args()

    d = diff(load(args.before), load(args.after))

    if args.json:
        print(json.dumps(d, indent=2, sort_keys=True))
        counts = tally(d)
    else:
        counts = report(d, args.before, args.after)

    # Unlike corpus-run.py, this script IS a gate, so its exit code depends on what it found.
    if counts[REGRESSION]:
        return 1
    return 1 if (args.strict and counts[REVIEW]) else 0


if __name__ == "__main__":
    sys.exit(main())
