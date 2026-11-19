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
| `services/`          | 5 × Deployment + ClusterIP Service + ConfigMap + Secret, startup/liveness/readiness. `autoscaling.yaml`: én HorizontalPodAutoscaler pr. service (2-5 pods ved 70 % CPU), se [Autoscaling (HPA)](#autoscaling-hpa) |
| `metrics-server/`    | Helm-values til metrics-server på kind (ikke en del af `kustomization.yaml`, installeres med Helm i `kube-system`) |
| `notifications/`     | Serverless notifikationsfunktion: KEDA ScaledJob + TriggerAuthentication + setup-Job (egen kustomization, kræver KEDA), se [Serverless: notifikationer med KEDA](#serverless-notifikationer-med-keda) |
| `frontend/`          | nginx Deployment + Service + ConfigMap der overskriver `js/config.js` med Ingress-stier    |
| `tools/`             | pgAdmin (dev/demo-værktøj, ikke en del af systemet): Deployment + Service + ConfigMap + Secret |
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

# metrics-server, som HPA'erne læser CPU fra (se "Autoscaling (HPA)" nedenfor)
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/
helm upgrade --install metrics-server metrics-server/metrics-server --version 3.14.0 \
  -n kube-system -f k8s/metrics-server/values-kind.yaml

kubectl apply -k k8s/
kubectl -n airport get pods -w
```

Fejler `kind create cluster` med *"kubelet is configured to not run on a host using cgroup v1"*, kører Docker på en
ældre WSL2-kerne med cgroup v1 (tjek med `docker info --format '{{.CgroupVersion}}'`). `kind-config.yaml` tillader
det allerede med `failCgroupV1: false`; alternativt opdatér WSL med `wsl --update`, så Docker får cgroup v2.

Første opstart tager 3-5 minutter: 10 JVM'er starter samtidig og deler nodens CPU. Derfor giver startup-proben
services op til 5 minutter (`failureThreshold: 60`), før en pod genstartes.

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

## Autoscaling (HPA)

`services/autoscaling.yaml` giver hver af de fem backend-services en **HorizontalPodAutoscaler**: 2-5 pods, og målet er
70 % gennemsnitlig CPU i forhold til podens CPU-*request* (250m), dvs. der kommer en pod til, når gennemsnittet går
over ca. 175m pr. pod. Deployments har derfor **ikke** noget `replicas:`-felt: HPA'en ejer antallet, og en fast værdi
ville nulstille det ved hver `kubectl apply -k k8s/`.

HPA'en læser CPU fra **metrics-server**, som kind ikke har med. Den installeres med Helm (se kind-trinnene ovenfor) med
`metrics-server/values-kind.yaml`, der tilføjer `--kubelet-insecure-tls`, fordi kinds kubelets bruger selvsignerede
certifikater. Det flag er kun til lokalt brug: managed clusters i skyen har typisk metrics-server i forvejen, ellers
installeres chartet uden flaget. Uden metrics-server viser `kubectl -n airport get hpa` `<unknown>` og antallet af pods
ændres ikke.

**Adfærd (`behavior`)**

| Retning   | Regel                                         | Hvorfor                                                                            |
|-----------|-----------------------------------------------|------------------------------------------------------------------------------------|
| Op        | Højst 1 ny pod pr. 60 s, efter 30 s over målet | En ny JVM er 40-60 s om at blive Ready; ellers tilføjes pod på pod, før den første tager last |
| Ned       | 1 pod pr. 30 s, efter 60 s under målet          | Kubernetes' standard venter 300 s; 60 s holder demoen kort, men korte pauser fjerner ikke pods |

**Flere pods er sikkert:** services er stateless, og outbox-relayet tager en Postgres advisory lock
(`pg_try_advisory_xact_lock`), så kun én pod ad gangen sender events, uanset hvor mange pods HPA'en starter. Consumers
på samme kø deler beskederne mellem sig (competing consumers), og `processed_event` gør dem idempotente.

**Ressourcer:** HPA'ens pods skal kunne schedules, og kind-noden har kun den hukommelse, Docker har (fx ca. 7,6 GiB).
Memory-requests er derfor sat ud fra målt forbrug: ca. 270-290 MiB i tomgang og op til ca. 335 MiB under belastning,
så `requests.memory` er 384Mi, mens `limits.memory` stadig er 1Gi (heap = 75 % af limit). Med 512Mi kunne kun 3 af 5
shop-service-pods starte; resten stod `Pending` med *Insufficient memory*. Med 384Mi er der plads til, at én service
skalerer helt op til 5 pods; alle fem services på 5 pods samtidig (25 pods) er der ikke plads til på en laptop.

### Demo: belastning mod shop-service

Kræver [k6](https://grafana.com/docs/k6/latest/set-up/install-k6/) (fx `winget install GrafanaLabs.k6`). Scriptet
`scripts/load-shops.js` sender `shops`-queries gennem Ingress'en: 30 s optrapning, 4 min med 30 virtuelle brugere og
derefter stop. Det tjekker også, at svarene ikke indeholder GraphQL-fejl (GraphQL svarer med HTTP 200 selv ved fejl).

```bash
# Terminal 1: følg autoscaleren
kubectl -n airport get hpa shop-service -w

# Terminal 2: start belastningen (fra repo-roden)
k6 run scripts/load-shops.js
#   andre værdier: k6 run -e VUS=50 -e SHOP_URL=http://localhost:8090/api/shops/graphql scripts/load-shops.js

# Bagefter: se hvornår og hvorfor der blev skaleret
kubectl -n airport describe hpa shop-service
```

Målt på kind (12 kerner, 7,6 GiB til Docker):

| Tid efter start | CPU (af 70 %) | Pods        |
|-----------------|---------------|-------------|
| 0 s             | 9 %           | 2           |
| ~1 min          | 372 %         | 2 → 3       |
| ~2 min          | 226 %         | 3 → 4       |
| ~3,5 min        | 229 %         | 4 → 5 (alle Ready, ingen Pending) |
| efter stop      | under målet   | 5 → 4 → 3 → 2, ca. én pod pr. 30 s |

k6: ca. 39 requests/s, 100 % gyldige svar. CPU forbliver over målet ved 5 pods, fordi `maxReplicas: 5` er nået; HPA'en
melder da `ScalingLimited: TooManyReplicas`.

## Serverless: notifikationer med KEDA

`notifications/` deployer en **serverless funktion**: `notification-job` sender (logger) en mail, når en booking
bekræftes eller annulleres, og når en betaling refunderes. Det er ikke en service, der kører hele tiden, men et
lille Java-program uden HTTP-server, som **KEDA** starter som ét Kubernetes Job pr. besked på køen `notifications`.
Er køen tom, kører der 0 pods.

| | HPA (ticket 13, backend-services) | KEDA ScaledJob (ticket 12, notification-job) |
|---|---|---|
| Kører | Deployments, der altid kører | Jobs, der tager én besked og afslutter |
| Minimum | 2 pods | **0** pods |
| Skalerer på | CPU | Antal beskeder i køen `notifications` |

Delene:

* **`notification-setup` (Job)** kører én gang ved deploy og opretter køen `notifications` (bundet til
  `booking.confirmed`, `booking.cancelled`, `payment.refunded` på `airport.events`) og DLQ'en
  `notification-job.dlq`. Uden den ville ingen oprette køen, når der kører 0 jobs.
* **`rabbitmq-trigger-auth` (TriggerAuthentication)** giver KEDA brugernavn og kode fra den eksisterende
  `rabbitmq-secret`.
* **`notification-job` (ScaledJob)**: KEDA tjekker kølængden hvert 5. sekund og starter ét Job pr. ventende
  besked (`value: "1"`), højst 5 ad gangen (`maxReplicaCount: 5`). Et Job, der fejler, har allerede flyttet sin
  besked til DLQ'en, så det køres ikke igen (`backoffLimit: 0`). De sidste 10 færdige Jobs gemmes, så mailene kan
  læses i loggen.

Mappen er **ikke** med i `k8s/kustomization.yaml`, fordi `ScaledJob` og `TriggerAuthentication` er KEDA-CRD'er:
uden KEDA ville `kubectl apply -k k8s/` fejle for alle.

### Installation (kind)

```bash
# 1. KEDA (én gang pr. cluster). 2.21.1 er testet på kind med Kubernetes 1.37.
helm repo add kedacore https://kedacore.github.io/charts
helm upgrade --install keda kedacore/keda --version 2.21.1 -n keda --create-namespace --wait

# 2. Image (fra repo-roden)
docker build -t airport/notification-job:local ./notification-job
kind load docker-image airport/notification-job:local --name airport

# 3. Deploy (kræver at resten af stakken kører, se ovenfor)
kubectl apply -k k8s/notifications/
kubectl -n airport get scaledjob notification-job      # READY skal være True
```

Er `notification-setup` allerede kørt og skal køres igen (fx efter nyt image), så slet den først:
`kubectl -n airport delete job notification-setup`. Et færdigt Job kan ikke opdateres.

### Demo: fem bookinger giver fem jobs

`scripts/notifications-demo.js` (k6) opretter og betaler fem bookinger gennem Ingress'en. Hver betaling giver et
`booking.confirmed` → én besked på `notifications` → ét Job.

```bash
# Terminal 1: følg jobbene
kubectl -n airport get jobs -l app.kubernetes.io/name=notification-job -w

# Terminal 2 (fra repo-roden)
k6 run scripts/notifications-demo.js

# Bagefter: læs en "mail"
kubectl -n airport logs job/<navn fra listen>
```

Målt på kind (12 kerner):

| Tid efter sidste betaling | Jobs                                       | Pods          |
|---------------------------|--------------------------------------------|---------------|
| 0 s                       | 0                                          | 0             |
| ~5 s                      | 5 startet (1 allerede Complete, 4 Running) | 4             |
| ~10 s                     | 5 Complete                                 | 0             |

Hvert Job tog 4-5 s fra start til slut, inkl. pod-opstart (selve programmet kører under 1 s). Køen og DLQ'en var tomme
bagefter (`kubectl -n airport exec rabbitmq-0 -- rabbitmqctl list_queues name messages`).

### Compose (task 12.6): udeladt med vilje

Funktionen findes kun i Kubernetes. docker-compose har ingen KEDA og ingen måde at starte en container pr. besked;
den samme image i en løkke ville bare være en almindelig consumer, der kører hele tiden, og dermed ikke vise
kravet (serverless, skalering til 0). I compose findes køen `notifications` derfor ikke, og events bliver ikke routet
dertil. Programmet kan stadig køres lokalt mod compose-RabbitMQ: `java -jar notification-job/target/notification-job.jar setup`
og derefter `... once`.

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
