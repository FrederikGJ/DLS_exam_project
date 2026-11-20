# DLS_exam_project – Lufthavnssystem (microservices)

[![CI](https://github.com/FrederikGJ/DLS_exam_project/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/FrederikGJ/DLS_exam_project/actions/workflows/ci.yml)

Eksamensprojekt i *Development of Large Systems*, Softwareudvikling bachelor 2026 efterår.

**Gruppe 7:** Mahdi Karimi, Lukas Rønberg, Frederik Johannessen, Sophus Ingi Sophusson  

Et lufthavnssystem bestående af én frontend og fem uafhængige backend-microservices, der kan:

- vise flyselskaber og afgange
- lade en passager booke en plads på et fly
- tage imod (simuleret) betaling for en booking
- registrere bagage og binde den til en passager/booking
- vise lufthavnens butikker og navigere passageren til dem (Dijkstra)

Dokumentation: [docs/architecture.md](docs/architecture.md) (diagram, flows, designvalg) ·
[docs/events.md](docs/events.md) (alle events med eksempler) · [docs/asyncapi.yaml](docs/asyncapi.yaml) (samme
eventkontrakt maskinlæsbart, AsyncAPI 3.1, valideret i CI) · [k8s/README.md](k8s/README.md) (minikube/kind) ·
service-READMEs: [flight](flight-service/README.md) · [booking](booking-service/README.md) ·
[payment](payment-service/README.md) · [baggage](baggage-service/README.md) · [shop](shop-service/README.md).

## Tech stack

| Del                 | Teknologi                                                             |
|---------------------|-----------------------------------------------------------------------|
| Frontend            | Vanilla JavaScript, HTML, CSS – serveret af nginx (ingen frameworks)  |
| Backend             | Java 21, Spring Boot 3.5 (Maven)                                       |
| API                 | GraphQL (Spring for GraphQL) – kun `/actuator/health` er REST         |
| Database            | PostgreSQL 16 – én database pr. service, migrationer med Flyway       |
| Message broker      | RabbitMQ (Spring AMQP) – topic exchange `airport.events`              |
| Containerisering    | Docker, multi-stage builds (maven → eclipse-temurin JRE)              |
| Orkestrering        | Kubernetes (Kustomize), verificeret på kind – minikube-kommandoer i k8s/README.md |
| Tests               | JUnit 5, Testcontainers (Postgres + RabbitMQ), Spring GraphQL Tester  |

## Komponenter

| Service           | Ansvar                                            | Port (compose) | GraphQL-endpoint                          |
|-------------------|---------------------------------------------------|----------------|-------------------------------------------|
| `frontend`        | SPA: afgange, book, betaling, min booking, bagage, butikker | 8080  | –                                          |
| `flight-service`  | Flyselskaber, fly, afgange, sæder (kilde til sandhed) | 8081       | `http://localhost:8081/api/flights/graphql` |
| `booking-service` | Passagerer og bookinger, orkestrerer bookingflowet | 8082          | `http://localhost:8082/api/bookings/graphql` |
| `payment-service` | Simuleret betalingsgateway, refunds               | 8083           | `http://localhost:8083/api/payments/graphql` |
| `baggage-service` | Bagage bundet til booking, status-tracking        | 8084           | `http://localhost:8084/api/baggage/graphql` |
| `shop-service`    | Butikker + navigation (Dijkstra) + AI-spørgsmål (Ollama)                | 8085           | `http://localhost:8085/api/shops/graphql` |
| RabbitMQ          | Events mellem services                            | 5672 / 15672   | Management UI: http://localhost:15672 (airport/airport) |
| PostgreSQL ×5     | `flight_db`, `booking_db`, `payment_db`, `baggage_db`, `shop_db` | 5433–5437 | – |

Hver service har GraphiQL på `http://localhost:808x/graphiql?path=/api/<x>/graphql` i dev-profilen
og health-endpoints på `/actuator/health/liveness` og `/actuator/health/readiness`.

**Hver service har sin egen README** (linket i tabellen), bygget over samme skabelon,
[docs/service-readme-template.md](docs/service-readme-template.md): ansvar og data, endpoints med et kørt eksempel,
forretningsregler, events ind og ud, konfiguration (env-vars), kør lokalt, tests, drift og designvalg.

## Kør lokalt med Docker

Krav: Docker med Compose v2 (Docker Desktop eller docker-ce + compose-plugin). Intet andet skal installeres.

```bash
docker compose up --build
```

Første build tager nogle minutter (Maven downloader dependencies i build-containerne). Når alle
services melder `healthy`, åbn **http://localhost:8080**.

**Med AI (valgfrit):** `askRoute` ("Spørg om vej" på *Butikker*) bruger en lokal LLM (Ollama). Den ligger i
compose-profilen `ai`, så den normale stak starter uden:

```bash
docker compose --profile ai up --build    # som ovenfor + Ollama; første start henter modellen (~2 GB)
```

Uden profilen virker `askRoute` stadig – den svarer med søgeords-fallbacken (`aiUsed: false`).
Standardmodellen er `qwen2.5:3b` (~2 GB download, ~2,5 GB RAM). Mangler maskinen RAM, så brug `qwen2.5:1.5b`
(~1 GB, ~1,5 GB RAM, men svagere valg): sæt `MODEL` på `ollama` og `OLLAMA_MODEL` på `shop-service` i `docker-compose.yml`.
Se [shop-service/README.md](shop-service/README.md) og [docs/architecture.md](docs/architecture.md#flow-e--spørg-om-vej-ai-askroute).

Seed-data indlæses automatisk af Flyway ved første opstart: 3 flyselskaber, 5 fly, 10 afgange med sæder,
2 terminaler, 15+ butikker og et navigationsnetværk med 30+ noder inkl. gates.

### Login og roller

Login kører gennem **Keycloak** (OpenID Connect, Authorization Code + PKCE). Klik *Log ind* øverst til højre:

| Bruger | Kode   | Rolle        | Må                                                                                   |
|--------|--------|--------------|--------------------------------------------------------------------------------------|
| `anna` | `anna` | `PASSENGER`  | booke (med sin egen e-mail `anna@example.com`), betale, registrere bagage, se og annullere egne bookinger |
| `ops`  | `ops`  | `OPERATIONS` | alt ovenfor for alle bookinger + oprette/ændre afgange, status og gate, refundere, opdatere bagagestatus, vedligeholde butikker |

Afgange, butikker og ruter kan ses uden login; *Book*, *Betaling*, *Min booking* og *Bagage* sender til login først.
Hver service validerer tokenet selv (OAuth2 resource server) og tjekker rollen pr. operation. Mangler tokenet, svarer
servicen med fejlkoden `UNAUTHORIZED`; forkert rolle eller en andens booking giver `FORBIDDEN`. Hele designet (valg af
IdP, flow, rolle pr. operation, ejerskab, issuer/JWKS, GitHub-login, dev-secrets) står i
[docs/architecture.md – Sikkerhed](docs/architecture.md#sikkerhed-login-og-roller).

Token til `curl` (password grant er slået til for test-clienten):

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' http://localhost:8081/api/flights/graphql \
  -d '{"query":"mutation { updateGate(flightId: 1, gate: \"A7\") { flightNumber gate } }"}'
```

Nyttige kommandoer:

```bash
docker compose ps                         # status/health
docker compose logs -f booking-service    # logs for én service
docker compose down -v                    # stop og slet databaser (nulstil seed-data)
```

### Prøv flows fra frontenden

- **Flow A – booking og betaling:** log ind som `anna` → *Afgange* → *Book* på et fly → udfyld passager (e-mailen er
  din login-e-mail), vælg sæde → *Bekræft booking*
  → *Betaling*: brug fx kort `4242 4242 4242 4242`, udløb `12/30`, cvv `123` → bookingen bliver `CONFIRMED`.
  Kort der slutter på `0000` giver `Insufficient funds`; udløbet dato giver `Card expired` (bookingen annulleres).
- **Flow B – bagage:** *Bagage* → indtast bookingreference, vægt 23, type CHECKED → tag genereres →
  (som `ops`) opdatér status til `LOADED` / "Belt 4" → se det under *Min booking*.
- **Flow C – aflysning:** log ind som `ops` → *Afgange* → slå *Vis operations-panel* til → sæt status `CANCELLED` på flyet →
  bookinger annulleres, betalinger refunderes, bagage sendes til `RETURN_DESK`, sæder frigives.
- **Flow D – navigation:** *Butikker* → vælg "Security T2" → "Gate B12" → *Find rute* → trin-for-trin rute,
  afstand, estimeret tid, butikker undervejs og et SVG-kort med gangnetværk, nummererede trin, instruktionstekst,
  afstand pr. delstrækning og retningspile.
- **Flow E – spørg om vej (AI):** *Butikker* → vælg "Hvor er jeg?" = "Security T2" → skriv *"Hvor kan jeg købe kaffe
  på vej til gate B12?"* → *Spørg* → butik + rute start → butik → gate på kortet, og et mærke der viser om AI eller
  fallbacken svarede. Kræver `--profile ai` for AI-svaret; ellers svarer fallbacken.

### Automatisk smoke-test af Flow A–D

Med stakken kørende kan alle fire flows køres end-to-end fra kommandolinjen (kræver `curl` og `jq`). Scriptet
henter selv tokens for `anna` og `ops` fra Keycloak og tjekker også, at et kald uden token giver `UNAUTHORIZED` og
`anna` på en operations-mutation giver `FORBIDDEN`:

```bash
./scripts/e2e-smoke.sh
```

Scriptet booker et sæde, betaler, registrerer bagage, aflyser flyet og verificerer at bookingen bliver
`CANCELLED`, betalingen `REFUNDED`, bagagen står ved `RETURN_DESK` og sædet er frigivet – og slutter med en
rute fra Security T2 til Gate B12. Bemærk at Flow C aflyser et fly fra seed-data; `docker compose down -v`
nulstiller.

### Test af retry + dead-letter queue

Publicér en besked der ikke kan parses, og se den lande i DLQ'erne efter 3 forsøg:

```bash
curl -u airport:airport -H 'Content-Type: application/json' -X POST \
  http://localhost:15672/api/exchanges/%2F/airport.events/publish \
  -d '{"properties":{},"routing_key":"booking.test","payload":"not json","payload_encoding":"string"}'
# -> flight-service.dlq, payment-service.dlq og baggage-service.dlq har nu 1 besked hver (RabbitMQ UI -> Queues)
```

### Test af transactional outbox (broker nede)

Events skrives til tabellen `outbox_event` i samme transaktion som tilstandsændringen og sendes videre af et
relay, så en mutation lykkes selv om RabbitMQ er nede – og eventet leveres når brokeren er tilbage:

```bash
docker compose stop rabbitmq
# lav en booking i frontenden (lykkes: bookingen er PENDING_PAYMENT, men payment/baggage har ikke hørt om den)
docker compose exec booking-db psql -U booking -d booking_db \
  -c 'select id, event_type, attempts, published_at, last_error from outbox_event'
# -> booking.created står med published_at = NULL og attempts der tæller op
docker compose start rabbitmq
# få sekunder senere: published_at er sat, og baggage-service kender bookingen (Flow B kan fortsætte)
# /actuator/metrics kræver et OPERATIONS-token (se Login og roller)
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8082/actuator/metrics/outbox.pending \
  | jq '.measurements[0].value'   # 0 = tom backlog
```

### Kør en enkelt service uden Docker (udvikling)

```bash
docker compose up -d flight-db rabbitmq      # infrastruktur
cd flight-service && mvn spring-boot:run     # bruger dev-defaults i application.yml (localhost:5433)
```

## Tests

Hver service har unit tests for domænelogik, en integrationstest med Testcontainers (rigtig PostgreSQL 16 +
RabbitMQ) og en `SecurityIntegrationTest` (uden token → `UNAUTHORIZED`, forkert rolle → `FORBIDDEN`, gyldigt token →
data, udløbet/fremmed token → HTTP 401). Testene minter selv JWT'er (`TestTokens`), så Keycloak ikke skal køre.
Docker skal køre. Alle tests køres også af CI ved hvert push – se
[CI (GitHub Actions)](#ci-github-actions).

```bash
cd flight-service  && mvn test
cd booking-service && mvn test
cd payment-service && mvn test
cd baggage-service && mvn test
cd shop-service    && mvn test
```

| Service         | Unit tests                                     | Integrationstest dækker                                                 |
|-----------------|------------------------------------------------|-------------------------------------------------------------------------|
| flight-service  | `PricingService`, `SeatGenerator`              | seed, filtre, `booking.confirmed` → sæde optaget (idempotent), aflysning publicerer events, validering, outbox (rollback, relay-retry, publish uden transaktion) |
| booking-service | bookingreference, tilstandsovergange           | createBooking → `payment.completed` → CONFIRMED, SEAT_TAKEN, `flight.cancelled` |
| payment-service | `PaymentSimulator` (0000, udløbet kort)        | pay → COMPLETED/FAILED events, refund ved `booking.cancelled`, kortnummer gemmes ikke |
| baggage-service | `BaggageRules` (3 stk., 32 kg), tag-format     | snapshot via events, register/limit, statusopdatering, RETURN_DESK ved aflysning, idempotency key på `registerBaggage` (samme nøgle → én bagage) |
| shop-service    | `Dijkstra`, `OpeningHours`                     | rute Security T2 → Gate B12, accessibleOnly, søgning, CRUD, idempotens, tombstone ved `deleteShop` (væk fra alle queries og ruter, rækken findes stadig), idempotency key på `createShop` |

### Systemtest: services der samarbejder i containere

`system-tests/` er et separat Maven-modul, der starter de **byggede images** af flight-, booking- og payment-service
sammen med RabbitMQ og PostgreSQL (Testcontainers) og tester samarbejdet udefra – GraphQL ind, events på
`airport.events` ud. Keycloaks JWKS-endpoint er en WireMock-stub, og testene minter selv tokens med en testnøgle, så
sikkerheden kører præcis som i drift.

| Test | Viser |
|------|-------|
| `paidBookingIsConfirmedThroughPaymentCompletedEvent` | `createBooking` (pris hentes synkront fra flight-service) → `pay` → `payment.completed` → bookingen bliver `CONFIRMED`, `booking.confirmed` ses på exchangen, og flight-service markerer sædet optaget |
| `cancelledFlightCancelsEveryBookingOnTheFlight` | `updateFlightStatus(CANCELLED)` i flight-service → `flight.cancelled` → alle bookinger på flyet (betalt og ubetalt) bliver `CANCELLED`, og den betalte refunderes (`payment.refunded`) |
| `declinedCardFailsPaymentAndCancelsBooking` | kort der slutter på 0000 → `payment.failed` → bookingen annulleres med årsagen fra eventet |

Testene kører kun med profilen `system` og kræver, at images er bygget (ca. 2 minutter):

```bash
docker compose build flight-service booking-service payment-service
cd system-tests && mvn -Psystem verify      # uden -Psystem springes testene over
```

Containernes egne logs vises med `-Dcontainer.log.level=INFO`. I CI kører systemtestene i `system`-jobbet efter
image-buildet og smoke-testen.

### CI (GitHub Actions)

Pipelinen ligger i [.github/workflows/ci.yml](.github/workflows/ci.yml) og kører ved hvert push til alle branches
og på pull requests mod `main`. Et nyt push til samme branch afbryder den kørsel, der stadig er i gang – undtagen på
`main`, hvor hvert commit får en hel kørsel. Seks uafhængige jobs:

| Job         | Hvad |
|-------------|------|
| `backend`   | Én matrix-kørsel pr. service (fem i alt): `mvn -Pci verify` = unit tests + integrationstests med Testcontainers (Docker findes på `ubuntu-latest`), **Checkstyle** og **SpotBugs/find-sec-bugs**. Surefire-, Checkstyle- og SpotBugs-rapporterne uploades som artifact `reports-<service>`, også når noget fejler. |
| `frontend`  | `npm ci && npx eslint js/` i `frontend/` (ESLint *recommended* + browser-globals; frontenden har intet build-step) |
| `manifests` | `kubectl kustomize` + `kubeconform -strict` mod Kubernetes-API-skemaerne for hver kustomization under `k8s/` (standardstakken og `k8s/tools/`) |
| `contracts` | `docs/asyncapi.yaml` valideres af AsyncAPI CLI (også warnings fejler), og `scripts/check-asyncapi.sh` tjekker, at hvert eventnavn og hver kø i koden findes i kontrakten |
| `security`  | **gitleaks** over hele git-historikken (`fetch-depth: 0`) og **Trivy** over alle seks byggede images (Alpine-pakker + hver jar i imaget). HIGH/CRITICAL-fund med en rettelse gør jobbet rødt |
| `system`    | Hele systemet som en bruger kører det: `docker compose build` (alle seks images) + `up --wait`, `scripts/e2e-smoke.sh` (Flow A–D gennem Keycloak, RabbitMQ og alle services), `docker compose down -v` og derefter systemtestene (`mvn -Psystem verify` i `system-tests/`). Fejler noget, uploades `docker compose logs` som artifact |

En fejlende test – unit, integration eller system – eller et enkelt fund fra Checkstyle, SpotBugs, ESLint,
kubeconform, AsyncAPI, gitleaks eller Trivy gør sit job rødt og dermed hele pipelinen rød. `fail-fast` er slået fra
i matrixen, så de andre services stadig bliver testet færdigt, og alle fejl kan ses i én kørsel.

**Statisk analyse.** Maven-profilen `ci` findes i alle fem poms og bruger `config/checkstyle.xml` (Google-stil med 4
spaces og 120 tegn) og `config/spotbugs-exclude.xml` (hver undtagelse er begrundet i filen). Uden `-Pci` er
`mvn package` og Docker-buildet uændret.

**Sikkerhedsscanning.** gitleaks og Trivy installeres som release-binærer, fastlåst på version *og* SHA-256, i stedet
for via deres GitHub Actions: en action kører med workflowets token og kan ændre sig bag et flyttet tag, det kan en
checksum ikke. Accepterede fund står med begrundelse i [.gitleaks.toml](.gitleaks.toml) (dev-brugeren
`airport:airport` i curl-eksemplerne og én falsk positiv på en anden branch) og [.trivyignore.yaml](.trivyignore.yaml).
Den første image-scanning (18-11-2026) fandt CRITICAL/HIGH-sårbarheder i Tomcat, Jackson, PostgreSQL-driveren og
RabbitMQ-klienten, som Spring Boot 3.5.16 stadig styrer – de er løftet med versions-properties i hver pom
(`tomcat.version`, `jackson-bom.version`, `postgresql.version`, `rabbit-amqp-client.version`, `netty.version`,
`graphql-java.version`) med CVE-numrene i en kommentar, og images kører `apk upgrade` oven på base-imaget.

**Hvor ses resultatet?** Badget øverst i README'en viser status for seneste kørsel på `main` og linker til
[workflowets side under Actions](https://github.com/FrederikGJ/DLS_exam_project/actions/workflows/ci.yml), hvor
hver kørsel, hvert job og hvert trins log kan åbnes. På en pull request står jobbene som checks nederst i PR'en, og
ved et push vises ✓/✗ ud for commit'et. Testrapporterne hentes under *Artifacts* på kørslens side.

At en fejlende test faktisk gør pipelinen rød, er vist med en bevidst rød test på en midlertidig branch – se
[docs/ci-red-test/](docs/ci-red-test/README.md).

Kør det samme lokalt:

```bash
cd flight-service && mvn -Pci verify          # som backend-jobbet: tests + Checkstyle + SpotBugs
cd frontend && npm ci && npx eslint js/       # som frontend-jobbet
kubectl kustomize k8s/ | kubeconform -strict -summary
npx --yes @asyncapi/cli@6.1.0 validate --fail-severity=warn docs/asyncapi.yaml && ./scripts/check-asyncapi.sh
gitleaks git --config .gitleaks.toml --redact .
docker compose build && trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile .trivyignore.yaml airport/flight-service:local
docker compose up -d --wait && ./scripts/e2e-smoke.sh && docker compose down -v
cd system-tests && mvn -Psystem verify
```

## Kubernetes

Manifests ligger i `k8s/` (Kustomize): namespace `airport`, Deployment (2 replicas) + Service + ConfigMap +
Secret pr. backend-service, StatefulSet + PVC + Service pr. database, RabbitMQ StatefulSet, frontend og én Ingress.
Keycloak (login) ligger bag Ingress'en på `/auth`. pgAdmin (dev/demo-værktøj uden login) er **ikke** med i
standardstakken; den tilvælges på et lokalt cluster med `kubectl apply -k k8s/tools/` (se [k8s/README.md](k8s/README.md)).

Manifests er verificeret på et **kind**-cluster (17/17 pods Ready, alle flows grønne gennem Ingress). Kort version for
minikube – se
[k8s/README.md](k8s/README.md) for detaljer og kind-alternativet:

```bash
minikube start --cpus 4 --memory 8192
minikube addons enable ingress

# byg images direkte i minikubes Docker
eval $(minikube docker-env)
docker compose build

kubectl apply -k k8s/
kubectl -n airport get pods -w          # vent til alle er Running/Ready

# åbn frontend
minikube ip                             # -> http://<ip>/
```

Ingress-routing:

| Path (pathType: Prefix) | Service          | Dækker bl.a.            |
|-------------------------|------------------|-------------------------|
| `/`                     | frontend         | index.html, css/, js/   |
| `/api/flights`          | flight-service   | `/api/flights/graphql`  |
| `/api/bookings`         | booking-service  | `/api/bookings/graphql` |
| `/api/payments`         | payment-service  | `/api/payments/graphql` |
| `/api/baggage`          | baggage-service  | `/api/baggage/graphql`  |
| `/api/shops`            | shop-service     | `/api/shops/graphql`    |
| `/auth`                 | keycloak         | login, admin console `/auth/admin/` |

pgAdmin får sin egen Ingress-regel (`/pgadmin`) fra `k8s/tools/`, kun når den er tilvalgt.

I Kubernetes erstattes `frontend/js/config.js` af en ConfigMap med relative paths (`/api/.../graphql`),
så frontend og API deler origin.

## Konfiguration

Alle services konfigureres via environment variables. Defaults i `application.yml` gælder kun lokal udvikling.

| Variabel               | Betydning                                  | Eksempel (compose)                          |
|------------------------|--------------------------------------------|---------------------------------------------|
| `DB_URL`               | JDBC-url til servicens database            | `jdbc:postgresql://flight-db:5432/flight_db` |
| `DB_USERNAME` / `DB_PASSWORD` | DB-credentials                      | fra Secret i k8s                            |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | RabbitMQ                        | `rabbitmq` / `5672`                         |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ-credentials    | fra Secret i k8s                            |
| `CORS_ALLOWED_ORIGINS` | Kommasepareret liste af tilladte origins   | `http://localhost:8080`                     |
| `GRAPHQL_PATH`         | Path GraphQL-endpointet serveres på        | `/api/flights/graphql`                      |
| `SPRING_PROFILES_ACTIVE` | `dev` (læsbare logs, GraphiQL) / `prod` (JSON-logs) | `dev`                             |
| `FLIGHT_SERVICE_URL`   | Kun booking-service: flight-service GraphQL | `http://flight-service:8080/api/flights/graphql` |
| `OIDC_ISSUER_URI`      | Den `iss`, tokens skal have (Keycloaks browser-vendte URL + realm) | `http://localhost:8180/realms/airport` |
| `JWK_SET_URI`          | Hvor servicen henter Keycloaks signeringsnøgler (intern adresse) | `http://keycloak:8080/realms/airport/protocol/openid-connect/certs` |

## Repository-struktur

```
/
  README.md                docker-compose.yml
  .github/workflows/       ci.yml – GitHub Actions: backend (matrix), frontend, manifests, contracts, security, system
  .gitleaks.toml           .trivyignore.yaml – begrundede undtagelser for gitleaks og Trivy
  config/                  checkstyle.xml, spotbugs-exclude.xml – regler for Maven-profilen `ci`
  docs/                    architecture.md, events.md, asyncapi.yaml (eventkontrakten maskinlæsbart), service-readme-template.md
  frontend/                Dockerfile, nginx.conf, index.html, silent-check-sso.html, css/, js/ (config.js, auth.js, api.js, app.js, pages/, vendor/keycloak.js), package.json + eslint.config.js (kun lint)
  flight-service/          pom.xml, Dockerfile, src/main/java/dk/airport/flight/{domain,repository,service,graphql,messaging,config}
  booking-service/         ... dk/airport/booking/...
  payment-service/         ... dk/airport/payment/...
  baggage-service/         ... dk/airport/baggage/...
  shop-service/            ... dk/airport/shop/...
    (hver: README.md, src/main/resources/graphql/schema.graphqls, db/migration/V1__init.sql (+V2__seed.sql), src/test/java)
  k8s/                     kustomization.yaml, namespace.yaml, rabbitmq/, databases/, services/, frontend/, keycloak/ (+ realm-airport.json), ingress.yaml
                           tools/ (pgAdmin, opt-in: kubectl apply -k k8s/tools/)
  scripts/e2e-smoke.sh     end-to-end smoke-test af Flow A-D mod en kørende compose-stak
  scripts/check-asyncapi.sh  tjekker, at alle events og køer i koden står i docs/asyncapi.yaml (CI-job contracts)
  system-tests/            Maven-modul: systemtest med de byggede images (mvn -Psystem verify)
```

## Designvalg og afvigelser

De valg spec'en lod være åbne er samlet i [docs/architecture.md](docs/architecture.md#designvalg-hvor-specen-gav-frihed).
De vigtigste:

- **Pris i booking-service** hentes synkront fra flight-service via GraphQL ved `createBooking`
  (`flight(id){ seat(seatNumber){ isAvailable price } }`). Sædeprisen er `basePrice × klassemultiplikator`.
- **Topic-bindings bruger `#`** (`booking.#`, `flight.#`), fordi `*` kun matcher ét segment og
  fx `flight.status.changed` har tre.
- **`pay` returnerer et `Payment`-objekt også ved afvist kort** (`status: FAILED`, `failureReason`), så
  frontenden kan vise årsagen; bookingen annulleres asynkront via `payment.failed`.
- **Fælles event-envelope** er en identisk record i hver service (ingen delt bibliotek), så hver service
  kan bygges alene med sin egen Dockerfile.
- **Transactional outbox**: events skrives til `outbox_event` i samme transaktion som tilstandsændringen og
  sendes af et relay med publisher confirms. At-least-once + idempotente consumers (`processed_event`)
  giver effektivt exactly-once, også når RabbitMQ er nede. Se [docs/events.md](docs/events.md#leveringsgarantier).
- **Login med Keycloak**, roller i tokenet og autorisation pr. operation (`@PreAuthorize`); læsning af afgange og
  butikker er offentlig, alt med persondata kræver login. Se [Sikkerhed](docs/architecture.md#sikkerhed-login-og-roller).
- **Ingen hardcodede secrets**: compose-filen indeholder kun dev-defaults; i Kubernetes kommer alle
  credentials fra `Secret`-objekter (dev-værdier i repoet – også Keycloak-admin og testbrugerne – udskiftes i et
  rigtigt miljø).

## Licens

Copyright © 2026 Mahdi Karimi, Lukas Rønberg og Frederik Johannessen.

Projektet er fri software under **GNU General Public License v3.0 (GPL-3.0)** – se [LICENSE](LICENSE).
Det må frit bruges, ændres og videredistribueres, så længe afledte værker udgives under samme licens.
