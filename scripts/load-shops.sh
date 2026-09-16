#!/usr/bin/env bash
# Load generator for shop-service's route query (dev plan DP-15): makes the HorizontalPodAutoscaler in
# k8s/base/services/shop-service-hpa.yaml scale shop-service out, and in again when the load stops.
# Requires: curl, jq. Usage:
#
#   ./scripts/load-shops.sh                       # 8 workers for 180 s against kind (http://localhost:8090)
#   WORKERS=12 DURATION=300 ./scripts/load-shops.sh
#   SHOP_URL=http://localhost:8085/api/shops/graphql ./scripts/load-shops.sh    # compose (no HPA there)
#
# Watch the autoscaler in another terminal:
#   kubectl -n airport get hpa shop-service -w
#   kubectl -n airport get pods -l app.kubernetes.io/name=shop-service -w
#
# Every request is a public `route` query (no token needed) between two random nodes, so each one loads the walkway
# graph and runs Dijkstra - cheap per request, but a few hundred per second keep a 150m-CPU pod busy.
set -euo pipefail

SHOP=${SHOP_URL:-http://localhost:8090/api/shops/graphql}
WORKERS=${WORKERS:-8}
DURATION=${DURATION:-180}

gql() { # gql <query> [variables-json]
  local vars=${2:-'{}'}
  curl -sS -H 'Content-Type: application/json' "$SHOP" \
       --data "$(jq -cn --arg q "$1" --argjson v "$vars" '{query:$q, variables:$v}')"
}

mapfile -t NODES < <(gql '{ navNodes { id } }' | jq -r '.data.navNodes[].id')
if (( ${#NODES[@]} < 2 )); then
  echo "Fandt ingen navigationsnoder på $SHOP - kører stakken?" >&2
  exit 1
fi
echo "== load-shops: $WORKERS workers i $DURATION s mod $SHOP (${#NODES[@]} noder) =="

COUNTS=$(mktemp -d)
trap 'rm -rf "$COUNTS"' EXIT

worker() { # worker <n>: route queries between random nodes until the deadline; writes its count to $COUNTS/<n>
  local n=$1 deadline=$(( $(date +%s) + DURATION )) ok=0 failed=0 body from to
  while (( $(date +%s) < deadline )); do
    from=${NODES[RANDOM % ${#NODES[@]}]}
    to=${NODES[RANDOM % ${#NODES[@]}]}
    body="{\"query\":\"query(\$f:ID!,\$t:ID!){ route(fromNodeId:\$f, toNodeId:\$t){ totalDistanceM steps { instruction } shopsAlongRoute { name } } }\",\"variables\":{\"f\":\"$from\",\"t\":\"$to\"}}"
    if curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data "$body" "$SHOP" \
         2>/dev/null | grep -q '^200$'; then
      ok=$((ok + 1))
    else
      failed=$((failed + 1))
    fi
  done
  echo "$ok $failed" > "$COUNTS/$n"
}

started=$(date +%s)
for i in $(seq 1 "$WORKERS"); do
  worker "$i" &
done

# progress line every 15 s (replicas only when kubectl can see the cluster)
while (( $(date +%s) - started < DURATION )); do
  remaining=$(( DURATION - ($(date +%s) - started) ))
  sleep $(( remaining < 15 ? remaining : 15 ))
  elapsed=$(( $(date +%s) - started ))
  replicas=$(kubectl -n airport get deployment shop-service -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
  hpa=$(kubectl -n airport get hpa shop-service \
          -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || true)
  echo "  ${elapsed}s${replicas:+  ready pods: $replicas}${hpa:+  hpa cpu: ${hpa}% (mål 70 %)}"
done
wait

ok=0; failed=0
for f in "$COUNTS"/*; do
  read -r o fl < "$f"
  ok=$((ok + o)); failed=$((failed + fl))
done
echo "== færdig: $ok requests ok, $failed fejlede, ca. $(( ok / DURATION )) req/s =="
