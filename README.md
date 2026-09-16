# DLS_exam_project – Lufthavnssystem (microservices)

[![CI](https://github.com/FrederikGJ/DLS_exam_project/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/FrederikGJ/DLS_exam_project/actions/workflows/ci.yml)

Eksamensprojekt i *Development of Large Systems*, Softwareudvikling bachelor 2026 efterår.

**Gruppe 7:** Mahdi Karimi · Lukas Rønberg · Frederik Johannessen

Et lufthavnssystem bestående af én frontend og fem uafhængige backend-microservices, der kan:

- vise flyselskaber og afgange
- lade en passager booke en plads på et fly
- tage imod (simuleret) betaling for en booking
- registrere bagage og binde den til en passager/booking
- vise lufthavnens butikker og navigere passageren til dem (Dijkstra)

Dokumentation: [docs/architecture.md](docs/architecture.md) (diagram, flows, designvalg) ·
[docs/events.md](docs/events.md) (alle events med eksempler) · [k8s/README.md](k8s/README.md) (minikube/kind).

## Tech stack

| Del                 | Teknologi                                                             |
|---------------------|-----------------------------------------------------------------------|
| Frontend            | Vanilla JavaScript, HTML, CSS – serveret af nginx (ingen frameworks)  |
| Backend             | Java 21, Spring Boot 3.5 (Maven)                                       |
| API                 | GraphQL (Spring for GraphQL) + versioneret REST v1 i `baggage-service` (OpenAPI/springdoc) |
| Database            | PostgreSQL 16 – én database pr. service, migrationer med Flyway       |
| Message broker      | RabbitMQ (Spring AMQP) – topic exchange `airport.events`              |
| Containerisering    | Docker, multi-stage builds (maven → eclipse-temurin JRE)              |
| Orkestrering        | Kubernetes (Kustomize), verificeret på kind – minikube-kommandoer i k8s/README.md |
| Tests               | JUnit 5, Testcontainers (Postgres + RabbitMQ), Spring GraphQL Tester, WireMock (system-test) |

## Komponenter

| Service           | Ansvar                                            | Port (compose) | GraphQL-endpoint                          |
|-------------------|---------------------------------------------------|----------------|-------------------------------------------|
| `frontend`        | SPA: afgange, book, betaling, min booking, bagage, butikker | 8080  | –                                          |
| `flight-service`  | Flyselskaber, fly, afgange, sæder (kilde til sandhed) | 8081       | `http://localhost:8081/api/flights/graphql` |
| `booking-service` | Passagerer og bookinger, orkestrerer bookingflowet | 8082          | `http://localhost:8082/api/bookings/graphql` |
| `payment-service` | Simuleret betalingsgateway, refunds               | 8083           | `http://localhost:8083/api/payments/graphql` |
| `baggage-service` | Bagage bundet til booking, status-tracking, **REST v1** | 8084      | `http://localhost:8084/api/baggage/graphql` + REST `/api/baggage/v1` |
| `shop-service`    | Butikker + navigation (Dijkstra)                  | 8085           | `http://localhost:8085/api/shops/graphql` |
| RabbitMQ          | Events mellem services                            | 5672 / 15672   | Management UI: http://localhost:15672 (airport/airport) |
| Keycloak          | OpenID Connect-login, roller PASSENGER/OPERATIONS | 8180           | http://localhost:8180/realms/airport (admin: /admin/, admin/admin) |
| PostgreSQL ×5     | `flight_db`, `booking_db`, `payment_db`, `baggage_db`, `shop_db` | 5433–5437 | – |

Hver service har GraphiQL på `http://localhost:808x/graphiql?path=/api/<x>/graphql` i dev-profilen
og health-endpoints på `/actuator/health/liveness` og `/actuator/health/readiness`.

## API'er: GraphQL og REST

Fire services taler udelukkende GraphQL. `baggage-service` har **derudover** et versioneret REST-API v1 på
`/api/baggage/v1` oven på præcis den samme forretningslogik – samme regler, samme events, samme roller – så
systemet demonstrerer begge API-stilarter. Frontendens bagage-side kalder REST; booking-snapshottet hentes
stadig over GraphQL, så begge kald kan ses i browserens netværksfane.

| Metode  | Sti                                              | Rolle                | Svar ved succes    |
|---------|--------------------------------------------------|----------------------|--------------------|
| `POST`  | `/api/baggage/v1/baggage`                        | PASSENGER/OPERATIONS | `201` + `Location` |
| `GET`   | `/api/baggage/v1/baggage/{tagNumber}`            | offentlig            | `200`              |
| `GET`   | `/api/baggage/v1/bookings/{reference}/baggage`   | PASSENGER/OPERATIONS | `200` (liste)      |
| `PATCH` | `/api/baggage/v1/baggage/{tagNumber}/status`     | OPERATIONS           | `200`              |

Fejl er RFC 9457 problem details (`application/problem+json`) med et ekstra felt `code`, der bruger samme
vokabular som GraphQL's `errors[].extensions.code`. En klient kan altså behandle fejl ens uanset API-stil:

| HTTP  | `code`                                       | Hvornår                                                          |
|-------|----------------------------------------------|------------------------------------------------------------------|
| `400` | `VALIDATION_ERROR`                           | Forkert form: manglende felt, ugyldig reference, ulæselig JSON     |
| `401` | `UNAUTHORIZED`                               | Manglende, udløbet eller ugyldigt token                           |
| `403` | `FORBIDDEN`                                  | Gyldigt token, men rollen tillader ikke handlingen                |
| `404` | `NOT_FOUND`                                  | Ukendt bagagetag eller ukendt booking                             |
| `409` | `INVALID_STATE`, `CONFLICT`                  | Bookingen er ikke betalt/bekræftet; data konflikter               |
| `422` | `VALIDATION_ERROR`, `BAGGAGE_LIMIT_EXCEEDED` | Formen er rigtig, men en forretningsregel siger nej (vægt, antal) |
| `500` | `INTERNAL_ERROR`                             | Uventet fejl                                                      |

### OpenAPI og Swagger UI

Beskrivelsen genereres af springdoc ud fra controlleren, så den ikke kan komme bagud i forhold til koden:

| Hvad                 | docker compose                             | kind (Ingress)                                  |
|----------------------|--------------------------------------------|-------------------------------------------------|
| Swagger UI           | http://localhost:8084/swagger-ui.html      | http://localhost:8090/swagger-ui/index.html     |
| OpenAPI (JSON)       | http://localhost:8084/v3/api-docs          | http://localhost:8090/v3/api-docs               |
| OpenAPI (YAML)       | http://localhost:8084/v3/api-docs.yaml     | http://localhost:8090/v3/api-docs.yaml          |

En eksporteret kopi ligger i [docs/openapi/baggage-v1.yaml](docs/openapi/baggage-v1.yaml). I Swagger UI trykker
man **Authorize** og indsætter et access token (se `token`-funktionen i `scripts/e2e-smoke.sh`) – derefter sender
UI'et det som `Authorization: Bearer ...`.

### Prøv REST-API'et med curl

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=airport-frontend -d username=anna -d password=anna \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)

# registrér bagage på en bekræftet booking (K7Q2ZP = din reference fra Flow A)
curl -i -X POST http://localhost:8084/api/baggage/v1/baggage \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"bookingReference":"K7Q2ZP","weightKg":23.0,"type":"CHECKED"}'
# -> 201 Created, Location: /api/baggage/v1/baggage/BAG-XXXXXXXX

curl -s http://localhost:8084/api/baggage/v1/baggage/BAG-XXXXXXXX | jq          # offentligt opslag
curl -s -X POST http://localhost:8084/api/baggage/v1/baggage \
  -H 'Content-Type: application/json' -d '{}' | jq                              # -> 401 UNAUTHORIZED
```

Se [baggage-service/README.md](baggage-service/README.md) for alle endpoints og versioneringsreglerne.

## Kør lokalt med Docker

Krav: Docker med Compose v2 (Docker Desktop eller docker-ce + compose-plugin). Intet andet skal installeres.

```bash
docker compose up --build
```

Første build tager nogle minutter (Maven downloader dependencies i build-containerne). Når alle
services melder `healthy`, åbn **http://localhost:8080**.

Login sker via Keycloak på **http://localhost:8180** (realm `airport`, admin console `/admin/` med admin/admin).
Testbrugere: `anna`/`anna` (rolle PASSENGER) og `ops`/`ops` (rolle OPERATIONS). Realm'et importeres fra
`k8s/keycloak/realm-airport.json` ved hver opstart – samme fil som Kubernetes bruger (se
[k8s/README.md, Keycloak](k8s/README.md#keycloak-login), også om issuer-faldgruben).

Seed-data indlæses automatisk af Flyway ved første opstart: 3 flyselskaber, 5 fly, 10 afgange med sæder,
2 terminaler, 15+ butikker og et navigationsnetværk med 30+ noder inkl. gates.

Nyttige kommandoer:

```bash
docker compose ps                         # status/health
docker compose logs -f booking-service    # logs for én service
docker compose down -v                    # stop og slet databaser (nulstil seed-data)
```

### AI: sprogmodellen bag "Spørg om vej"

`shop-service`s `askRoute` bruger en **lokal** sprogmodel, der kører i sin egen container (Ollama med
`qwen2.5:1.5b` bagt ind i imaget – ingen API-nøgler, intet data forlader maskinen). Den er valgfri og ligger bag
compose-profilen `ai`:

```bash
docker compose up --build                  # uden AI: askRoute svarer med nøgleordssøgning (aiUsed=false)
docker compose --profile ai up --build     # med AI:  askRoute svarer med modellen  (aiUsed=true)
```

Første `--profile ai`-build henter modellen (~1 GB) ind i imaget; derefter starter containeren uden download.
`shop-service` har **ikke** `depends_on` på den: servicen er sund uden AI, og et spørgsmål besvares så af
nøgleordssøgningen med en forklaring i `fallbackReason`. Skift model med
`docker compose build --build-arg OLLAMA_MODEL=<navn> ollama` og sæt `OLLAMA_MODEL` for `shop-service`.
I Kubernetes tændes modellen med overlayet `k8s/overlays/demo` (se [k8s/README.md](k8s/README.md)).

### Prøv flows fra frontenden

Læsning (afgange, butikker, opslag af booking) kræver ikke login. *Book*, *Betaling* og *Bagage* sender dig til
Keycloaks login-side (brug `anna`/`anna`) og tilbage igen; *Log ind*/*Log ud* står øverst til højre sammen med
brugernavn og rolle. Operations-panelet under *Afgange* vises kun for brugere med rollen OPERATIONS (`ops`/`ops`).

- **Flow A – booking og betaling:** *Afgange* → *Book* på et fly → udfyld passager, vælg sæde → *Bekræft booking*
  → *Betaling*: brug fx kort `4242 4242 4242 4242`, udløb `12/30`, cvv `123` → bookingen bliver `CONFIRMED`.
  Kort der slutter på `0000` giver `Insufficient funds`; udløbet dato giver `Card expired` (bookingen annulleres).
- **Flow B – bagage:** *Bagage* → indtast bookingreference, vægt 23, type CHECKED → tag genereres →
  opdatér status til `LOADED` / "Belt 4" → se det under *Min booking*.
- **Flow C – aflysning:** log ind som `ops` → *Afgange* → slå *Vis operations-panel* til → sæt status `CANCELLED` på flyet →
  bookinger annulleres, betalinger refunderes, bagage sendes til `RETURN_DESK`, sæder frigives.
- **Flow D – navigation:** *Butikker* → vælg "Security T2" → "Gate B12" → *Find rute* → trin-for-trin rute,
  afstand, estimeret tid, butikker undervejs og et SVG-kort med gangnetværk, nummererede trin, instruktionstekst,
  afstand pr. delstrækning og retningspile.
- **Flow E – spørg om vej (AI):** *Butikker* → vælg hvor du er → skriv fx
  *"Hvor finder jeg en kop kaffe på vej til gate B12?"* → *Spørg om vej*. En lokal sprogmodel (Ollama) oversætter
  spørgsmålet til én butik + et evt. mål, og ruten tegnes som i Flow D. Svaret viser, hvordan spørgsmålet blev
  forstået, og om det var modellen eller nødløsningen (nøgleordssøgning), der svarede. Kræver
  `docker compose --profile ai up` – uden den svarer nøgleordssøgningen, og siden siger det tydeligt.

### Automatisk smoke-test af Flow A–D

Med stakken kørende kan alle fire flows køres end-to-end fra kommandolinjen (kræver `curl` og `jq`):

```bash
./scripts/e2e-smoke.sh
```

Scriptet henter først tokens fra Keycloak for `anna` (PASSENGER) og `ops` (OPERATIONS) med password grant,
tjekker at en mutation uden token giver `UNAUTHORIZED` og at anna får `FORBIDDEN` på en OPERATIONS-mutation,
og kører så flows: anna booker et sæde, betaler og registrerer bagage, ops sætter bagagestatus og aflyser flyet,
og scriptet verificerer at bookingen bliver `CANCELLED`, betalingen `REFUNDED`, bagagen står ved `RETURN_DESK` og
sædet er frigivet – og slutter med en rute fra Security T2 til Gate B12. Mod kind sættes `KEYCLOAK_URL` og
service-URL'erne som vist i [k8s/README.md](k8s/README.md#alternativ-kind). Bemærk at Flow C aflyser et fly fra
seed-data; `docker compose down -v` nulstiller.

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
curl -s localhost:8082/actuator/metrics/outbox.pending | jq '.measurements[0].value'   # 0 = tom backlog
```

### Kør en enkelt service uden Docker (udvikling)

```bash
docker compose up -d flight-db rabbitmq      # infrastruktur
cd flight-service && mvn spring-boot:run     # bruger dev-defaults i application.yml (localhost:5433)
```

## Tests

Hver service har unit tests for domænelogik og én integrationstest med Testcontainers
(rigtig PostgreSQL 16 + RabbitMQ). Docker skal køre. Integrationstestene kalder GraphQL over HTTP gennem
Spring Securitys filterkæde; mutations sendes med et test-JWT fra `TestTokens` (`passenger()` = anna,
`operations()` = ops), som signeres med en testnøgle, så Keycloak ikke behøver køre.

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
| baggage-service | `BaggageRules` (3 stk., 32 kg), tag-format     | snapshot via events, register/limit, statusopdatering, RETURN_DESK ved aflysning |
| shop-service    | `Dijkstra`, `OpeningHours`                     | rute Security T2 → Gate B12, accessibleOnly, søgning, CRUD, idempotens |

### System-test (booking ↔ payment)

`system-tests/` starter de byggede images af booking-service og payment-service sammen med RabbitMQ og PostgreSQL
(Testcontainers) og verificerer samarbejdet udefra: createBooking → pay → `payment.completed` → CONFIRMED, afvist kort
→ `payment.failed` → CANCELLED, og cancelBooking → `payment.refunded`. flight-service og Keycloaks JWKS-endpoint er
WireMock-stubs; tokens udstedes af en testnøgle, som services'ne validerer ad præcis samme vej som mod Keycloak.
Kræver byggede images og springes over, hvis de mangler. Kører på ca. 50 sekunder.

```bash
docker compose build
cd system-tests && mvn verify        # -Pci for også Checkstyle + SpotBugs
```

Se [system-tests/README.md](system-tests/README.md).

### CI og statisk analyse

GitHub Actions ([.github/workflows/ci.yml](.github/workflows/ci.yml)) kører ved hvert push og på pull requests
mod `main`; et nyt push til samme branch afbryder den kørsel, der stadig er i gang. Tre uafhængige jobs:

| Job         | Hvad                                                                                                          |
|-------------|---------------------------------------------------------------------------------------------------------------|
| `backend`   | Én matrix-kørsel pr. service: `mvn -Pci verify` = unit + Testcontainers-tests, Checkstyle og SpotBugs/find-sec-bugs. Surefire-, Checkstyle- og SpotBugs-rapporter uploades som artifacts. |
| `frontend`  | `npm ci && npx eslint js/` i `frontend/` (ESLint *recommended* + browser-globals; frontenden har intet build-step) |
| `manifests` | `kubectl kustomize` + `kubeconform -strict` mod Kubernetes-API-skemaerne for hver kustomization under `k8s/`   |

Maven-profilen `ci` findes i alle fem poms og bruger `config/checkstyle.xml` (Google-stil med 4 spaces og
120 tegn) og `config/spotbugs-exclude.xml` (hver undtagelse er begrundet i filen). Uden `-Pci` er
`mvn package`/Docker-buildet uændret. Kør det samme lokalt:

```bash
cd flight-service && mvn -Pci verify          # som pipelinen: tests + Checkstyle + SpotBugs
cd frontend && npm ci && npx eslint js/
kubectl kustomize k8s/ | kubeconform -strict -summary
```

## Kubernetes

Manifests ligger i `k8s/` (Kustomize): namespace `airport`, Deployment (1 replica, dimensioneret til en laptop – se
[Ressourcer på en laptop](k8s/README.md#ressourcer-på-en-laptop)) + Service + ConfigMap + Secret pr. backend-service, StatefulSet + PVC + Service pr. database, RabbitMQ StatefulSet, frontend og én Ingress.
Selve systemet ligger i `k8s/base/` (`kubectl apply -k k8s/`); pgAdmin som dev/demo-værktøj på `/pgadmin` ligger i
`k8s/tools/` og deployes kun med overlayet `kubectl apply -k k8s/overlays/dev-tools/` – det er ikke en del af selve systemet.

Manifests er verificeret på et **kind**-cluster (13/13 pods Ready efter ca. 70 s på en laptop, alle flows grønne gennem Ingress). Kort version for
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
| `/pgadmin`              | pgadmin          | kun med overlayet `dev-tools`: pgAdmin UI, åbner uden login |

I Kubernetes erstattes `frontend/js/config.js` af en ConfigMap med relative paths (`/api/.../graphql`),
så frontend og API deler origin.

## Login og roller

Login sker via **Keycloak** (OpenID Connect, realm `airport`). Frontenden er en public client med Authorization Code
+ PKCE; de fem services er resource servers, der validerer JWT'et og læser rollen i `realm_access.roles`.
Realm'et importeres fra `k8s/keycloak/realm-airport.json` ved hver opstart – samme fil i compose og Kubernetes.

| Bruger  | Kode   | Rolle        | Må                                                                          |
|---------|--------|--------------|-----------------------------------------------------------------------------|
| –       | –      | (ingen)      | læse: afgange, sæder, butikker, ruter, booking/bagage/betaling pr. reference/id |
| `anna`  | `anna` | `PASSENGER`  | booke, betale, checke ind, annullere, registrere bagage, se egne bookinger (`bookingsByPassenger` kun med egen e-mail) |
| `ops`   | `ops`  | `OPERATIONS` | alt ovenstående for alle + ændre flystatus/gate, opdatere bagagestatus, refundere, vedligeholde butikker |

Fejlkoder: `UNAUTHORIZED` (operationen kræver login), `FORBIDDEN` (forkert rolle); et udløbet eller forkert token
afvises med HTTP 401. Hele rolletabellen pr. operation står i [docs/architecture.md](docs/architecture.md).

Hent et token til scripts og `curl` (password grant er slået til på clienten `airport-frontend` netop til det):

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"{ bookingsByPassenger(email: \"anna@example.com\") { bookingReference status } }"}' \
  http://localhost:8082/api/bookings/graphql
```

Services'ne konfigureres med `OIDC_ISSUER_URI` (den issuer et token skal have – Keycloaks browser-vendte URL +
`/realms/airport`) og `JWK_SET_URI` (hvor signeringsnøglerne hentes – en intern adresse). De to er forskellige,
fordi browseren og containerne når Keycloak på hver sin adresse; faldgruben er beskrevet i
[k8s/README.md](k8s/README.md#keycloak-login). Admin console: compose <http://localhost:8180/admin/>, kind
<http://localhost:8090/auth/admin/> (admin/admin). GitHub-login kan slås til som identity provider – se
docs/architecture.md.

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
| `OIDC_ISSUER_URI`      | Issuer (`iss`) et JWT skal have – Keycloaks browser-vendte URL + `/realms/airport` | `http://localhost:8180/realms/airport` |
| `JWK_SET_URI`          | Hvor servicen henter Keycloaks signeringsnøgler (intern adresse) | `http://keycloak:8080/realms/airport/protocol/openid-connect/certs` |
| `SPRING_PROFILES_ACTIVE` | `dev` (læsbare logs, GraphiQL) / `prod` (JSON-logs) | `dev`                             |
| `FLIGHT_SERVICE_URL`   | Kun booking-service: flight-service GraphQL | `http://flight-service:8080/api/flights/graphql` |

## Repository-struktur

```
/
  README.md                docker-compose.yml
  .github/workflows/       ci.yml – GitHub Actions: backend (matrix), frontend (eslint), manifests (kubeconform)
  config/                  checkstyle.xml, spotbugs-exclude.xml – regler for Maven-profilen `ci`
  docs/                    architecture.md, events.md
  frontend/                Dockerfile, nginx.conf, index.html, css/, js/ (config.js, api.js, app.js, pages/), eslint.config.js + package.json (kun lint)
  flight-service/          pom.xml, Dockerfile, src/main/java/dk/airport/flight/{domain,repository,service,graphql,messaging,config}
  booking-service/         ... dk/airport/booking/...
  payment-service/         ... dk/airport/payment/...
  baggage-service/         ... dk/airport/baggage/...
  shop-service/            ... dk/airport/shop/...
    (hver: src/main/resources/graphql/schema.graphqls, db/migration/V1__init.sql (+V2__seed.sql), src/test/java)
  k8s/                     base/ (namespace, rabbitmq/, databases/, services/, frontend/, ingress.yaml), keycloak/ (realm-airport.json), tools/ (pgAdmin), overlays/dev-tools/
  scripts/e2e-smoke.sh     end-to-end smoke-test af Flow A-D mod en kørende compose-stak
  system-tests/            system-test af booking ↔ payment med de byggede images (Testcontainers + WireMock)
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
- **Ingen hardcodede secrets**: compose-filen indeholder kun dev-defaults; i Kubernetes kommer alle
  credentials fra `Secret`-objekter (dev-værdier i repoet, udskiftes i et rigtigt miljø).

## Licens

Copyright © 2026 Mahdi Karimi, Lukas Rønberg og Frederik Johannessen.

Projektet er fri software under **GNU General Public License v3.0 (GPL-3.0)** – se [LICENSE](LICENSE).
Det må frit bruges, ændres og videredistribueres, så længe afledte værker udgives under samme licens.
