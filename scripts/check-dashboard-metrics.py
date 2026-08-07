#!/usr/bin/env python3
"""Fails when a dashboard or alert references a metric the application never emits.

WHY THIS EXISTS
---------------
A Grafana panel querying a metric name that does not exist renders an empty graph. An alert rule
referencing one never fires. Both look *exactly* like a healthy system: no error, no warning,
nothing red. That is the same failure shape this repository has already been bitten by four times --
a script that always exited 0, a suite that never ran, 29 IT classes that never matched, an audit
stream trained to be ignored -- except here the thing that silently does nothing is the alert meant
to tell you production is broken.

The risk is not hypothetical. Micrometer renames metrics on the way out: dots become underscores,
counters gain a `_total` suffix, timers expand into `_seconds_bucket` / `_seconds_count` /
`_seconds_sum`, and a `baseUnit` becomes part of the name. So the string a dashboard must use is
never the string the Java code contains, and nothing else checks that the translation is right.

WHAT IT CHECKS
--------------
Every `finora_worker_*` token appearing in ops/monitoring (alert expressions, dashboard queries)
resolves to a meter the framework actually registers, after applying Micrometer's Prometheus naming
rules.

It deliberately does NOT check non-Finora metrics (`jvm_*`, `hikaricp_*`, `up`). Those come from
Micrometer's own binders rather than from this codebase, so their names cannot be derived from our
source -- asserting them here would mean hard-coding a second list that could drift from the first.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OPS_DIR = REPO_ROOT / "ops" / "monitoring"
FRAMEWORK = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "finora" / "observability"

PREFIX = "finora_worker_"

# Counters registered via the private counter(name, ...) helper, plus the ones built inline.
COUNTER_NAME = re.compile(r'counter\("([a-z_]+)"')
# Timers and gauges are built with a full metric name.
FULL_NAME = re.compile(r'"(finora\.worker\.[a-z_]+)"')


def emitted_series():
    """The Prometheus series names the framework can produce.

    Mirrors Micrometer's Prometheus naming: dots to underscores, counters suffixed `_total`, timers
    expanded to the three histogram series, and a declared baseUnit appended to a gauge's name.
    """
    source = "\n".join(p.read_text(encoding="utf-8") for p in FRAMEWORK.glob("*.java"))

    counters = set(COUNTER_NAME.findall(source))
    full = {n.replace("finora.worker.", "") for n in FULL_NAME.findall(source)}

    # Timers and gauges are declared with their full name; separate them by how they are built.
    timers = {n for n in full if f'Timer.builder("finora.worker.{n}"' in source}
    gauges = {n for n in full if f'Gauge.builder("finora.worker.{n}"' in source}

    series = set()
    for c in counters:
        series.add(f"{PREFIX}{c}_total")
        series.add(f"{PREFIX}{c}")            # Prometheus also exposes the un-suffixed form
    for t in timers:
        for suffix in ("_seconds_bucket", "_seconds_count", "_seconds_sum", "_seconds_max", "_seconds"):
            series.add(f"{PREFIX}{t}{suffix}")
        series.add(f"{PREFIX}{t}")
    for g in gauges:
        series.add(f"{PREFIX}{g}")
        # A declared baseUnit becomes part of the exported name.
        if f'.baseUnit("seconds")' in source:
            series.add(f"{PREFIX}{g}_seconds")
    return series, counters, timers, gauges


def referenced():
    """Every finora_worker_* token used anywhere under ops/monitoring, with its source file."""
    token = re.compile(r"finora_worker_[a-z_]+")
    found = {}
    for path in sorted(OPS_DIR.rglob("*")):
        if path.suffix not in {".yml", ".yaml", ".json"} or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        # JSON is read as text on purpose: queries live in nested string fields and walking the
        # structure would mean tracking Grafana's schema, which changes between versions.
        for name in token.findall(text):
            found.setdefault(name, set()).add(path.relative_to(REPO_ROOT).as_posix())
    return found


RUNBOOK = REPO_ROOT / "docs" / "engineering" / "observability.md"


def check_runbooks():
    """Every alert must have a runbook section, and every section must belong to an alert.

    An alert that fires at 3am with no runbook is an alert someone has to reverse-engineer under
    pressure. A runbook section for an alert that no longer exists is worse: it is confidently
    wrong, and nothing about reading it reveals that.

    Both directions are checked because they drift in both directions -- an alert gets added
    without a section, or an alert gets renamed and the section is orphaned.
    """
    import yaml

    alerts_file = OPS_DIR / "alerts.yml"
    if not alerts_file.is_file() or not RUNBOOK.is_file():
        return []

    rules = []
    for group in yaml.safe_load(alerts_file.read_text(encoding="utf-8")).get("groups", []):
        rules.extend(group.get("rules", []))

    runbook_text = RUNBOOK.read_text(encoding="utf-8")
    # Sections are named exactly after the alert, which is what makes runbook_url anchors work.
    sections = set(re.findall(r"^### (\w+)$", runbook_text, re.M))

    problems = []
    for rule in rules:
        name = rule.get("alert")
        if name not in sections:
            problems.append(f"alert {name} has no '### {name}' section in observability.md")
        url = rule.get("annotations", {}).get("runbook_url", "")
        if not url:
            problems.append(f"alert {name} has no runbook_url annotation")
        elif not url.rstrip().endswith("#" + name.lower()):
            problems.append(f"alert {name} runbook_url points at '{url}', not #{name.lower()}")

    alert_names = {r.get("alert") for r in rules}
    for section in sections - alert_names:
        problems.append(f"runbook section '### {section}' names no alert -- renamed or removed?")

    return problems


def main():
    if not OPS_DIR.is_dir():
        print(f"No {OPS_DIR.relative_to(REPO_ROOT)} directory -- nothing to check.")
        return 0

    series, counters, timers, gauges = emitted_series()
    if not series:
        print("BLOCKED: could not derive any metric names from the framework source.")
        print("Either the observability package moved or the builders changed shape; this check is")
        print("now vacuous and would pass against any dashboard at all.")
        return 1

    used = referenced()
    if not used:
        print("BLOCKED: no finora_worker_* metrics referenced anywhere in ops/monitoring.")
        print("The dashboards and alerts are not actually querying this application.")
        return 1

    unknown = {name: files for name, files in used.items() if name not in series}

    print(f"Framework emits {len(counters)} counters, {len(timers)} timers, {len(gauges)} gauges.")
    print(f"ops/monitoring references {len(used)} distinct finora_worker_* series.")

    if unknown:
        print("\nBLOCKED: a dashboard or alert references a metric that is never emitted.\n")
        for name, files in sorted(unknown.items()):
            print(f"  {name}")
            for f in sorted(files):
                print(f"      used in {f}")
        print(
            "\nAn empty panel and a healthy system look identical, and an alert on a non-existent\n"
            "series never fires. Check Micrometer's Prometheus naming: dots become underscores, a\n"
            "counter gains _total, a timer becomes _seconds_bucket/_count/_sum, and a declared\n"
            "baseUnit is appended to the name."
        )
        return 1

    runbook_problems = check_runbooks()
    if runbook_problems:
        print("\nBLOCKED: alerts and their runbooks have drifted apart.\n")
        for problem in runbook_problems:
            print(f"  {problem}")
        print(
            "\nAn alert firing at 3am with no runbook is one somebody has to reverse-engineer under\n"
            "pressure. A runbook section for an alert that no longer exists is worse: confidently\n"
            "wrong, with nothing about reading it revealing that."
        )
        return 1

    print("\nClean -- every referenced metric is one the framework emits,")
    print("         and every alert has a runbook section it actually links to.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
