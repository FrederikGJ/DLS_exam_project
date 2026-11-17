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
| `services/`          | 5 × Deployment (2 replicas) + ClusterIP Service + ConfigMap + Secret, liveness/readiness. 2 replicas er ok for outbox-relayet: en Postgres advisory lock sikrer, at kun én pod ad gangen sender events |
| `frontend/`          | nginx Deployment + Service + ConfigMap der overskriver `js/config.js` med Ingress-stier    |
| `tools/`             | pgAdmin (dev/demo-værktøj, ikke en del af systemet): Deployment + Service + ConfigMap + Secret |
| `ai/`                | **Opt-in**, ikke med i `kubectl apply -k k8s/`: Ollama (lokal LLM til shop-service' `askRoute`) – Deployment (1 replica) + PVC 5Gi + Service, egen `kustomization.yaml` |
| `ingress.yaml`       | Én Ingress: `/` → frontend, `/api/<x>/graphql` → den enkelte service, `/pgadmin` → pgAdmin |

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

pgAdmin (dev/demo-værktøj, se [afsnittet nederst](#pgadmin-devdemo-værktøj)) ligger bag Ingress'en og kræver ikke
port-forward: `http://<minikube ip>/pgadmin/` (åbner direkte uden login).

Nyttige kommandoer:

```bash
kubectl -n airport logs deploy/booking-service -f      # logs (JSON i prod-profil)
kubectl -n airport get ingress                          # tjek at Ingress har fået en adresse
kubectl delete -k k8s/                                  # ryd op (PVC'er slettes ikke automatisk af StatefulSets)
```

## Alternativ: kind

Clusteret oprettes med `kind-config.yaml`, som mapper ingress-nginx' port 80/443 på kind-noden til
**localhost:8090 / 8443** på host-maskinen. Dermed kolliderer det ikke med docker-compose-stakken (frontend på 8080),
og der er ikke brug for port-forward. Skal du bruge andre porte, så ret `hostPort` i `kind-config.yaml` **og**
`CORS_ALLOWED_ORIGINS` i `k8s/services/*.yaml`: nginx-ingress sender `X-Forwarded-Port: 80` (porten inde i noden),
så Spring ser browserens origin `http://localhost:8090` som cross-origin, og den skal derfor være tilladt eksplicit.

```bash
kind create cluster --config k8s/kind-config.yaml

# Byg images lokalt og load dem ind i kind-noden (fra repo-roden).
# Har du allerede kørt `docker compose build`/`up --build`, kan build-trinnet springes over.
docker compose build
for img in flight-service booking-service payment-service baggage-service shop-service frontend; do
  kind load docker-image "airport/${img}:local" --name airport
done

# Ingress-controller til kind
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

kubectl apply -k k8s/
kubectl -n airport get pods -w
```

Åbn frontenden på <http://localhost:8090/> (alle `/api/<x>/graphql`-stier går gennem samme Ingress) og pgAdmin på
<http://localhost:8090/pgadmin/> (åbner direkte uden login).

Kør end-to-end-smoketesten mod Kubernetes-stakken gennem Ingress:

```bash
FLIGHT_URL=http://localhost:8090/api/flights/graphql \
BOOKING_URL=http://localhost:8090/api/bookings/graphql \
PAYMENT_URL=http://localhost:8090/api/payments/graphql \
BAGGAGE_URL=http://localhost:8090/api/baggage/graphql \
SHOP_URL=http://localhost:8090/api/shops/graphql \
scripts/e2e-smoke.sh
```

Ryd op med `kind delete cluster --name airport`.

## pgAdmin (dev/demo-værktøj)

`tools/pgadmin.yaml` deployer pgAdmin 4 bag Ingress'en på `/pgadmin/` (kind: <http://localhost:8090/pgadmin/>,
minikube: `http://<minikube ip>/pgadmin/`). Den kører i pgAdmins *desktop mode* (`PGADMIN_CONFIG_SERVER_MODE=False`),
så der er ingen login-side: siden åbner direkte med de fem databaser klar. Netop derfor er det kun et dev/demo-værktøj:
alle, der kan nå `/pgadmin`, har fuld adgang til databaserne.
Første opstart tager 30-60 sekunder, fordi pgAdmin bygger sin konfigurationsdatabase og importerer serverne. Når
hele stakken booter på én gang, tager det flere minutter, fordi de ti Java-services optager CPU'en imens. Ingress'en
svarer 503 på `/pgadmin/`, indtil podden er Ready (`kubectl -n airport get pods -w`). Boot stakken op før en demo.

- De fem databaser er forudregistreret i gruppen **Airport** via `servers.json` (ConfigMap `pgadmin-servers`).
  Kodeordene kommer fra en pgpass-fil i `pgadmin-secret`: imagets entrypoint kopierer `PGPASS_FILE` til
  `/var/lib/pgadmin/.pgpass` med rettigheder 0600 (libpq afviser pgpass-filer, som andre kan læse), og `"PassFile"`
  i `servers.json` peger på den. Klik på en server, så åbner den uden at spørge om kode.
- `PGADMIN_DEFAULT_EMAIL`/`PGADMIN_DEFAULT_PASSWORD` i `pgadmin-secret` kræves stadig af imagets første opstart,
  men bruges ikke til noget login.
- pgAdmin serverer selv under præfikset `/pgadmin` (`SCRIPT_NAME`), så Ingress'en behøver ingen rewrite-regel,
  præcis som services' `GRAPHQL_PATH`.
- pgAdmin er **ikke** en del af selve systemet (det står ikke i kravspec'en) og hører ikke hjemme i et
  produktionscluster. Fjern linjen `tools/pgadmin.yaml` i `kustomization.yaml` for at deploye uden.
- Tilstanden (registrerede servere, sessions) ligger i en `emptyDir` og genskabes ved hvert pod-start. Ændrer du
  `servers.json` eller pgpass, så `kubectl apply -k k8s/` efterfulgt af `kubectl -n airport rollout restart deploy/pgadmin`.
- Ændrer du brugernavn/kode i `databases/*-db.yaml`, skal `pgpass` og `servers.json` i `tools/pgadmin.yaml` følge med.

Demo-idé: lav en booking i frontenden og betal den. Se derefter rækken i `booking_db` (tabellerne `booking` og
`passenger`), betalingen i `payment_db` (`payment`) og bagage-snapshottet i `baggage_db` (`booking_snapshot`).
Tabellen `processed_event` i hver database viser, hvilke events servicen har behandlet (idempotens), og at
`booking_db` ikke indeholder flights, viser "én database pr. service" i praksis.

## AI (Ollama) – valgfrit

shop-service' GraphQL-query `askRoute` ("hvor kan jeg købe kaffe på vej til gate B12?") bruger en lokal LLM
(Ollama, CPU only). Ollama ligger i `ai/` med sin egen `kustomization.yaml` og er **ikke** en del af standard-stakken,
fordi modellen bruger ca. 2,5 GB RAM ekstra i clusteret. Der er to måder at køre på:

**Uden AI (standard)** – `kubectl apply -k k8s/` som normalt. `askRoute` virker stadig: shop-service kan ikke nå
`OLLAMA_URL` og svarer med keyword-fallbacken (`aiUsed: false`, `fallbackReason` forklarer hvorfor).

**Med AI** – deploy add-on'et oven på stakken:

```bash
kubectl apply -k k8s/                 # normal stak
kubectl apply -k k8s/ai               # + Ollama (Deployment, PVC, Service "ollama":11434)
kubectl -n airport get pods -l app.kubernetes.io/name=ollama -w
```

- Første start henter modellen (`qwen2.5:3b`, ca. 2 GB) til PVC'en; podden er først Ready, når modellen er hentet
  (startupProbe tillader op til 10 min). Senere starter genbruger PVC'en.
- Kræver netværksadgang fra clusteret til `registry.ollama.ai` første gang. Imaget `ollama/ollama` hentes fra Docker Hub
  (kind: `kind load docker-image ollama/ollama:0.34.0 --name airport` hvis noden ikke selv kan hente det).
- shop-service finder Ollama via `OLLAMA_URL=http://ollama:11434` i ConfigMap'en `shop-service-config`
  (`k8s/services/shop-service.yaml`) sammen med `OLLAMA_MODEL`, `AI_ENABLED` og `OLLAMA_TIMEOUT`. Der er ingen
  afhængighed den anden vej: shop-service starter og er Ready uden Ollama.
- Podden beder om 3Gi RAM (limit 4Gi), da `qwen2.5:3b` bruger ca. 2,5 GB, når den er indlæst.
- Lidt RAM i clusteret? Skift til `qwen2.5:1.5b` (ca. 1,5 GB RAM, svagere svar): ret `MODEL` i `ai/ollama.yaml`
  **og** `OLLAMA_MODEL` i `shop-service-config`, sænk evt. requests/limits til 1536Mi/3Gi, og kør `kubectl apply` + `kubectl -n airport rollout restart deploy/shop-service`.
- Fjern igen: `kubectl delete -k k8s/ai` (PVC'en med modellen slettes også).
