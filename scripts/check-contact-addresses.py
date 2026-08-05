#!/usr/bin/env python3
"""Fails when a Finora mailbox is hardcoded in application source instead of imported.

WHY THIS EXISTS
---------------
The domain moved from `finora.app` to `finoratech.info`. The mailto links did not, in six frontend
files at once (`TopBar`, `Help`, `Careers`, `Landing`, `Privacy`, `Terms`), because the address was
typed inline in each one and the migration was a manual grep. Every one of those links was a dead
support channel on a live marketing page: a user clicking "Contact Support" got a bounce.

Five of them were fixed by routing through `frontend/src/lib/contact.ts`. That centralisation was
necessary and not sufficient -- `Careers.tsx` kept its own inline `careers@finora.app` and survived
the cleanup, because nothing failed when one copy was fixed and another was not. Centralising an
address only helps if using it is enforced; otherwise the next person types the literal again and
the next migration misses it again. That is what this checks.

WHAT IT CHECKS
--------------
Any Finora mailbox literal (`<name>@finora.app` or `<name>@finoratech.info`) appearing under a
scanned app's `src/` is an error, with two deliberate exemptions:

1. `frontend/src/lib/contact.ts` -- the single source of truth, the one place the literal belongs.
2. A line carrying `check-contact-addresses: allow`, for prose that must name an address literally
   rather than link it (a legal notice quoting a contact, say).

The OLD domain is flagged wherever it appears -- including inside `contact.ts` -- since after the
migration any `finora.app` mailbox is simply dead. Note this is about *mailboxes*: `com.finora.app`
is the mobile bundle identifier, matches no `@`, and is intentionally untouched.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SCANNED_ROOTS = [
    REPO_ROOT / "frontend" / "src",
    REPO_ROOT / "admin-portal" / "src",
    REPO_ROOT / "mobile" / "src",
]

SOURCE_SUFFIXES = {".ts", ".tsx", ".js", ".jsx"}

# The canonical module is allowed to hold the literal -- that is its entire job.
SOURCE_OF_TRUTH = REPO_ROOT / "frontend" / "src" / "lib" / "contact.ts"

OLD_DOMAIN = "finora.app"
CURRENT_DOMAIN = "finoratech.info"

# A mailbox, not a bare domain: requires the local-part and @ so that `com.finora.app` (the mobile
# bundle identifier) and prose mentions of the domain do not trip the check.
MAILBOX = re.compile(r"[A-Za-z0-9._%+-]+@(?:finora\.app|finoratech\.info)")

ALLOW_MARKER = "check-contact-addresses: allow"


def scan():
    problems = []
    for root in SCANNED_ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
                continue
            is_source_of_truth = path.resolve() == SOURCE_OF_TRUTH.resolve()
            for lineno, line in enumerate(
                path.read_text(encoding="utf-8").splitlines(), start=1
            ):
                if ALLOW_MARKER in line:
                    continue
                # dict, not set: one line usually carries the address twice (href and link text),
                # which is a single problem to fix, not two. Preserves first-seen order.
                for address in dict.fromkeys(m.group(0) for m in MAILBOX.finditer(line)):
                    stale = address.endswith(OLD_DOMAIN)
                    # contact.ts may hold current addresses, but a dead one is still dead there.
                    if is_source_of_truth and not stale:
                        continue
                    problems.append((path, lineno, address, stale))
    return problems


def main():
    problems = scan()
    if not problems:
        print("Clean -- no hardcoded Finora mailboxes outside frontend/src/lib/contact.ts.")
        return 0

    print("BLOCKED: Finora mailbox addresses must come from frontend/src/lib/contact.ts.\n")
    for path, lineno, address, stale in problems:
        rel = path.relative_to(REPO_ROOT)
        reason = (
            f"dead address -- {OLD_DOMAIN} was migrated to {CURRENT_DOMAIN}"
            if stale
            else "hardcoded copy -- import SUPPORT_EMAIL/CAREERS_EMAIL instead"
        )
        print(f"  {rel}:{lineno}: {address}  ({reason})")

    print(
        "\nImport the address instead:\n"
        "    import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';\n"
        "    <a href={SUPPORT_MAILTO}>{SUPPORT_EMAIL}</a>\n"
        "\nAdd a new mailbox to contact.ts rather than typing it inline. If a line genuinely must\n"
        f"spell out an address, mark it with: {ALLOW_MARKER}"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
