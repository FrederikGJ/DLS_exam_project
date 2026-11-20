#!/usr/bin/env bash
# Starts the whole airport stack on a local kind cluster in one command (the kind steps from k8s/README.md).
# Safe to run again: an existing cluster is reused and every manifest is applied idempotently.
# Requires: kind, kubectl, docker. Usage (from any directory):
#
#   scripts/kind-up.sh                   # cluster + ingress-nginx + the standard stack (k8s/)
#   scripts/kind-up.sh --build           # docker compose build first (needed after code changes)
#   scripts/kind-up.sh --ai              # + Ollama for shop-service's askRoute (k8s/ai)
#   scripts/kind-up.sh --observability   # + Prometheus, Grafana, Loki, Alloy (k8s/observability)
#   scripts/kind-up.sh --tools           # + pgAdmin on /pgadmin/ (k8s/tools)
#
# Flags can be combined. Frontend: http://localhost:8090/ (anna/anna, ops/ops). Stop: kind delete cluster --name airport
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=airport
IMAGES=(flight-service booking-service payment-service baggage-service shop-service frontend)
INGRESS_NGINX=https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

build=false
addons=()
for arg in "$@"; do
  case $arg in
    --build)         build=true ;;
    --ai)            addons+=(k8s/ai) ;;
    --observability) addons+=(k8s/observability) ;;
    --tools)         addons+=(k8s/tools) ;;
    -h|--help)       sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)               echo "unknown option: $arg (see --help)" >&2; exit 1 ;;
  esac
done

k() { kubectl --context "kind-$CLUSTER" "$@"; }   # always the kind cluster, whatever context is current

apply_kustomize() { # apply_kustomize <dir> - retries while ingress-nginx' admission webhook is still starting
  local attempt
  for attempt in 1 2 3 4 5 6; do
    k apply -k "$1" && return 0
    echo "    apply failed (attempt $attempt/6) - retrying in 10 s" >&2
    sleep 10
  done
  return 1
}

if [[ $build == true ]]; then
  echo "==> Building images (docker compose build)"
  docker compose build
fi
for img in "${IMAGES[@]}"; do
  docker image inspect "airport/$img:local" >/dev/null 2>&1 \
    || { echo "Image airport/$img:local is missing - run again with --build" >&2; exit 1; }
done

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "==> kind cluster '$CLUSTER' already exists - reusing it"
else
  echo "==> Creating kind cluster '$CLUSTER'"
  kind create cluster --config k8s/kind-config.yaml
fi

echo "==> Loading the local images into the kind node"
for img in "${IMAGES[@]}"; do
  kind load docker-image "airport/$img:local" --name "$CLUSTER"
done

echo "==> Installing ingress-nginx"
k apply -f "$INGRESS_NGINX"
k -n ingress-nginx rollout status deploy/ingress-nginx-controller --timeout=180s

echo "==> Deploying the airport stack (k8s/)"
apply_kustomize k8s/
for dir in ${addons[@]+"${addons[@]}"}; do
  echo "==> Deploying add-on $dir"
  apply_kustomize "$dir"
done

# Ollama is left out of the wait: its first start pulls a multi-GB image plus the model and can take far longer.
echo "==> Waiting until every Deployment is Available (first start takes a few minutes; Ctrl-C is safe)"
k -n airport wait --for=condition=Available deployment -l 'app.kubernetes.io/name!=ollama' --timeout=10m

echo
echo "Ready: http://localhost:8090/  (log in as anna/anna or ops/ops)"
for dir in ${addons[@]+"${addons[@]}"}; do
  case $dir in
    k8s/ai)            echo "Ollama: still pulling image/model in the background - kubectl -n airport get pods -l app.kubernetes.io/name=ollama -w" ;;
    k8s/observability) echo "Grafana: kubectl -n airport port-forward svc/grafana 3000:3000  ->  http://localhost:3000/" ;;
    k8s/tools)         echo "pgAdmin: http://localhost:8090/pgadmin/" ;;
  esac
done
echo "Pods: k9s -n airport    Stop: kind delete cluster --name $CLUSTER"
