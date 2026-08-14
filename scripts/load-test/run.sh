#!/usr/bin/env bash
# Runs one load-test tier and captures DB/memory metrics alongside k6's own output.
#
# Usage: scripts/load-test/run.sh <vus> [duration]
#   scripts/load-test/run.sh 100
#   scripts/load-test/run.sh 500 90s
#
# Writes results under scripts/load-test/results/<vus>-users/.
set -euo pipefail

VUS="${1:?usage: run.sh <vus> [duration]}"
DURATION="${2:-60s}"
OUT_DIR="$(dirname "$0")/results/${VUS}-users"
mkdir -p "$OUT_DIR"

echo "=== Load test: ${VUS} VUs for ${DURATION} ==="

# Sample container memory + Postgres connection/activity state every 2s for the duration of the
# k6 run. Backgrounded, killed when k6 exits.
(
  echo "timestamp,backend_mem_mib,backend_cpu_pct,postgres_active_conns,postgres_idle_conns,postgres_waiting_conns" > "$OUT_DIR/resource-samples.csv"
  while true; do
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    mem_raw=$(docker stats finora-backend-1 --no-stream --format "{{.MemUsage}}" 2>/dev/null | awk '{print $1}')
    cpu_raw=$(docker stats finora-backend-1 --no-stream --format "{{.CPUPerc}}" 2>/dev/null | tr -d '%')
    mem_mib=$(echo "$mem_raw" | sed -E 's/([0-9.]+)([A-Za-z]+)/\1 \2/' | awk '{if ($2 ~ /GiB/) print $1*1024; else print $1}')
    conns=$(docker exec finora-postgres-1 psql -U finora -d finora -t -c \
      "SELECT state, count(*) FROM pg_stat_activity WHERE datname='finora' GROUP BY state;" 2>/dev/null || echo "")
    active=$(echo "$conns" | grep -c "active" || true)
    idle=$(echo "$conns" | grep -c "idle" || true)
    waiting=$(docker exec finora-postgres-1 psql -U finora -d finora -t -c \
      "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock';" 2>/dev/null | tr -d ' ' || echo "0")
    echo "${ts},${mem_mib:-0},${cpu_raw:-0},${active:-0},${idle:-0},${waiting:-0}" >> "$OUT_DIR/resource-samples.csv"
    sleep 2
  done
) &
MONITOR_PID=$!
trap 'kill $MONITOR_PID 2>/dev/null || true' EXIT

k6 run \
  --env VUS="$VUS" \
  --env TEST_DURATION="$DURATION" \
  --summary-export "$OUT_DIR/k6-summary.json" \
  "$(dirname "$0")/loadtest.js" \
  | tee "$OUT_DIR/k6-output.txt"

kill "$MONITOR_PID" 2>/dev/null || true
trap - EXIT

echo ""
echo "=== Resource samples (${OUT_DIR}/resource-samples.csv) ==="
python3 - "$OUT_DIR/resource-samples.csv" <<'EOF'
import csv
import sys

path = sys.argv[1]
mems, cpus, actives, waitings = [], [], [], []
with open(path) as f:
    for row in csv.DictReader(f):
        try:
            mems.append(float(row["backend_mem_mib"]))
            cpus.append(float(row["backend_cpu_pct"]))
            actives.append(int(row["postgres_active_conns"]))
            waitings.append(int(row["postgres_waiting_conns"]))
        except (ValueError, KeyError):
            continue

def summarize(label, values, unit=""):
    if not values:
        print(f"{label}: no samples")
        return
    print(f"{label}: min={min(values):.1f}{unit} avg={sum(values)/len(values):.1f}{unit} max={max(values):.1f}{unit}")

summarize("Backend memory", mems, " MiB")
summarize("Backend CPU", cpus, "%")
summarize("Postgres active connections", actives)
summarize("Postgres lock-waiting connections", waitings)
EOF

echo ""
echo "Results written to ${OUT_DIR}/"
