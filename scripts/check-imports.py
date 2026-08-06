#!/usr/bin/env python3
"""
Static cross-reference checker for backend/src/**/*.java -- fails when a file references a
com.finora.* type without a valid same-package/import/wildcard/fully-qualified path to it.

WHY THIS EXISTS
---------------
Written during the v56 module migration (imports/transactions/accounts/budgets/goals/rules) to
catch the "same-package-before-move, broken-after-move" bug: a class that never needed an explicit
import because it sat in the same package as something it used, which breaks silently the moment
either one moves. It caught several real bugs during that migration, most notably
TransactionService.java missing five service imports after moving to com.finora.transactions.

javac catches this too, of course -- but only after the move is finished and everything compiles
together. This runs on a tree mid-migration and names the file and the type, which is the moment
the information is useful.

WHY IT IS A GATE NOW (and was not before)
-----------------------------------------
It shipped with four hand-verified false positives and therefore could not `exit(1)`: a check whose
clean state is "two warnings you are supposed to recognise" is a check nobody reads. Making it fail
the build required getting the true positive count to zero honestly, which meant fixing the
checker rather than widening a filter until it went quiet.

Two of the four had already fixed themselves by the time this was revisited (FP-02, FP-04 -- the
code they pointed at changed), which is exactly why the docstring warned not to trust the list over
the tool. The remaining two were both real defects in this script:

  FP-01  BankRegistry.java: 'Bank' flagged, 31 times. strip_comments_and_strings() removed //
         line comments BEFORE string literals, so the // inside "https://sbi.co.in" ate the rest of
         the line including the closing quote -- unbalancing every string after it, so "Punjab
         National Bank" and friends survived into the body as if they were code. Fixed by
         stripping strings first, and by forbidding a string literal from spanning a newline so one
         stray quote cannot corrupt the rest of the file.

  FP-03  StatementImportServiceSummaryTest.java: 'StatementImportDuplicateCount' flagged despite a
         correct import. The type is NESTED (TransactionRepository.StatementImportDuplicateCount),
         and this script built every fully-qualified name as package + "." + simple name, so it
         looked for com.finora.repository.StatementImportDuplicateCount and never matched the real
         import. The declaration regex is called "topdecl" but happily matches indented nested
         declarations, so nested types were being recorded as if they were top-level. Fixed by
         tracking brace depth and recording each type's real enclosing chain.

FP-02 was the same nesting bug seen from the other side (BankRegistry declares its own nested
Category enum, which was registered as a second top-level declaration of the entity-package
Category and so made the name "ambiguous"). It is fixed by the same change.

ACCEPTING A FALSE POSITIVE
--------------------------
The accept-list below is deliberately EMPTY. If you need to add an entry, follow
check-dependency-advisories.py's contract, which this mirrors: an accepted entry that no longer
corresponds to a real finding FAILS, so the list cannot quietly rot the way the docstring list
above did. Prefer fixing the checker -- both survivors above looked like unfixable regex trivia and
were both real bugs affecting every file, not just the one that surfaced them.

USAGE
-----
    python3 scripts/check-imports.py            # check; exit 1 on any unaccepted finding
    python3 scripts/check-imports.py --self-test  # prove the checker still detects a real break

Paths are relative to this script, not the working directory.
"""
import os
import re
import sys
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.join(SCRIPT_DIR, "..")
BACKEND_SRC = os.path.join(REPO_ROOT, "backend", "src")


class Accepted:
    """One finding somebody has hand-verified as wrong and decided not to fix.

    `reason` must say why the checker is wrong, not that the finding is unimportant. "Harmless" is
    not a reason; "the occurrence is inside a text block the stripper cannot see" is.
    """

    def __init__(self, path, type_name, declared_in, reason):
        self.path = path.replace("\\", "/")
        self.type_name = type_name
        self.declared_in = declared_in
        self.reason = reason

    def key(self):
        return (self.path, self.type_name, self.declared_in)


# Emptied when V62's MerchantLearningEvent introduced a second nested type named Status.
#
# The removed entry excused `Status` in TwoFactorSmsProvider -- a record COMPONENT name mirroring
# 2Factor's JSON response shape, which the tokeniser could not distinguish from a reference to
# PasswordChangeSession.Status. It went stale, but NOT because that file changed: a second type
# named Status now exists, so the name is declared in more than one file, and the `ambiguous` rule
# below skips every Status occurrence everywhere. The false positive is suppressed rather than
# fixed, and one real reference to Status would now be missed along with it.
#
# That is the same mechanic this module's docstring already records happening once with Category.
# The consequence worth writing down: if MerchantLearningEvent.Status is ever renamed or removed,
# Status stops being ambiguous, the false positive comes back, and this entry has to come back
# with it. Restore it from git history rather than rewriting the reasoning from scratch.
ACCEPTED_FALSE_POSITIVES = []


# --------------------------------------------------------------------------- lexing

def strip_comments_and_strings(text):
    """Blank out everything that is not code, so a name in prose is not read as a reference.

    ORDER MATTERS, and getting it wrong was FP-01. Strings are removed BEFORE line comments,
    because "https://x" contains // and a line-comment pass would eat the closing quote. Block
    comments still go first -- they can contain quote characters, and a /* */ never legally starts
    inside a string that we would have already removed.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r'"""[\s\S]*?"""', " ", text)          # text blocks, before single-line strings
    text = re.sub(r'"(\\.|[^"\\\n])*"', '""', text)      # no \n: a stray quote cannot run away
    text = re.sub(r"'(\\.|[^'\\\n])'", "''", text)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


DECL_RE = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")


def declared_types(body):
    """[(simple_name, [enclosing simple names])] for every type declared in `body`.

    Brace-depth tracking rather than a line-anchored regex, so a nested type is recorded as nested.
    Treating nested types as top-level was FP-02 and FP-03: it both invents phantom duplicate
    declarations and builds unmatchable fully-qualified names.
    """
    depths = []
    depth = 0
    index = 0
    for match in re.finditer(r"[{}]|\b(?:class|interface|enum|record)\s+\w+", body):
        depth += body.count("{", index, match.start()) - body.count("}", index, match.start())
        index = match.start()
        token = match.group(0)
        if token == "{":
            depth += 1
            index = match.end()
        elif token == "}":
            depth -= 1
            index = match.end()
        else:
            depths.append((depth, DECL_RE.match(token).group(1)))
            index = match.end()

    results = []
    stack = []  # (depth, name) of types we are currently inside
    for d, name in depths:
        while stack and stack[-1][0] >= d:
            stack.pop()
        results.append((name, [n for _, n in stack]))
        stack.append((d, name))
    return results


PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", re.M)
TOKEN_RE = re.compile(r"(?<![.\w])([A-Z]\w*)")


# --------------------------------------------------------------------------- scan

def scan(files):
    """Returns sorted [(relative_path, type_name, declaring_package)]."""
    info = {}
    declarations = defaultdict(list)  # simple name -> [(fqn, declaring_pkg, file)]

    for path in files:
        with open(path, encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
        pkg_match = PKG_RE.search(raw)
        pkg = pkg_match.group(1) if pkg_match else None
        body = strip_comments_and_strings(raw)
        info[path] = {
            "pkg": pkg,
            "imports": set(IMPORT_RE.findall(raw)),
            # One tokenise per file instead of one regex search per (file, type) pair. Same
            # semantics as the old (?<!\.)\bName\b search -- a name preceded by a dot is a member
            # access, not a reference to a type this file must import.
            "tokens": set(TOKEN_RE.findall(body)),
            "raw": raw,
        }
        if not pkg:
            continue
        for name, enclosing in declared_types(body):
            fqn = ".".join([pkg] + enclosing + [name])
            declarations[name].append((fqn, pkg, path))

    ambiguous = {n for n, locs in declarations.items() if len({f for f, _, _ in locs}) > 1}

    problems = []
    for path, meta in info.items():
        pkg = meta["pkg"]
        if not pkg or not pkg.startswith("com.finora"):
            continue
        wildcards = {i[:-2] for i in meta["imports"] if i.endswith(".*")}
        # Simple names this file already binds with an explicit single-type import, from ANY
        # package. A single-type import wins in Java, so if the file says
        # `import org.springframework.boot.actuate.health.Status;` then a bare `Status` in it is
        # that type and never com.finora.entity's -- there is nothing missing to report. Without
        # this the checker fires on every collision with a third-party simple name.
        bound_names = {i.rsplit(".", 1)[1] for i in meta["imports"] if not i.endswith(".*")}
        for name, locs in declarations.items():
            if name in ambiguous or name not in meta["tokens"] or name in bound_names:
                continue
            fqn, decl_pkg, decl_file = locs[0]
            if decl_file == path or decl_pkg == pkg:
                continue
            if decl_pkg == "com.finora" or not decl_pkg.startswith("com.finora"):
                continue
            # A wildcard `X.*` covers exactly the types whose fully-qualified name is `X.<simple>`,
            # so the test is on the fqn's parent -- which is the PACKAGE for a top-level type and
            # the ENCLOSING CLASS for a nested one. Checking `decl_pkg in wildcards` instead looks
            # equivalent and is not: `import com.finora.dto.AuthDtos.*;` is how most of this
            # codebase's request/response records are imported, and a package-only test misses
            # every one of them. It appeared to work only because fully-qualified names used to be
            # built flat, so two bugs cancelled; fixing the nesting exposed this one across 67
            # references.
            if fqn in meta["imports"] or fqn.rsplit(".", 1)[0] in wildcards:
                continue
            # An enclosing type imported instead of the nested one: `import a.b.Outer;` then
            # `Outer.Inner`. The token scan already ignores dotted references, but the enclosing
            # import legitimately covers this file.
            if any(fqn.startswith(imp + ".") for imp in meta["imports"]):
                continue
            if fqn in meta["raw"]:
                continue
            problems.append((os.path.relpath(path, REPO_ROOT).replace("\\", "/"), name, decl_pkg))

    return sorted(set(problems))


def java_files(root):
    found = []
    for base in ("main/java", "test/java"):
        for dirpath, _, filenames in os.walk(os.path.join(root, base)):
            found.extend(os.path.join(dirpath, f) for f in filenames if f.endswith(".java"))
    return sorted(found)


# --------------------------------------------------------------------------- self-test

SELF_TEST_SOURCES = {
    "main/java/com/finora/probe/ProbeTarget.java": """
        package com.finora.probe;
        public class ProbeTarget {
            public interface Nested { }
        }
    """,
    # Missing `import com.finora.probe.ProbeTarget;` -- must be reported.
    "main/java/com/finora/other/BrokenUser.java": """
        package com.finora.other;
        public class BrokenUser {
            ProbeTarget field;
        }
    """,
    # Correctly imports the NESTED type. Must NOT be reported (this was FP-03).
    "main/java/com/finora/other/NestedImporter.java": """
        package com.finora.other;
        import com.finora.probe.ProbeTarget.Nested;
        public class NestedImporter {
            Nested field;
        }
    """,
    # Names the type only inside a string that follows a URL. Must NOT be reported (this was
    # FP-01: the // in the URL used to eat the closing quote).
    "main/java/com/finora/other/UrlInString.java": """
        package com.finora.other;
        public class UrlInString {
            String a = "https://example.test";
            String b = "ProbeTarget mentioned only in prose";
        }
    """,
    # Wildcard on the ENCLOSING CLASS, not the package -- how most request/response records in
    # this codebase are imported. Must NOT be reported.
    "main/java/com/finora/other/NestedWildcard.java": """
        package com.finora.other;
        import com.finora.probe.ProbeTarget.*;
        public class NestedWildcard {
            Nested field;
        }
    """,
    # Wildcard on the package. Must NOT be reported.
    "main/java/com/finora/other/PackageWildcard.java": """
        package com.finora.other;
        import com.finora.probe.*;
        public class PackageWildcard {
            ProbeTarget field;
        }
    """,
    # Binds the same simple name from a THIRD-PARTY package. A single-type import wins, so this
    # file's `ProbeTarget` is the other one and nothing is missing. Must NOT be reported.
    "main/java/com/finora/other/ShadowedByThirdParty.java": """
        package com.finora.other;
        import org.example.elsewhere.ProbeTarget;
        public class ShadowedByThirdParty {
            ProbeTarget field;
        }
    """,
}


def self_test():
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="check-imports-selftest-")
    try:
        for rel, source in SELF_TEST_SOURCES.items():
            full = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w", encoding="utf-8") as fh:
                fh.write("\n".join(line[8:] for line in source.strip("\n").split("\n")))

        found = scan(java_files(tmp))
        names = {(os.path.basename(p), t) for p, t, _ in found}

        failures = []
        if ("BrokenUser.java", "ProbeTarget") not in names:
            failures.append(
                "MISSED a genuinely missing import (BrokenUser -> ProbeTarget). The checker is "
                "vacuous: it would pass every future run without checking anything.")
        for basename in ("NestedImporter.java", "UrlInString.java", "NestedWildcard.java",
                         "PackageWildcard.java", "ShadowedByThirdParty.java"):
            for f, t in names:
                if f == basename:
                    failures.append(f"FALSE POSITIVE on {basename}: flagged '{t}'.")

        if failures:
            print("check-imports self-test FAILED", file=sys.stderr)
            for f in failures:
                print("  - " + f, file=sys.stderr)
            return 1
        print("check-imports self-test passed "
              "(detects a real break; no FP on nested imports or URLs in strings)")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# --------------------------------------------------------------------------- main

def main():
    if "--self-test" in sys.argv:
        return self_test()

    files = java_files(BACKEND_SRC)
    problems = scan(files)
    accepted = {a.key(): a for a in ACCEPTED_FALSE_POSITIVES}

    unaccepted = [p for p in problems if p not in accepted]
    stale = sorted(k for k in accepted if k not in set(problems))

    # One stream for the whole report. Splitting findings across stdout and stderr interleaves
    # them unpredictably when both are piped to a terminal or a CI log, which had already produced
    # one garbled line mid-finding.
    out = sys.stderr if (unaccepted or stale) else sys.stdout

    print(f"check-imports: {len(files)} files scanned, "
          f"{len(unaccepted)} problem(s), "
          f"{len(accepted)} accepted, {len(stale)} stale", file=out)

    for path, name, decl_pkg in unaccepted:
        print(f"  {path}", file=out)
        print(f"      uses '{name}' (declared in {decl_pkg}) with no import that reaches it",
              file=out)

    if unaccepted:
        print("\nAdd the import, or -- only if the checker is provably wrong -- add an entry to\n"
              "ACCEPTED_FALSE_POSITIVES with a reason that says why it is wrong.", file=out)

    for path, name, decl_pkg in stale:
        print(f"  STALE ACCEPT: {path}: '{name}' ({decl_pkg}) is no longer reported.", file=out)
    if stale:
        print("\nRemove those entries. An accept-list that outlives what it excuses is how this\n"
              "script's own docstring ended up documenting two false positives that had already\n"
              "fixed themselves.", file=out)

    return 1 if (unaccepted or stale) else 0


if __name__ == "__main__":
    sys.exit(main())
