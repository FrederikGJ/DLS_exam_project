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
| `services/`          | 5 × Deployment (1 replica, `requests` 150m/256Mi, `limits` 500m/640Mi – se *Ressourcer på en laptop*) + ClusterIP Service + ConfigMap + Secret, liveness/readiness, initContainer `wait-for-db` der venter på servicens Postgres med `pg_isready`. Kan skaleres til flere replicas uden kodeændringer: en Postgres advisory lock sikrer, at kun én pod ad gangen kører outbox-relayet |
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

## Ressourcer på en laptop

Stakken er dimensioneret til at køre på en almindelig laptop (4 kerner / 15 GiB) ved siden af kind, Docker og en
browser. Med de oprindelige værdier (2 replicas pr. service, `requests` 250m/512Mi, `limits` 1000m/1Gi) bootede ti
JVM'er samtidig og låste maskinen. Derfor er der skåret på fire ting:

| Hvad                      | Før                         | Nu                                                    | Hvorfor det er ok |
|---------------------------|-----------------------------|-------------------------------------------------------|-------------------|
| Replicas pr. Java-service | 2                           | 1                                                     | Ingen krav siger 2 replicas; horisontal skalering vises i stedet med en HorizontalPodAutoscaler på shop-service. Services er stateless, og outbox-relayet er uafhængigt af antallet (advisory lock), så man kan skalere op uden at røre koden |
| `requests` pr. pod        | cpu 250m / memory 512Mi     | cpu 150m / memory 256Mi                               | Requests er kun en reservation over for scheduleren. De fem Java-pods reserverer nu 0,75 kerne og 1,25 GiB i stedet for 2,5 kerner og 5 GiB, så alt kan ligge på én kind-node uden `Pending` |
| `limits` pr. pod          | cpu 1000m / memory 1Gi      | cpu 500m / memory 640Mi                               | Målt RSS efter boot er ca. 240 MiB, og `MaxRAMPercentage=75` giver en heap på 480 MiB – rigeligt til demo-trafik. En halv kerne er nok, fordi JVM-flagene nedenfor fjerner det tunge JIT-arbejde under boot |
| `JAVA_TOOL_OPTIONS`       | `-XX:MaxRAMPercentage=75.0` | + `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k` | Booter 2,3–2,8× hurtigere under CPU-limit (måling nedenfor). Prisen er lavere peak-throughput og korte GC-pauser, som ingen mærker i en demo |

Samlet reserverer de fem Java-services 0,75 kerne / 1,25 GiB (før: 2,5 kerner / 5 GiB) og må højst bruge 2,5 kerner /
3,1 GiB (før: 10 kerner / 10 GiB). Værdierne er ens i alle fem `services/*.yaml`, og JVM-flagene er de samme i
`x-service-defaults` i `docker-compose.yml`, så compose og k8s opfører sig ens.

### Sådan skruer du op igen

- **Flere replicas:** ad hoc med `kubectl -n airport scale deployment/shop-service --replicas=2`, permanent ved at
  rette `replicas:` i `services/<service>.yaml`. To replicas er sikkert for alle fem services (se noten om
  outbox-relayet i tabellen øverst i denne fil).
- **Mere CPU/RAM:** ret `resources` i `services/<service>.yaml`. Heapen følger automatisk med (`MaxRAMPercentage`),
  så der er ikke andet at ændre. Husk at summen af requests skal kunne være på noden.
- **Fuld JIT/throughput:** fjern `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k` fra `JAVA_TOOL_OPTIONS` i
  `services/*.yaml` og i `x-service-defaults` i `docker-compose.yml`. Regn så med markant længere boot-tid under
  CPU-limit (tabellen nedenfor).
- **Større maskine:** kind bruger alle værtens kerner; minikube skal have dem tildelt (`--cpus`/`--memory` i
  `minikube start` ovenfor).

### Verificeret på kind (14-09-2026)

Cold boot af hele stakken med værdierne ovenfor, målt fra `kubectl apply -k k8s/` på samme laptop (8 tråde / 15 GiB,
kind-node + Docker + browser kørende):

| Måling                                   | Resultat                                                        |
|------------------------------------------|-----------------------------------------------------------------|
| Alle 13 pods Ready                       | efter 71 s (databaser + RabbitMQ efter 57 s, Java-services 14 s senere); 55 s ved en senere cold boot med initContaineren `wait-for-db` (se nedenfor) |
| `Started ...Application in`              | 25–27 s pr. Java-service, mens alle fem bootede samtidig       |
| Load average (1 min), maks               | 5,0 på 8 tråde – maskinen forblev responsiv                     |
| Ledig RAM, minimum                       | ca. 6,9 GiB                                                     |
| `scripts/e2e-smoke.sh` gennem Ingress    | alle flows grønne                                               |
| Restarts på Java-pods                    | 2 uden initContainer (Flyway før Postgres), 0 med `wait-for-db` – se nedenfor |

Til sammenligning tog cold boot ca. 4 minutter med de gamle værdier, og maskinen låste undervejs.

### initContainer: vent på databasen

Java-pods starter med **0 restarts**, fordi hver Deployment i `services/*.yaml` har en initContainer `wait-for-db`, der
kører `pg_isready` mod servicens egen database i en løkke (2 s mellem forsøg), indtil Postgres accepterer forbindelser.
Den bruger `postgres:16-alpine`, som allerede ligger på noden, fordi databaserne kører på det, så der hentes ikke noget
ekstra, og den har sine egne små `resources` (10m/16Mi), så den ikke tæller med i Java-poddens budget.

Baggrunden er, at JVM'en med de lettere flag er hurtigere oppe end Postgres ved cold boot. Uden initContaineren fik
Flyway `Connection to <service>-db:5432 refused`, containeren afsluttede med exit 1, og hver Java-pod viste 2 restarts
med back-off (10 s, 20 s), før tredje forsøg lykkedes. Det var ikke OOM (limits holder), og løsningen er ikke højere
limits, men at vente på databasen – det samme som `depends_on: condition: service_healthy` gør i `docker-compose.yml`.
Ved cold boot i en ny namespace ventede initContaineren 14–18 s pr. pod (databaserne var Ready efter 14–20 s),
Java-services var Ready efter 46–51 s, og alle 13 pods efter 55 s. Se, hvad den venter på, med:

```bash
kubectl -n airport logs deploy/flight-service -c wait-for-db
```

### Måling af boot-tid (før/efter de lettere JVM-flag)

Målt 14-09-2026 på `airport/flight-service:local` i Docker (laptop, 8 tråde / 15 GiB). Tallet er Spring Boots egen
linje `Started FlightServiceApplication in X seconds`; to kørsler pr. variant efter én opvarmningskørsel mod samme
database (Flyway-migrationer allerede kørt). "Begrænset" er `docker run --cpus 0.5 --memory 640m`, som svarer til
poddens limits (`cpu: 500m`, `memory: 640Mi`).

| `JAVA_TOOL_OPTIONS`                                                          | Uden CPU-grænse | Begrænset (0,5 CPU) | RSS efter boot, begrænset |
|-------------------------------------------------------------------------------|-----------------|---------------------|---------------------------|
| Før: `-XX:MaxRAMPercentage=75.0`                                              | 5,4 s · 5,5 s   | 53,2 s · 44,8 s     | ca. 262 MiB               |
| Efter: `-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k` | 5,2 s · 5,2 s | 19,8 s · 19,1 s | ca. 240 MiB           |

Konklusion: uden CPU-grænse gør flagene ingen forskel, men med den CPU-limit podden faktisk har, booter servicen
2,3–2,8× hurtigere og bruger ca. 20 MiB mindre. Det er præcis den situation, der opstod, når ti JVM'er bootede
samtidig og delte fire kerner: hver pod fik under en halv kerne, og C2-JIT'en åd den. Trade-off: `TieredStopAtLevel=1`
(kun C1-JIT) giver lavere peak-throughput, og SerialGC har korte stop-the-world-pauser. Begge dele er uden betydning
for en demo-stak med få requests, og flagene fjernes igen ved at rette `JAVA_TOOL_OPTIONS` i `services/*.yaml`
(og `x-service-defaults` i `docker-compose.yml`).

Gentag målingen selv (kræver `docker compose up -d flight-db rabbitmq` først):

```bash
timeout 90 docker run --rm --network dls_exam_default --cpus 0.5 --memory 640m \
  -e DB_URL=jdbc:postgresql://flight-db:5432/flight_db -e RABBITMQ_HOST=rabbitmq \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k" \
  airport/flight-service:local 2>&1 | grep -m1 'Started FlightServiceApplication'
```

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
