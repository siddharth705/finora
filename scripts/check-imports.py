#!/usr/bin/env python3
"""
Static cross-reference checker for backend/src/**/*.java -- flags files that reference a
com.finora.* type without a valid same-package/import/wildcard/fully-qualified path to it.

Written during the v56 module migration pass (imports/transactions/accounts/budgets/goals/rules)
specifically to catch the "same-package-before-move, broken-after-move" class of bug: a class
that never needed an explicit import because it was in the same package as something it used,
which silently breaks the moment that something else moves to a different package. This caught
several real bugs during that migration (see git history / CHANGELOG around that work) -- most
notably TransactionService.java missing five service imports after moving to com.finora.transactions.

Usage:
    python3 scripts/check-imports.py

Run from the repo root (or anywhere -- BACKEND_SRC below is relative to this script's location,
not the current working directory).

KNOWN FALSE POSITIVES (as of the v56 diagnostics/needs-attention pass -- re-verify these by hand
if this list and the tool's output ever disagree, don't assume the tool is right):

  FP-01  backend/src/main/java/com/finora/util/BankRegistry.java: 'Bank' flagged, but every
         occurrence is inside string literals passed to register(...) (e.g. "Bank of India") --
         this script's string-stripping regex doesn't perfectly handle every string literal
         pattern in this file. Not a real missing import.

  FP-02  backend/src/main/java/com/finora/util/BankRegistry.java: 'Category' flagged, but this
         file declares its OWN nested `Category` enum (BankRegistry.Category) -- the script's
         declaration regex isn't nesting-aware, so it registers this as if it were a second,
         separate top-level declaration of the entity-package `Category` type. Not a real
         missing import.

  FP-03  backend/src/test/java/com/finora/service/StatementImportServiceSummaryTest.java:
         'StatementImportDuplicateCount' flagged, but this file already has a correct explicit
         import -- verify by hand if this ever reappears, it may indicate the checker's import-
         parsing regex missed a formatting variant.

  FP-04  backend/src/test/java/com/finora/controller/AuthFlowIT.java: 'User' flagged, but the
         occurrence is inside a JSON string literal in a request body, not a real code reference.

If you fix the checker's string/comment-stripping to eliminate FP-01/02/04, or its import-parsing
to eliminate FP-03, remove the corresponding entry here. If new false positives appear, add them
here with the same rigor (confirm by hand, then document why) rather than just widening a filter
until the tool goes quiet.
"""
import re
import os
from collections import defaultdict

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BACKEND_SRC = os.path.join(SCRIPT_DIR, "..", "backend", "src")

files = []
for base in ["main/java", "test/java"]:
    for dirpath, _, filenames in os.walk(os.path.join(BACKEND_SRC, base)):
        for fn in filenames:
            if fn.endswith(".java"):
                files.append(os.path.join(dirpath, fn))


def strip_comments_and_strings(text):
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    # Text blocks (Java 15+, """...""") must be stripped before the regular-string regex below --
    # they routinely hold prose that names real com.finora.* types (AssertJ .as("""...""")
    # descriptions are the common case), and without this, the single-line string regex leaves
    # that prose in the body for the reference scan to misread as real code. Matched first and
    # non-greedily so a file with more than one text block doesn't collapse the gap between them.
    text = re.sub(r'"""[\s\S]*?"""', ' ', text)
    text = re.sub(r'"(\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(\\.|[^'\\])'", "''", text)
    return text


file_info = {}
type_decl_locs = defaultdict(list)

pkg_re = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)
import_re = re.compile(r'^\s*import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;', re.M)
topdecl_re = re.compile(
    r'^(?:public|private|protected)?\s*(?:static\s+)?(?:final\s+)?(?:abstract\s+)?(class|interface|enum|record)\s+(\w+)',
    re.M)

for f in files:
    with open(f, encoding='utf-8', errors='replace') as fh:
        raw = fh.read()
    pkg_m = pkg_re.search(raw)
    pkg = pkg_m.group(1) if pkg_m else None
    imports = import_re.findall(raw)
    imp_list = [i[1] for i in imports]
    body = strip_comments_and_strings(raw)
    decls = topdecl_re.findall(raw)
    file_info[f] = dict(pkg=pkg, imports=imp_list, body=body, raw=raw)
    for kind, name in decls:
        if pkg:
            type_decl_locs[name].append((pkg, f))

ambiguous = {n: locs for n, locs in type_decl_locs.items() if len(set(p for p, _ in locs)) > 1}

problems = []
for f, info in file_info.items():
    pkg = info['pkg']
    if pkg is None or not pkg.startswith('com.finora'):
        continue
    imports = info['imports']
    body = info['body']
    wildcard_pkgs = set(i[:-2] for i in imports if i.endswith('.*'))
    explicit_imports = set(imports)
    for name, locs in type_decl_locs.items():
        if name in ambiguous:
            continue
        decl_pkg, decl_file = locs[0]
        if decl_file == f:
            continue
        if decl_pkg == 'com.finora' or not decl_pkg.startswith('com.finora'):
            continue
        if not re.search(r'(?<!\.)\b' + re.escape(name) + r'\b', body):
            continue
        if pkg == decl_pkg:
            continue
        fqn = decl_pkg + '.' + name
        if fqn in explicit_imports:
            continue
        if decl_pkg in wildcard_pkgs:
            continue
        if fqn in info['raw']:
            continue
        problems.append((f, name, decl_pkg))

print(f"Total files scanned: {len(files)}")
print(f"Potential missing-import problems: {len(problems)}")
for f, name, decl_pkg in problems:
    rel = os.path.relpath(f, os.path.join(SCRIPT_DIR, ".."))
    print(f"  {rel}: uses '{name}' (declared in {decl_pkg}) without import")
print("\nCompare against the KNOWN FALSE POSITIVES list in this script's own docstring (FP-01..04)")
print("before treating any of the above as real. If something new shows up, verify it by hand.")
