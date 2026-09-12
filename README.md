# DLS_exam_project – Lufthavnssystem (microservices)

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
| `shop-service`    | Butikker + navigation (Dijkstra)                  | 8085           | `http://localhost:8085/api/shops/graphql` |
| RabbitMQ          | Events mellem services                            | 5672 / 15672   | Management UI: http://localhost:15672 (airport/airport) |
| PostgreSQL ×5     | `flight_db`, `booking_db`, `payment_db`, `baggage_db`, `shop_db` | 5433–5437 | – |

Hver service har GraphiQL på `http://localhost:808x/graphiql?path=/api/<x>/graphql` i dev-profilen
og health-endpoints på `/actuator/health/liveness` og `/actuator/health/readiness`.

## Kør lokalt med Docker

Krav: Docker med Compose v2 (Docker Desktop eller docker-ce + compose-plugin). Intet andet skal installeres.

```bash
docker compose up --build
```

Første build tager nogle minutter (Maven downloader dependencies i build-containerne). Når alle
services melder `healthy`, åbn **http://localhost:8080**.

Seed-data indlæses automatisk af Flyway ved første opstart: 3 flyselskaber, 5 fly, 10 afgange med sæder,
2 terminaler, 15+ butikker og et navigationsnetværk med 30+ noder inkl. gates.

Nyttige kommandoer:

```bash
docker compose ps                         # status/health
docker compose logs -f booking-service    # logs for én service
docker compose down -v                    # stop og slet databaser (nulstil seed-data)
```

### Prøv flows fra frontenden

- **Flow A – booking og betaling:** *Afgange* → *Book* på et fly → udfyld passager, vælg sæde → *Bekræft booking*
  → *Betaling*: brug fx kort `4242 4242 4242 4242`, udløb `12/30`, cvv `123` → bookingen bliver `CONFIRMED`.
  Kort der slutter på `0000` giver `Insufficient funds`; udløbet dato giver `Card expired` (bookingen annulleres).
- **Flow B – bagage:** *Bagage* → indtast bookingreference, vægt 23, type CHECKED → tag genereres →
  opdatér status til `LOADED` / "Belt 4" → se det under *Min booking*.
- **Flow C – aflysning:** *Afgange* → slå *Vis operations-panel* til → sæt status `CANCELLED` på flyet →
  bookinger annulleres, betalinger refunderes, bagage sendes til `RETURN_DESK`, sæder frigives.
- **Flow D – navigation:** *Butikker* → vælg "Security T2" → "Gate B12" → *Find rute* → trin-for-trin rute,
  afstand, estimeret tid, butikker undervejs og et SVG-kort med gangnetværk, nummererede trin, instruktionstekst,
  afstand pr. delstrækning og retningspile.

### Automatisk smoke-test af Flow A–D

Med stakken kørende kan alle fire flows køres end-to-end fra kommandolinjen (kræver `curl` og `jq`):

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

### Kør en enkelt service uden Docker (udvikling)

```bash
docker compose up -d flight-db rabbitmq      # infrastruktur
cd flight-service && mvn spring-boot:run     # bruger dev-defaults i application.yml (localhost:5433)
```

## Tests

Hver service har unit tests for domænelogik og én integrationstest med Testcontainers
(rigtig PostgreSQL 16 + RabbitMQ). Docker skal køre.

```bash
cd flight-service  && mvn test
cd booking-service && mvn test
cd payment-service && mvn test
cd baggage-service && mvn test
cd shop-service    && mvn test
```

| Service         | Unit tests                                     | Integrationstest dækker                                                 |
|-----------------|------------------------------------------------|-------------------------------------------------------------------------|
| flight-service  | `PricingService`, `SeatGenerator`              | seed, filtre, `booking.confirmed` → sæde optaget (idempotent), aflysning publicerer events, validering |
| booking-service | bookingreference, tilstandsovergange           | createBooking → `payment.completed` → CONFIRMED, SEAT_TAKEN, `flight.cancelled` |
| payment-service | `PaymentSimulator` (0000, udløbet kort)        | pay → COMPLETED/FAILED events, refund ved `booking.cancelled`, kortnummer gemmes ikke |
| baggage-service | `BaggageRules` (3 stk., 32 kg), tag-format     | snapshot via events, register/limit, statusopdatering, RETURN_DESK ved aflysning |
| shop-service    | `Dijkstra`, `OpeningHours`                     | rute Security T2 → Gate B12, accessibleOnly, søgning, CRUD, idempotens |

## Kubernetes

Manifests ligger i `k8s/` (Kustomize): namespace `airport`, Deployment (2 replicas) + Service + ConfigMap +
Secret pr. backend-service, StatefulSet + PVC + Service pr. database, RabbitMQ StatefulSet, frontend og én Ingress.
Derudover pgAdmin som dev/demo-værktøj på `/pgadmin` (`k8s/tools/`, kan fjernes med én linje i `kustomization.yaml`);
det er ikke en del af selve systemet.

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
| `/pgadmin`              | pgadmin          | dev/demo: pgAdmin UI, åbner uden login |

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

## Repository-struktur

```
/
  README.md                docker-compose.yml
  docs/                    architecture.md, events.md
  frontend/                Dockerfile, nginx.conf, index.html, css/, js/ (config.js, api.js, app.js, pages/)
  flight-service/          pom.xml, Dockerfile, src/main/java/dk/airport/flight/{domain,repository,service,graphql,messaging,config}
  booking-service/         ... dk/airport/booking/...
  payment-service/         ... dk/airport/payment/...
  baggage-service/         ... dk/airport/baggage/...
  shop-service/            ... dk/airport/shop/...
    (hver: src/main/resources/graphql/schema.graphqls, db/migration/V1__init.sql (+V2__seed.sql), src/test/java)
  k8s/                     kustomization.yaml, namespace.yaml, rabbitmq/, databases/, services/, frontend/, tools/ (pgAdmin), ingress.yaml
  scripts/e2e-smoke.sh     end-to-end smoke-test af Flow A-D mod en kørende compose-stak
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
- **Ingen hardcodede secrets**: compose-filen indeholder kun dev-defaults; i Kubernetes kommer alle
  credentials fra `Secret`-objekter (dev-værdier i repoet, udskiftes i et rigtigt miljø).

## Licens

Copyright © 2026 Mahdi Karimi, Lukas Rønberg og Frederik Johannessen.

Projektet er fri software under **GNU General Public License v3.0 (GPL-3.0)** – se [LICENSE](LICENSE).
Det må frit bruges, ændres og videredistribueres, så længe afledte værker udgives under samme licens.
