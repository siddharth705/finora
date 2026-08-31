#!/usr/bin/env python3
"""Fails when a Fynora mailbox is hardcoded in application source instead of imported.

WHY THIS EXISTS
---------------
The domain moved from `finora.app` to `finoratech.info`, and mailto links did not, in six frontend
files at once (`TopBar`, `Help`, `Careers`, `Landing`, `Privacy`, `Terms`), because the address was
typed inline in each one and the migration was a manual grep. Every one of those links was a dead
support channel on a live marketing page: a user clicking "Contact Support" got a bounce.

Five of them were fixed by routing through `frontend/src/lib/contact.ts`. That centralisation was
necessary and not sufficient -- `Careers.tsx` kept its own inline `careers@finora.app` and survived
the cleanup, because nothing failed when one copy was fixed and another was not. Centralising an
address only helps if using it is enforced; otherwise the next person types the literal again and
the next migration misses it again. That is what this checks.

The domain has since moved a second time, `finoratech.info` -> `fynora.net`. Unlike the first move,
`finoratech.info` mailboxes are being kept alive with forwarding during the transition rather than
going dead outright -- but new code should still only ever reference the current domain, so both
retired domains are flagged the same way: a hardcoded literal anywhere is a bug, whether or not the
address behind it still receives mail.

WHAT IT CHECKS
--------------
Any Fynora mailbox literal (`<name>@finora.app`, `<name>@finoratech.info`, or
`<name>@fynora.net`) appearing under a scanned app's `src/` is an error, with two deliberate
exemptions:

1. `frontend/src/lib/contact.ts` -- the single source of truth, the one place a *current*-domain
   literal belongs. A retired-domain literal is still an error even there.
2. A line carrying `check-contact-addresses: allow`, for prose that must name an address literally
   rather than link it (a legal notice quoting a contact, say).

Note this is about *mailboxes*: the mobile bundle identifier (`com.fynora.app`, renamed from
`com.finora.app` on both platforms after an Apple Developer registration collision, unrelated to
either mailbox migration) matches no `@` and is intentionally untouched.
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

# The canonical module is allowed to hold the current-domain literal -- that is its entire job.
SOURCE_OF_TRUTH = REPO_ROOT / "frontend" / "src" / "lib" / "contact.ts"

# Every domain the mailboxes have lived at, oldest first, and the one they live at now.
RETIRED_DOMAINS = ("finora.app", "finoratech.info")
CURRENT_DOMAIN = "fynora.net"

# A mailbox, not a bare domain: requires the local-part and @ so that `com.fynora.app` (the
# mobile bundle identifier) and prose mentions of a domain do not trip the check.
ALL_DOMAINS = RETIRED_DOMAINS + (CURRENT_DOMAIN,)
MAILBOX = re.compile(
    r"[A-Za-z0-9._%+-]+@(?:" + "|".join(re.escape(d) for d in ALL_DOMAINS) + r")"
)

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
                    retired_domain = next(
                        (d for d in RETIRED_DOMAINS if address.endswith(d)), None
                    )
                    # contact.ts may hold current-domain addresses, but a retired one is still an
                    # error there too.
                    if is_source_of_truth and retired_domain is None:
                        continue
                    problems.append((path, lineno, address, retired_domain))
    return problems


def main():
    problems = scan()
    if not problems:
        print("Clean -- no hardcoded Fynora mailboxes outside frontend/src/lib/contact.ts.")
        return 0

    print("BLOCKED: Fynora mailbox addresses must come from frontend/src/lib/contact.ts.\n")
    for path, lineno, address, retired_domain in problems:
        rel = path.relative_to(REPO_ROOT)
        reason = (
            f"retired domain -- {retired_domain} was migrated to {CURRENT_DOMAIN}"
            if retired_domain
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
