#!/usr/bin/env bash
# Quick health-check for the ChronoMart docker-compose stack.
#
# Exit codes:
#   0 — every required ("core") endpoint responds AND the implemented SDK
#       capability manifests fetch OK.
#   1 — one or more core endpoints failed, or an implemented SDK manifest
#       could not be fetched.
#
# Stub SDK backends (dotnet/python/rust/go in Phase 1) are *not* required to
# answer — the gateway proxies them, and on a fresh stack the proxy call to a
# non-existent backend will time out. We probe them best-effort and only fail
# the script if a backend is *expected* to be live but isn't responding.
#
# Override the implemented-SDK list with IMPLEMENTED_SDKS="java dotnet" etc.
# as more backends come online.

set -uo pipefail

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$*"; }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$*"; }
warn() { printf "  \033[33m!\033[0m %s\n" "$*"; }

CORE_FAILED=0
SDK_FAILED=0

check() {
  local name=$1 url=$2
  if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
    ok "$name ($url)"
  else
    bad "$name ($url)"
    CORE_FAILED=$((CORE_FAILED + 1))
  fi
}

# SDKs whose backends are deployed in the current compose stack. Phase 1 ships
# Java only; the others are tracked as stubs at the gateway. Override via env.
IMPLEMENTED_SDKS=${IMPLEMENTED_SDKS:-"java"}
ALL_SDKS=${ALL_SDKS:-"java dotnet python rust go"}

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
for sdk in $ALL_SDKS; do
  printf "  %-7s -> " "$sdk"
  body=$(curl -fsS --max-time 5 -H "X-Cosmos-SDK: $sdk" \
    http://localhost:8000/api/v1/_meta/capabilities 2>/dev/null || true)
  if [ -n "$body" ]; then
    printf "%s …\n" "$(printf '%s' "$body" | head -c 120)"
  else
    is_impl=0
    for impl in $IMPLEMENTED_SDKS; do
      [ "$impl" = "$sdk" ] && is_impl=1
    done
    if [ "$is_impl" = "1" ]; then
      printf "\n"
      bad "  implemented SDK '$sdk' did not respond"
      SDK_FAILED=$((SDK_FAILED + 1))
    else
      printf "(stub backend unreachable — expected)\n"
    fi
  fi
done

echo
if [ "$CORE_FAILED" -eq 0 ] && [ "$SDK_FAILED" -eq 0 ]; then
  ok "All core checks passed."
  exit 0
fi
warn "Core failures: $CORE_FAILED, implemented-SDK failures: $SDK_FAILED"
exit 1
