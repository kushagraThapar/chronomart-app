#!/usr/bin/env bash
# Quick health-check for the ChronoMart docker-compose stack.
set -euo pipefail

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$*"; }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$*"; }

check() {
  local name=$1 url=$2
  if curl -fsS --max-time 5 "$url" >/dev/null; then ok "$name ($url)"; else bad "$name ($url)"; return 1; fi
}

bold "ChronoMart stack health"
check "Cosmos emulator (ready)" "http://localhost:8080/ready"
check "Cosmos emulator (alive)" "http://localhost:8080/alive"
check "Gateway healthz"         "http://localhost:8000/healthz"
check "Gateway metrics"         "http://localhost:8000/metrics"
check "Prometheus"              "http://localhost:9090/-/healthy"
check "Grafana"                 "http://localhost:3000/api/health"
check "OTel collector metrics"  "http://localhost:8889/metrics"

echo
bold "Capability manifests"
for sdk in java dotnet python rust go; do
  printf "  %-7s -> " "$sdk"
  curl -fsS --max-time 5 -H "X-Cosmos-SDK: $sdk" \
    http://localhost:8000/api/v1/_meta/capabilities | head -c 120
  echo " …"
done
