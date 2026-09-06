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
UNKNOWN = "UNKNOWN"

REAL_CORPUS, SYNTHETIC = "REAL_CORPUS", "SYNTHETIC"

# Dimensions the value axis can speak about. Each is judged SEPARATELY: an entity whose product and
# transaction count agree is not made less certain by amounts being unobservable, so UNKNOWN is
# per-dimension and never swallows the whole entity.
VALUE_DIMENSIONS = ("date", "amount", "direction", "currency", "description")

# Statement-level facts, not per-transaction ledger detail -- these apply to a REAL_CORPUS
# observation exactly like expectedProduct already does (see match()), unlike VALUE_DIMENSIONS
# above which stays refused for anything but a SYNTHETIC observation. (expected key on the
# ground-truth entity, observed key on the CorpusProbe section.)
STATEMENT_FIELDS = (
    ("expectedOpeningBalance", "openingBalance"),
    ("expectedClosingBalance", "closingBalance"),
    ("expectedStatementPeriodStart", "statementPeriodStart"),
    ("expectedStatementPeriodEnd", "statementPeriodEnd"),
    ("expectedCreditLimit", "creditLimit"),
    ("expectedTotalAmountDue", "totalAmountDue"),
    ("expectedPaymentDueDate", "paymentDueDate"),
)


def _observation_source(record):
    """REAL_CORPUS unless a record says otherwise.

    Defaulting to REAL_CORPUS is the safe direction: an unlabelled record is treated as the one that
    may not carry financial values, so a probe that forgets to declare itself loses capability rather
    than gaining permission.
    """
    return (record.get("observed") or {}).get("observationSource") or REAL_CORPUS


def _values_of(section):
    """The per-transaction values a SYNTHETIC observation may carry. Absent everywhere else."""
    return section.get("transactions")


def _compare_values(expected_rows, observed_rows):
    """-> {dimension: (outcome, detail)}, one entry per dimension, never a single verdict.

    The distinctions this preserves, and why each matters:

      not observed         -> UNKNOWN     nobody looked; never agreement, never a pass
      observed and equal   -> MATCHED
      observed, different  -> UNEXPECTED  a value we did not expect is a FAIL, not a review item
      expected, absent     -> MISSING     the row itself never arrived
      several candidates   -> AMBIGUOUS   pairing failed; inventing one hides a real problem

    An absent observation must never read as zero, as null-matches-anything, or as NOT_APPLICABLE.
    Those three readings are how a value axis quietly stops checking while continuing to pass.
    """
    if observed_rows is None:
        return {d: (UNKNOWN, "not observed -- this observation carries no financial values")
                for d in VALUE_DIMENSIONS}

    results = {}
    for dimension in VALUE_DIMENSIONS:
        outcome, detail = MATCHED, "as expected"
        for i, want in enumerate(expected_rows):
            if want.get(dimension) is None:
                outcome, detail = UNKNOWN, f"row {i}: not asserted by the ground truth"
                break
            if i >= len(observed_rows):
                outcome, detail = MISSING, f"row {i}: expected but not observed"
                break
            got = observed_rows[i].get(dimension)
            if got is None:
                outcome, detail = UNKNOWN, f"row {i}: dimension not observed"
                break
            if str(got) != str(want[dimension]):
                outcome, detail = UNEXPECTED, f"row {i}: observed {got}, expected {want[dimension]}"
                break
        results[dimension] = (outcome, detail)
    return results


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
    source = _observation_source(record)
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

        for expected_key, observed_key in STATEMENT_FIELDS:
            if expected_key not in e:
                continue                               # not asserted -- never fails, never passes
            want = e[expected_key]
            got = s.get(observed_key)
            if got is None:
                issues.append(f"{observed_key}: asserted {want} but not observed")
                worst = REVIEW if worst == PASS else worst
            elif str(got) != str(want):
                issues.append(f"{observed_key}: observed {got}, expected {want}")
                worst = FAIL

        # The value axis. Structural by construction: financial values are legal only on a
        # SYNTHETIC observation, so a real-corpus record cannot carry them even if a future probe
        # were changed to emit some -- the privacy boundary does not depend on anyone remembering.
        observed_rows = _values_of(s) if source == SYNTHETIC else None
        if source != SYNTHETIC and _values_of(s) is not None:
            issues.append("financial values present on a REAL_CORPUS observation -- refused")
            worst = FAIL
        values = _compare_values(e.get("expectedTransactionValues") or [], observed_rows)
        for dimension, (outcome, detail) in values.items():
            if outcome in (UNEXPECTED, MISSING):
                issues.append(f"{dimension}: {detail}")
                worst = FAIL
            elif outcome == AMBIGUOUS:
                issues.append(f"{dimension}: {detail}")
                worst = REVIEW if worst == PASS else worst

        out.append({"id": eid, "outcome": MATCHED, "section": pairs[eid],
                    "status": worst, "detail": "; ".join(issues) or "as expected",
                    "values": {d: {"outcome": o, "detail": t} for d, (o, t) in values.items()}})
        worsen(worst)

    unexpected = [{"section": i, "outcome": UNEXPECTED,
                   "detail": "section present with no expected entity"} for i in unpaired]
    if unexpected:
        worsen(REVIEW)                              # a real discovery OR a spurious section

    return {"verdict": verdict, "observationSource": source,
            "entities": out, "unexpected": unexpected}


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: ground-truth-match.py <ground-truth.json> <corpus-record.json>")
    r = match(json.loads(open(sys.argv[1]).read()), json.loads(open(sys.argv[2]).read()))
    print(json.dumps(r, indent=2))
    return 0 if r["verdict"] == PASS else 1


if __name__ == "__main__":
    sys.exit(main())
