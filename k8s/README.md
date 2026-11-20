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
| `frontend/`          | nginx Deployment + Service + ConfigMap der overskriver `js/config.js` med Ingress-stier og Keycloak-URL |
| `keycloak/`          | Keycloak 26 (login, roller): Deployment + Service + Secret + `realm-airport.json` (bliver til ConfigMap `keycloak-realm` via `configMapGenerator`) – se [Keycloak (login)](#keycloak-login) |
| `tools/`             | pgAdmin (dev/demo-værktøj uden login, **ikke** med i `kubectl apply -k k8s/`): egen kustomization med Deployment + Service + ConfigMap + Secret + egen Ingress på `/pgadmin` – se [pgAdmin](#pgadmin-devdemo-værktøj) |
| `ai/`                | **Opt-in**, ikke med i `kubectl apply -k k8s/`: Ollama (lokal LLM til shop-service' `askRoute`) – Deployment (1 replica) + PVC 5Gi + Service, egen `kustomization.yaml` |
| `observability/`     | **Opt-in**, ikke med i `kubectl apply -k k8s/`: Prometheus, Grafana, Loki og Alloy (logs, metrics, alarmer) – egen `kustomization.yaml`, se [docs/architecture.md](../docs/architecture.md#logging-og-monitoring) |
| `ingress.yaml`       | Én Ingress: `/` → frontend, `/api/<x>/graphql` → den enkelte service, `/auth` → Keycloak  |

Secrets indeholder **dev-værdier** (fx `flight/flight`, Keycloak-admin `admin/admin`, testbrugerne `anna/anna` og
`ops/ops`). Skift dem før brug i et delt cluster.

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

Bagagesidernes Keycloak-login bruger `http://localhost:8090` som lokal adgangsadresse i Kubernetes.
Hvis du bruger Minikube, kan du videresende Ingress-controlleren med
`kubectl -n ingress-nginx port-forward service/ingress-nginx-controller 8090:80` og åbne den adresse.

RabbitMQ management UI (bruger/kode `airport`/`airport`):

```bash
kubectl -n airport port-forward svc/rabbitmq 15672:15672
# -> http://localhost:15672
```

Login virker ud af boksen kun på kind (<http://localhost:8090>), fordi Keycloaks issuer er låst til den adresse. På
minikube skal `KC_HOSTNAME` i `keycloak/keycloak.yaml`, `OIDC_ISSUER_URI` i `services/*.yaml` og redirect-URI'erne i
`keycloak/realm-airport.json` rettes til `http://<minikube ip>/auth` – se [Keycloak (login)](#keycloak-login).

pgAdmin (dev/demo-værktøj uden login, se [afsnittet nederst](#pgadmin-devdemo-værktøj)) er ikke med i standardstakken;
tilvælg den med `kubectl apply -k k8s/tools/` og åbn `http://<minikube ip>/pgadmin/`.

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

**Alt på én gang:** `scripts/kind-up.sh` kører trinene nedenfor (opretter clusteret eller genbruger det, loader
images, installerer ingress-nginx, deployer `k8s/` og venter, til alle Deployments er Available). Tilvalg kan
kombineres: `--build` (byg images først), `--ai` (Ollama), `--observability` og `--tools` (pgAdmin), fx
`scripts/kind-up.sh --build --ai`. Trin for trin:

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

Åbn frontenden på <http://localhost:8090/> (alle `/api/<x>/graphql`-stier går gennem samme Ingress) og log ind med
`anna`/`anna` eller `ops`/`ops`. Keycloak ligger på <http://localhost:8090/auth/> (admin console `/auth/admin/`,
`admin`/`admin` – se [Keycloak (login)](#keycloak-login)). pgAdmin er ikke med; tilvælg den med
`kubectl apply -k k8s/tools/` (se [pgAdmin](#pgadmin-devdemo-værktøj)).

Kør end-to-end-smoketesten mod Kubernetes-stakken gennem Ingress:

```bash
FLIGHT_URL=http://localhost:8090/api/flights/graphql \
BOOKING_URL=http://localhost:8090/api/bookings/graphql \
PAYMENT_URL=http://localhost:8090/api/payments/graphql \
KEYCLOAK_URL=http://localhost:8090/auth \
BAGGAGE_URL=http://localhost:8090/api/baggage/graphql \
SHOP_URL=http://localhost:8090/api/shops/graphql \
KEYCLOAK_URL=http://localhost:8090/auth \
scripts/e2e-smoke.sh
```

`KEYCLOAK_URL` er Keycloaks browser-vendte adresse bag Ingress'en; scriptet henter tokens for `anna` og `ops` dér.

Ryd op med `kind delete cluster --name airport`.

## Keycloak (login)

`keycloak/keycloak.yaml` deployer Keycloak 26 bag Ingress'en på `/auth/` (kind: <http://localhost:8090/auth/>).
Realm'et `airport` importeres ved hvert pod-start fra `keycloak/realm-airport.json` – **den samme fil**, som
`docker-compose.yml` mounter, så compose og Kubernetes har præcis samme roller, client og testbrugere:

| Hvad                 | Værdi                                                                                     |
|----------------------|-------------------------------------------------------------------------------------------|
| Realm                | `airport`, realm-roller `PASSENGER` og `OPERATIONS`                                       |
| Client               | `airport-frontend` – public client, Authorization Code + PKCE (S256), direct access grants slået til (så `curl`/smoke-test kan hente tokens med password grant) |
| Testbrugere          | `anna` / `anna` (PASSENGER, `anna@example.com`), `ops` / `ops` (OPERATIONS, `ops@example.com`) |
| Admin console        | kind: <http://localhost:8090/auth/admin/>, compose: <http://localhost:8180/admin/> – `admin` / `admin` (`keycloak-secret`) |
| Identity provider    | `github` er defineret men **slået fra**; brokerede GitHub-brugere får rollen PASSENGER. Se [docs/architecture.md](../docs/architecture.md#github-login-identity-brokering) |
| Database             | H2 i en `emptyDir` (`start-dev`): kendt tilstand ved hver demo. Et delt cluster kræver `start` + PostgreSQL + TLS |

Keycloak serverer selv under præfikset `/auth` (`KC_HTTP_RELATIVE_PATH`), så Ingress'en behøver ingen rewrite-regel –
præcis som services' `GRAPHQL_PATH`. Health-endpoints ligger på management-porten 9000 og bruges af proberne.

Hent et token og se rollerne:

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  http://localhost:8090/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | jq .realm_access
```

### Faldgrube: issuer (`iss`) skal være den samme URL for browser og services

Et JWT fra Keycloak indeholder `iss`, og Spring afviser tokenet, hvis `iss` ikke er præcis den streng, den forventer.
Keycloak udleder som udgangspunkt `iss` af den URL, forespørgslen kom ind på – så et token hentet af browseren via
`http://localhost:8090/auth` og et token hentet af en pod via `http://keycloak:8080/auth` ville have to forskellige
issuers. Samme problem i compose: `localhost:8180` set fra browseren, `keycloak:8080` set fra containerne.

Løsningen er at **låse alle Keycloaks URL'er** til den browser-vendte adresse med `KC_HOSTNAME`
(`http://localhost:8090/auth` i kind, `http://localhost:8180` i compose). `KC_PROXY_HEADERS` og
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC` er bevidst **ikke** sat: med dem lækker ingress-nginx' `X-Forwarded-Port: 80`
(porten inde i kind-noden, ikke 8090) ind i discovery-dokumentet. Services'ne konfigureres med to værdier og bruger
aldrig discovery:

| Env-var i services      | Værdi (kind)                                                             | Bruges til                                 |
|-------------------------|--------------------------------------------------------------------------|--------------------------------------------|
| `OIDC_ISSUER_URI`       | `http://localhost:8090/auth/realms/airport`                              | den streng `iss` i tokenet skal matche     |
| `JWK_SET_URI`           | `http://keycloak:8080/auth/realms/airport/protocol/openid-connect/certs` | hvor signeringsnøglerne hentes (in-cluster) |

Skifter du host eller port (fx minikube: `http://<minikube ip>/auth`), skal `KC_HOSTNAME` i `keycloak/keycloak.yaml`,
`OIDC_ISSUER_URI` i `services/*.yaml` og redirect-URI'erne i `keycloak/realm-airport.json` rettes sammen (frontendens
`KEYCLOAK_URL` følger selv sidens origin) – ellers er symptomet `401` med
`WWW-Authenticate: ... invalid_token ... The iss claim is not valid`.

## pgAdmin (dev/demo-værktøj)

pgAdmin er **ikke** med i `kubectl apply -k k8s/`. Den kører i pgAdmins *desktop mode*
(`PGADMIN_CONFIG_SERVER_MODE=False`), så der er ingen login-side: siden åbner direkte med de fem databaser klar, og
alle, der kan nå `/pgadmin`, har fuld adgang til databaserne. Derfor ligger den i sin egen kustomization og tilvælges
kun på et lokalt dev-cluster, oven på standardstakken:

```bash
kubectl apply -k k8s/tools/      # pgAdmin + egen Ingress på /pgadmin (kind: http://localhost:8090/pgadmin/)
kubectl delete -k k8s/tools/     # fjern den igen
```

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
  produktionscluster – derfor er den ikke med i standardstakken. Dens Ingress-regel ligger i `tools/pgadmin-ingress.yaml`
  (ingress-nginx fletter stierne fra alle Ingress-objekter for samme host, og `/pgadmin` vinder over `/`).
- Tilstanden (registrerede servere, sessions) ligger i en `emptyDir` og genskabes ved hvert pod-start. Ændrer du
  `servers.json` eller pgpass, så `kubectl apply -k k8s/tools/` efterfulgt af `kubectl -n airport rollout restart deploy/pgadmin`.
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
