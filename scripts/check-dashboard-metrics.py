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
Every `finora_<domain>_*` token appearing in ops/monitoring (alert expressions, dashboard queries)
resolves to a meter the framework actually registers, after applying Micrometer's Prometheus naming
rules. This covers every domain under the observability package -- currently `worker` (see
`WorkerObservability.java`) and `reconciliation` (see `ReconciliationMetrics.java`) -- by deriving
domain and name from the source rather than hard-coding one domain's prefix.

It deliberately does NOT check non-Finora metrics (`jvm_*`, `hikaricp_*`, `up`). Those come from
Micrometer's own binders rather than from this codebase, so their names cannot be derived from our
source -- asserting them here would mean hard-coding a second list that could drift from the first.
"""

import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OPS_DIR = REPO_ROOT / "ops" / "monitoring"
FRAMEWORK = REPO_ROOT / "backend" / "src" / "main" / "java" / "com" / "finora" / "observability"

# WorkerObservability registers counters through a private counter(name, ...) helper that builds
# "finora.worker." + name inline, so no single quoted literal names the full metric -- this regex
# recovers those. Every other domain (e.g. ReconciliationMetrics) writes the full dotted name
# directly into Counter.builder(...)/Timer.builder(...)/Gauge.builder(...), which FULL_NAME below
# already catches; WORKER_HELPER_COUNTER exists only for this one legacy pattern.
WORKER_HELPER_COUNTER = re.compile(r'counter\("([a-z_]+)"')
# A full "finora.<domain>.<name>" literal, however it is built (Counter/Timer/Gauge.builder, or a
# literal argument to WorkerObservability's helper's Counter.builder call).
FULL_NAME = re.compile(r'"(finora\.[a-z]+\.[a-z_]+)"')


def emitted_series():
    """The Prometheus series names the framework can produce, across every observability domain.

    Mirrors Micrometer's Prometheus naming: dots to underscores, counters suffixed `_total`, timers
    expanded to the three histogram series, and a declared baseUnit appended to a gauge's name.
    """
    source = "\n".join(p.read_text(encoding="utf-8") for p in FRAMEWORK.glob("*.java"))

    full_names = set(FULL_NAME.findall(source))
    full_names |= {f"finora.worker.{c}" for c in WORKER_HELPER_COUNTER.findall(source)}

    # Every full name is declared however it is built; classify by which builder call carries it.
    timers = {n for n in full_names if f'Timer.builder("{n}"' in source}
    gauges = {n for n in full_names if f'Gauge.builder("{n}"' in source}
    counters = full_names - timers - gauges

    series = set()
    for c in counters:
        prefix = c.replace(".", "_")
        series.add(f"{prefix}_total")
        series.add(prefix)            # Prometheus also exposes the un-suffixed form
    for t in timers:
        prefix = t.replace(".", "_")
        for suffix in ("_seconds_bucket", "_seconds_count", "_seconds_sum", "_seconds_max", "_seconds"):
            series.add(f"{prefix}{suffix}")
        series.add(prefix)
    for g in gauges:
        prefix = g.replace(".", "_")
        series.add(prefix)
        # A declared baseUnit becomes part of the exported name.
        if '.baseUnit("seconds")' in source:
            series.add(f"{prefix}_seconds")
    return series, counters, timers, gauges


def _display_path(path: Path) -> str:
    """path relative to the repo root when possible, else its plain string -- the self-test points
    OPS_DIR at a temp directory that isn't under REPO_ROOT at all."""
    try:
        return path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return str(path)


def referenced():
    """Every finora_<domain>_* token used anywhere under ops/monitoring, with its source file."""
    # Must end on a letter, not an underscore -- excludes prose like "finora_worker_*" in a comment,
    # which is a wildcard illustration, not a real series name.
    token = re.compile(r"finora_[a-z_]*[a-z]")
    found = {}
    for path in sorted(OPS_DIR.rglob("*")):
        if path.suffix not in {".yml", ".yaml", ".json"} or not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        # JSON is read as text on purpose: queries live in nested string fields and walking the
        # structure would mean tracking Grafana's schema, which changes between versions.
        for name in token.findall(text):
            found.setdefault(name, set()).add(_display_path(path))
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
    # The only third-party import in scripts/. Declared in scripts/requirements.txt, and installed
    # by CI -- but the pre-commit hook runs whatever python3 is on PATH, which may not have it. A
    # bare ModuleNotFoundError traceback here reads as "the guard is broken" rather than "install
    # one package", and it appears at commit time when nobody wants to read a stack trace.
    try:
        import yaml
    except ModuleNotFoundError:
        raise SystemExit(
            "check-dashboard-metrics needs PyYAML, which is not installed for this interpreter:\n"
            f"    {sys.executable}\n"
            "\n"
            "Install it with:\n"
            "    pip install -r scripts/requirements.txt\n"
            "\n"
            "Note that `python3` may resolve to a different interpreter than you expect -- run\n"
            "`which -a python3` if the install appears to succeed and this message persists."
        ) from None

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


def _write_framework(content: str) -> None:
    FRAMEWORK.mkdir(parents=True, exist_ok=True)
    for old in FRAMEWORK.glob("*.java"):
        old.unlink()
    (FRAMEWORK / "Synthetic.java").write_text(content, encoding="utf-8")


def self_test() -> int:
    """Run against a synthetic framework/ops tree instead of this repo's real one.

    Asserts the check still catches an unknown-metric reference, still refuses to pass vacuously
    when either side has nothing to check, and still catches a runbook/alert mismatch in both
    directions -- a check nobody has falsified is a check nobody knows is running.
    """
    global OPS_DIR, FRAMEWORK, RUNBOOK
    original_ops, original_framework, original_runbook = OPS_DIR, FRAMEWORK, RUNBOOK
    tmp = Path(tempfile.mkdtemp(prefix="dashboard-metrics-selftest-"))
    try:
        FRAMEWORK = tmp / "observability"
        OPS_DIR = tmp / "ops"
        RUNBOOK = tmp / "observability.md"
        OPS_DIR.mkdir(parents=True, exist_ok=True)

        clean_framework = """
package com.finora.observability;

class Synthetic {
    void register() {
        Counter.builder("finora.demo.things_done").register(registry).increment();
        Timer.builder("finora.demo.duration").register(registry);
        Gauge.builder("finora.demo.queue_depth", this, x -> 1.0)
                .baseUnit("seconds")
                .register(registry);
    }
}
"""

        def write_dashboard(exprs):
            (OPS_DIR / "dashboard.json").write_text(json.dumps({
                "panels": [{"targets": [{"expr": e} for e in exprs]}]
            }), encoding="utf-8")

        def write_alerts(rules_yaml):
            rules = f"\n{rules_yaml}" if rules_yaml else " []"
            (OPS_DIR / "alerts.yml").write_text(f"groups:\n  - name: demo\n    rules:{rules}\n",
                                                 encoding="utf-8")

        # Case 1: every referenced series is one the fixture framework emits -- must pass clean.
        _write_framework(clean_framework)
        write_alerts("")
        write_dashboard([
            "rate(finora_demo_things_done_total[5m])",
            "histogram_quantile(0.95, finora_demo_duration_seconds_bucket)",
            "finora_demo_queue_depth",
        ])
        RUNBOOK.write_text("# Observability\n", encoding="utf-8")
        assert run_check() == 0, "case 1 (every referenced series is emitted) should pass clean"

        # Case 2: a dashboard references a metric the framework never emits -- must block.
        write_dashboard(["finora_demo_typo_total"])
        assert run_check() == 1, "case 2 (unknown metric referenced) must block"

        # Case 3: the framework emits nothing derivable -- must refuse to pass vacuously.
        _write_framework("package com.finora.observability;\nclass Empty {}\n")
        assert run_check() == 1, "case 3 (no metrics derivable from source) must block, not pass vacuously"

        # Case 4: framework is fine again, but nothing in ops/monitoring references it -- also vacuous.
        _write_framework(clean_framework)
        write_dashboard([])
        assert run_check() == 1, "case 4 (no finora_* metric referenced anywhere) must block"

        # Case 5: an alert with no matching runbook section -- must block.
        write_dashboard(["finora_demo_queue_depth"])
        write_alerts(
            "      - alert: DemoQueueStuck\n"
            "        annotations:\n"
            "          runbook_url: https://example/observability.md#demoqueuestuck\n"
        )
        assert run_check() == 1, "case 5 (alert with no runbook section) must block"

        # Case 6: the runbook section exists and matches the alert -- must pass again.
        RUNBOOK.write_text("# Observability\n\n### DemoQueueStuck\n", encoding="utf-8")
        assert run_check() == 0, "case 6 (alert and runbook section match) should pass clean"

        # Case 7: a runbook section names no alert (renamed or removed) -- the other drift direction.
        RUNBOOK.write_text("# Observability\n\n### DemoQueueStuck\n\n### GhostAlert\n", encoding="utf-8")
        assert run_check() == 1, "case 7 (orphan runbook section) must block"

        print("self-test: all 7 cases passed (clean match, unknown metric blocked, vacuous framework "
              "blocked, vacuous references blocked, orphan alert blocked, matched runbook passes, "
              "orphan runbook section blocked)")
        return 0
    except AssertionError as exc:
        print(f"SELF-TEST FAILED: {exc}", file=sys.stderr)
        return 1
    finally:
        OPS_DIR, FRAMEWORK, RUNBOOK = original_ops, original_framework, original_runbook
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--self-test" in sys.argv[1:]:
        return self_test()
    return run_check()


def run_check():
    if not OPS_DIR.is_dir():
        print(f"No {_display_path(OPS_DIR)} directory -- nothing to check.")
        return 0

    series, counters, timers, gauges = emitted_series()
    if not series:
        print("BLOCKED: could not derive any metric names from the framework source.")
        print("Either the observability package moved or the builders changed shape; this check is")
        print("now vacuous and would pass against any dashboard at all.")
        return 1

    used = referenced()
    if not used:
        print("BLOCKED: no finora_* metrics referenced anywhere in ops/monitoring.")
        print("The dashboards and alerts are not actually querying this application.")
        return 1

    unknown = {name: files for name, files in used.items() if name not in series}

    print(f"Framework emits {len(counters)} counters, {len(timers)} timers, {len(gauges)} gauges.")
    print(f"ops/monitoring references {len(used)} distinct finora_* series.")

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
