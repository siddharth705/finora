#!/usr/bin/env python3
"""
Checks every .xml file in the repo for the single most XML-specific footgun: a comment
(<!-- ... -->) containing "--" anywhere in its body. The XML spec forbids this -- "--" is only
legal as the comment's closing delimiter -- and Maven's POM parser (and most XML parsers) reject
the whole file outright when it appears, with an error message that doesn't obviously point at
"a comment used an em-dash."

Written after exactly that bug shipped in backend/pom.xml: comments there used "--" as a plain-
English em-dash substitute (a habit that's completely fine in Java's // comments, which don't
have this restriction) inside <!-- --> blocks, which is invalid XML and broke `mvn` outright with:

    Non-parseable POM: in comment after two dashes (--) next character must be > not ...

Usage:
    python3 scripts/check-xml-comments.py
"""
import re
import os
import glob

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.join(SCRIPT_DIR, "..")

problems = []
for path in glob.glob(os.path.join(REPO_ROOT, "**", "*.xml"), recursive=True):
    if "node_modules" in path or f"{os.sep}target{os.sep}" in path:
        continue
    with open(path, encoding="utf-8", errors="replace") as f:
        content = f.read()
    for m in re.finditer(r"<!--(.*?)-->", content, re.S):
        body = m.group(1)
        if "--" in body:
            line_no = content[: m.start()].count("\n") + 1
            rel = os.path.relpath(path, REPO_ROOT)
            problems.append((rel, line_no))

if not problems:
    print("Clean -- no XML comments contain an internal double-dash.")
else:
    print(f"Found {len(problems)} invalid XML comment(s):")
    for rel, line_no in problems:
        print(f"  {rel}:{line_no} -- comment contains '--' before its closing -->")
    print("\nFix: replace the internal '--' with ',' ';' or rephrase -- only the closing '-->' ")
    print("may contain those two characters together inside an XML comment.")
