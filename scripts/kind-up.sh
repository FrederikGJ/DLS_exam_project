#!/usr/bin/env bash
# Starts the whole airport stack on a local kind cluster in one command (the kind steps from k8s/README.md).
# Safe to run again: an existing cluster is reused and every manifest is applied idempotently.
# Requires: kind, kubectl, docker, helm (metrics-server for the HPAs, KEDA for --notifications). Usage (from any
# directory):
#
#   scripts/kind-up.sh                   # cluster + ingress-nginx + metrics-server + the standard stack (k8s/)
#   scripts/kind-up.sh --build           # docker compose build first (needed after code changes)
#   scripts/kind-up.sh --ai              # + Ollama for shop-service's askRoute (k8s/ai)
#   scripts/kind-up.sh --observability   # + Prometheus, Grafana, Loki, Alloy (k8s/observability)
#   scripts/kind-up.sh --tools           # + pgAdmin on /pgadmin/ (k8s/tools)
#   scripts/kind-up.sh --notifications   # + KEDA and the serverless notification-job (k8s/notifications)
#
# Flags can be combined. Frontend: http://localhost:8090/ (anna/anna, ops/ops). Stop: kind delete cluster --name airport
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=airport
IMAGES=(flight-service booking-service payment-service baggage-service shop-service frontend)
INGRESS_NGINX=https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
# Versions tested on kind with Kubernetes 1.37 (same as the manual steps in k8s/README.md)
METRICS_SERVER_CHART_VERSION=3.14.0
KEDA_CHART_VERSION=2.21.1

build=false
notifications=false
addons=()
for arg in "$@"; do
  case $arg in
    --build)         build=true ;;
    --ai)            addons+=(k8s/ai) ;;
    --observability) addons+=(k8s/observability) ;;
    --tools)         addons+=(k8s/tools) ;;
    --notifications) notifications=true ;;
    -h|--help)       sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)               echo "unknown option: $arg (see --help)" >&2; exit 1 ;;
  esac
done

k() { kubectl --context "kind-$CLUSTER" "$@"; }   # always the kind cluster, whatever context is current
h() { helm --kube-context "kind-$CLUSTER" "$@"; }

apply_kustomize() { # apply_kustomize <dir> - retries while ingress-nginx' admission webhook is still starting
  local attempt
  for attempt in 1 2 3 4 5 6; do
    k apply -k "$1" && return 0
    echo "    apply failed (attempt $attempt/6) - retrying in 10 s" >&2
    sleep 10
  done
  return 1
}

command -v helm >/dev/null 2>&1 \
  || { echo "helm is missing (needed for metrics-server and KEDA) - install it, e.g. winget install Helm.Helm" >&2; exit 1; }

if [[ $build == true ]]; then
  echo "==> Building images (docker compose build)"
  docker compose build
  if [[ $notifications == true ]]; then
    echo "==> Building airport/notification-job:local (not part of docker compose)"
    docker build -t airport/notification-job:local ./notification-job
  fi
fi
if [[ $notifications == true ]]; then
  IMAGES+=(notification-job)
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

# The HorizontalPodAutoscalers in k8s/services/autoscaling.yaml read pod CPU from metrics-server, which kind does
# not ship. Without it the HPAs show <unknown> and never scale (see k8s/README.md "Autoscaling (HPA)").
echo "==> Installing metrics-server (CPU metrics for the HPAs)"
h repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ --force-update >/dev/null
h upgrade --install metrics-server metrics-server/metrics-server --version "$METRICS_SERVER_CHART_VERSION" \
  -n kube-system -f k8s/metrics-server/values-kind.yaml --wait

# KEDA brings the ScaledJob/TriggerAuthentication CRDs that k8s/notifications/ uses, so it goes in before the stack.
if [[ $notifications == true ]]; then
  echo "==> Installing KEDA (scales notification-job on the queue length)"
  h repo add kedacore https://kedacore.github.io/charts --force-update >/dev/null
  h upgrade --install keda kedacore/keda --version "$KEDA_CHART_VERSION" -n keda --create-namespace --wait
fi

echo "==> Deploying the airport stack (k8s/)"
apply_kustomize k8s/
for dir in ${addons[@]+"${addons[@]}"}; do
  echo "==> Deploying add-on $dir"
  apply_kustomize "$dir"
done

# Ollama is left out of the wait: its first start pulls a multi-GB image plus the model and can take far longer.
echo "==> Waiting until every Deployment is Available (first start takes a few minutes; Ctrl-C is safe)"
k -n airport wait --for=condition=Available deployment -l 'app.kubernetes.io/name!=ollama' --timeout=10m

# The notifications go in last: KEDA connects to RabbitMQ when it sets up the ScaledJob, and if RabbitMQ is not up
# yet it backs off for many minutes. A ScaledJob that is still not Ready (e.g. from an earlier run while RabbitMQ
# was restarting) is picked up again by restarting the KEDA operator once.
if [[ $notifications == true ]]; then
  echo "==> Deploying add-on k8s/notifications"
  k -n airport rollout status statefulset/rabbitmq --timeout=5m
  apply_kustomize k8s/notifications
  if ! k -n airport wait --for=condition=Ready scaledjob/notification-job --timeout=60s >/dev/null 2>&1; then
    echo "    ScaledJob not Ready yet - restarting the KEDA operator so it retries now"
    k -n keda rollout restart deploy/keda-operator
    k -n keda rollout status deploy/keda-operator --timeout=120s
    k -n airport wait --for=condition=Ready scaledjob/notification-job --timeout=120s
  fi
fi

echo
echo "Ready: http://localhost:8090/  (log in as anna/anna or ops/ops)"
echo "Autoscaling: kubectl -n airport get hpa"
for dir in ${addons[@]+"${addons[@]}"}; do
  case $dir in
    k8s/ai)            echo "Ollama: still pulling image/model in the background - kubectl -n airport get pods -l app.kubernetes.io/name=ollama -w" ;;
    k8s/observability) echo "Grafana: kubectl -n airport port-forward svc/grafana 3000:3000  ->  http://localhost:3000/" ;;
    k8s/tools)         echo "pgAdmin: http://localhost:8090/pgadmin/" ;;
  esac
done
if [[ $notifications == true ]]; then
  echo "Notifications: kubectl -n airport get jobs -l app.kubernetes.io/name=notification-job -w  (demo: k6 run scripts/notifications-demo.js)"
fi
echo "Pods: k9s -n airport    Stop: kind delete cluster --name $CLUSTER"
