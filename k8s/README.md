# Kubernetes-deployment (k8s/)

Alle manifests ligger i denne mappe og deployes samlet med Kustomize:

```bash
kubectl apply -k k8s/
```

Indhold:

| Mappe/fil            | Indhold                                                                                   |
|----------------------|-------------------------------------------------------------------------------------------|
| `namespace.yaml`     | Namespace `airport`                                                                       |
| `rabbitmq/`          | StatefulSet + Service (5672/15672) + Secret                                               |
| `databases/`         | 5 × PostgreSQL 16 StatefulSet (1 replica, PVC 1Gi) + Service + Secret, én pr. service     |
| `services/`          | 5 × Deployment (2 replicas) + ClusterIP Service + ConfigMap + Secret, liveness/readiness  |
| `frontend/`          | nginx Deployment + Service + ConfigMap der overskriver `js/config.js` med Ingress-stier    |
| `ingress.yaml`       | Én Ingress: `/` → frontend, `/api/<x>/graphql` → den enkelte service                      |

Secrets indeholder **dev-værdier** (fx `flight/flight`). Skift dem før brug i et delt cluster.

## Minikube

```bash
# 1. Start cluster med ingress-addon
minikube start --cpus 4 --memory 8192
minikube addons enable ingress

# 2. Byg images direkte ind i minikubes Docker-daemon (kør fra repo-roden)
eval $(minikube docker-env)
docker compose build
#   ...eller ét image ad gangen:
#   minikube image build -t airport/flight-service:local ./flight-service   (gentag for booking/payment/baggage/shop og frontend)

# 3. Deploy alt
kubectl apply -k k8s/

# 4. Følg opstarten (databaser og RabbitMQ først, services ca. 1-2 min efter)
kubectl -n airport get pods -w
```

Åbn frontenden:

```bash
minikube ip            # fx 192.168.49.2
# -> http://192.168.49.2/
```

Bruger du Docker-driveren på macOS eller Windows, er `minikube ip` ikke direkte tilgængelig. Kør i stedet
`minikube tunnel` i en separat terminal og åbn <http://127.0.0.1/> (eller `minikube service -n airport frontend`
for en direkte NodePort-tunnel uden Ingress). På Linux virker `minikube ip` direkte.

RabbitMQ management UI (bruger/kode `airport`/`airport`):

```bash
kubectl -n airport port-forward svc/rabbitmq 15672:15672
# -> http://localhost:15672
```

Nyttige kommandoer:

```bash
kubectl -n airport logs deploy/booking-service -f      # logs (JSON i prod-profil)
kubectl -n airport get ingress                          # tjek at Ingress har fået en adresse
kubectl delete -k k8s/                                  # ryd op (PVC'er slettes ikke automatisk af StatefulSets)
```

## Alternativ: kind

```bash
kind create cluster --name airport

# Byg images lokalt og load dem ind i kind-noden (fra repo-roden)
docker compose build
for img in flight-service booking-service payment-service baggage-service shop-service frontend; do
  kind load docker-image "airport/${img}:local" --name airport
done

# Ingress-controller til kind
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

kubectl apply -k k8s/
kubectl -n airport get pods -w

# Åbn frontenden via port-forward til ingress-controlleren
kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80
# -> http://localhost:8080/
```
