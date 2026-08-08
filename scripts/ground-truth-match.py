#!/usr/bin/env python3
"""Matches a corpus record's observed sections against expected financial entities.

Reference implementation of docs/engineering/ground-truth-model-design.md. Python and stdlib-only for
the same reason as corpus-diff.py: the real corpus cannot reach CI, so the MECHANISM is tested against
synthetic fixtures and that is the only way this logic gets tested at all.

THE ONE DISTINCTION THIS EXISTS TO MAKE
---------------------------------------
Shivani_HDFC yields sections [75, 0, 0]. Two different realities produce that identical record: two
deposits that legitimately have no transaction ledger, or two deposits whose data was not extracted.
Nothing else in the pipeline can tell them apart. Every rule below serves that separation.

NEVER BY POSITION
-----------------
Expected entities carry stable ids that mean nothing to the parser. Pairing is by identity where
available, then by product type, and is allowed to FAIL -- see AMBIGUOUS. corpus-diff.py already
refuses positional comparison once section count changes; a positional matcher would inherit that.
"""

import json
import sys

MATCHED, MISSING, UNEXPECTED, AMBIGUOUS = "MATCHED", "MISSING", "UNEXPECTED", "AMBIGUOUS"
PASS, FAIL, REVIEW = "PASS", "FAIL", "REVIEW"


def _pair(expected, sections):
    """Expected entities -> section indexes. Returns (pairs, ambiguous_ids, unpaired_indexes).

    Identity first, because a masked number is the strongest available evidence. Then product type --
    but ONLY when it selects exactly one candidate. Two identity-less deposits of the same type are
    genuinely indistinguishable, and inventing a pairing there is how one product's transactions get
    attributed to another.
    """
    pairs, ambiguous, taken = {}, [], set()

    for e in expected:
        want = (e.get("expectedIdentity") or {}).get("accountNumberMasked")
        if not want:
            continue
        hit = [i for i, s in enumerate(sections)
               if i not in taken and s.get("accountNumberMasked") == want]
        if len(hit) == 1:
            pairs[e["id"]] = hit[0]
            taken.add(hit[0])

    for e in expected:
        if e["id"] in pairs:
            continue
        hit = [i for i, s in enumerate(sections)
               if i not in taken and s.get("suggestedAccountType") == e.get("expectedProduct")]
        if len(hit) == 1:
            pairs[e["id"]] = hit[0]
            taken.add(hit[0])
        elif len(hit) > 1:
            ambiguous.append(e["id"])

    return pairs, ambiguous, [i for i in range(len(sections)) if i not in taken]


def match(truth, record):
    """-> {"verdict": PASS|FAIL|REVIEW, "entities": [...], "unexpected": [...]}"""
    sections = (record.get("observed") or {}).get("sectionDetail") or []
    expected = truth.get("entities") or []
    pairs, ambiguous, unpaired = _pair(expected, sections)

    out, verdict = [], PASS

    def worsen(v):
        nonlocal verdict
        order = {PASS: 0, REVIEW: 1, FAIL: 2}
        if order[v] > order[verdict]:
            verdict = v

    for e in expected:
        eid = e["id"]
        if eid in ambiguous:
            out.append({"id": eid, "outcome": AMBIGUOUS,
                        "detail": "more than one section fits and no identity distinguishes them"})
            worsen(REVIEW)
            continue
        if eid not in pairs:
            if e.get("expectedPresence") == "ABSENT":
                out.append({"id": eid, "outcome": MATCHED, "detail": "absent as expected"})
                continue
            # THE Shivani defect. An expected entity that is not there at all.
            out.append({"id": eid, "outcome": MISSING, "detail": "expected entity not detected"})
            worsen(FAIL)
            continue

        s = sections[pairs[eid]]
        rows, issues, worst = s.get("rows", 0), [], PASS

        want_rows = e.get("expectedTransactions")
        if want_rows == "NOT_YET_ESTABLISHED" or want_rows is None:
            pass                                    # unestablished is never agreement, and never a pass
        elif want_rows == "NOT_APPLICABLE":
            pass
        elif isinstance(want_rows, int) and rows < want_rows:
            issues.append(f"under-extracted: {rows} of {want_rows}")
            worst = FAIL

        if rows == 0 and want_rows != 0:
            legit = (e.get("zeroTransactionsLegitimate") or {}).get("value", "UNKNOWN")
            if legit == "TRUE":
                pass                                # asserted by a human, with evidence
            elif legit == "FALSE":
                issues.append("zero transactions, and the document states otherwise")
                worst = FAIL
            else:
                # UNKNOWN never resolves to TRUE. Nobody has established it; that is a review item.
                issues.append("zero transactions, legitimacy UNKNOWN -- unestablished, not agreed")
                worst = REVIEW if worst == PASS else worst

        want_product = e.get("expectedProduct")
        if want_product and want_product != "NOT_YET_ESTABLISHED" \
                and s.get("suggestedAccountType") != want_product:
            issues.append(f"product {s.get('suggestedAccountType')} != expected {want_product}")
            worst = FAIL

        out.append({"id": eid, "outcome": MATCHED, "section": pairs[eid],
                    "status": worst, "detail": "; ".join(issues) or "as expected"})
        worsen(worst)

    unexpected = [{"section": i, "outcome": UNEXPECTED,
                   "detail": "section present with no expected entity"} for i in unpaired]
    if unexpected:
        worsen(REVIEW)                              # a real discovery OR a spurious section

    return {"verdict": verdict, "entities": out, "unexpected": unexpected}


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: ground-truth-match.py <ground-truth.json> <corpus-record.json>")
    r = match(json.loads(open(sys.argv[1]).read()), json.loads(open(sys.argv[2]).read()))
    print(json.dumps(r, indent=2))
    return 0 if r["verdict"] == PASS else 1


if __name__ == "__main__":
    sys.exit(main())
