#!/usr/bin/env bash
#
# oracle-gate.sh — run a verification workload and FAIL (non-zero exit) when the workload
# oracle reports correctness anomalies above a threshold. Use as a CI correctness gate.
#
# Usage:
#   scripts/oracle-gate.sh [BASE_URL] [PRESET_JSON]
#
# Env:
#   SDK         X-Cosmos-SDK header (default: java)
#   THRESHOLD   max ERROR anomalies tolerated (default: 0)
#   TIMEOUT     seconds to wait for the run to finish (default: 120)
#   ANALYZER_JAR  optional path to the backend jar; if set, also downloads the op history
#                 and runs the offline linearizability analyzer as a second, independent check.
#
# Exit codes: 0 = clean, 1 = anomalies/violations over threshold, 2 = operational error.
set -euo pipefail

BASE="${1:-http://localhost:8000/api/v1}"
PRESET="${2:-infra/workloads/verify-register.json}"
SDK="${SDK:-java}"
THRESHOLD="${THRESHOLD:-0}"
TIMEOUT="${TIMEOUT:-120}"

hdr=(-H "X-Cosmos-SDK: ${SDK}" -H "Content-Type: application/json")

[ -f "$PRESET" ] || { echo "preset not found: $PRESET" >&2; exit 2; }

echo "oracle-gate: starting '$PRESET' against $BASE (sdk=$SDK, threshold=$THRESHOLD)"
run_id=$(curl -fsS "${hdr[@]}" --data-binary @"$PRESET" "$BASE/workloads/run" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['runId'])") \
  || { echo "failed to start workload" >&2; exit 2; }
echo "oracle-gate: runId=$run_id"

# Poll until the run leaves RUNNING (COMPLETED / FAILED / STOPPED) or we time out.
deadline=$(( $(date +%s) + TIMEOUT ))
status="RUNNING"
progress=""
while :; do
  progress=$(curl -fsS "${hdr[@]}" "$BASE/workloads/$run_id") || { echo "poll failed" >&2; exit 2; }
  status=$(echo "$progress" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
  [ "$status" = "RUNNING" ] || [ "$status" = "PENDING" ] || break
  [ "$(date +%s)" -lt "$deadline" ] || { echo "oracle-gate: timed out after ${TIMEOUT}s" >&2; exit 2; }
  sleep 2
done
echo "oracle-gate: run status=$status"
[ "$status" = "COMPLETED" ] || { echo "oracle-gate: FAIL — terminal status is $status" >&2; exit 2; }

read -r total errors warns <<<"$(echo "$progress" | python3 -c "
import json,sys
r=json.load(sys.stdin); s=r.get('anomalySummary')
print((s['total'] if s else 0), (s['errorCount'] if s else 0), (s['warnCount'] if s else 0))
")"
echo "oracle-gate: anomalies total=$total errors=$errors warns=$warns"

rc=0

# Optional second opinion: the offline linearizability analyzer over the downloaded history.
if [ -n "${ANALYZER_JAR:-}" ]; then
  hist=$(mktemp)
  off=0; page=0
  : > "$hist.parts"
  while :; do
    chunk=$(curl -fsS "${hdr[@]}" "$BASE/workloads/$run_id/history?offset=$off&limit=5000")
    n=$(echo "$chunk" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")
    [ "$n" -gt 0 ] || break
    echo "$chunk" >> "$hist.parts"
    off=$(( off + n )); page=$(( page + 1 ))
    [ "$n" -lt 5000 ] && break
  done
  python3 -c "
import json,sys
out=[]
for line in open('$hist.parts'):
    line=line.strip()
    if line: out.extend(json.loads(line))
json.dump(out, open('$hist','w'))
print('oracle-gate: downloaded', len(out), 'history records')
"
  echo "oracle-gate: running offline analyzer..."
  java -cp "$ANALYZER_JAR" -Dloader.main=com.chronomart.oracle.OfflineHistoryAnalyzer \
    org.springframework.boot.loader.launch.PropertiesLauncher "$hist" || rc=1
  rm -f "$hist" "$hist.parts"
fi

if [ "$errors" -gt "$THRESHOLD" ]; then
  echo "oracle-gate: FAIL — $errors ERROR anomalies > threshold $THRESHOLD" >&2
  exit 1
fi
[ "$rc" -eq 0 ] || { echo "oracle-gate: FAIL — offline analyzer found violations" >&2; exit 1; }
echo "oracle-gate: PASS"
