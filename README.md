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

## Dokumentation

| Dokument | Indhold |
|----------|---------|
| [docs/architecture.md](docs/architecture.md) | Overblik og diagrammer, flows A–E, sikkerhed, AI, serverless, Kubernetes-deployment, skalerbarhed, observability, designmønstre (tombstone, idempotens, CQRS, saga, kommutative handlers) og designvalg |
| [docs/events.md](docs/events.md) · [docs/asyncapi.yaml](docs/asyncapi.yaml) | Eventkontrakten i prosa med begrundelser · maskinlæsbart som AsyncAPI 3.1 (valideret i CI) |
| [docs/openapi/baggage-v1.yaml](docs/openapi/baggage-v1.yaml) | REST-API v1 som OpenAPI (genereret af springdoc) |
| [docs/requirements-matrix.md](docs/requirements-matrix.md) | Hvert krav → hvor det er implementeret → test, CI-kørsel eller måling, der beviser det |
| Service-READMEs: [flight](flight-service/README.md) · [booking](booking-service/README.md) · [payment](payment-service/README.md) · [baggage](baggage-service/README.md) · [shop](shop-service/README.md) · [notification-job](notification-job/README.md) | Ansvar, API med roller, regler, events, konfiguration, tests og drift pr. modul – samme skabelon ([docs/service-readme-template.md](docs/service-readme-template.md)) |
| [k8s/README.md](k8s/README.md) | kind/minikube, overlays og components, ressourcer, Keycloak, KEDA, HPA, observability – med målinger |
| [system-tests/README.md](system-tests/README.md) | System-testen af booking ↔ payment med de byggede images |

## Tech stack

| Del                 | Teknologi                                                             |
|---------------------|-----------------------------------------------------------------------|
| Frontend            | Vanilla JavaScript, HTML, CSS – serveret af nginx (ingen frameworks)  |
| Backend             | Java 21, Spring Boot 3.5 (Maven)                                       |
| API                 | GraphQL (Spring for GraphQL) + versioneret REST v1 i `baggage-service` (OpenAPI/springdoc) |
| Database            | PostgreSQL 16 – én database pr. service, migrationer med Flyway       |
| Message broker      | RabbitMQ (Spring AMQP) – topic exchange `airport.events`              |
| Containerisering    | Docker, multi-stage builds (maven → eclipse-temurin JRE)              |
| Orkestrering        | Kubernetes (Kustomize), verificeret på kind – minikube-kommandoer i k8s/README.md; HPA på shop-service |
| Serverless          | `notification-job` (ren Java 21) som KEDA `ScaledJob` på RabbitMQ-kølængde – skalerer til 0 |
| AI                  | Lokal sprogmodel (Ollama, `qwen2.5:1.5b`) bag `askRoute` i `shop-service` – valgfri |
| Observability       | Micrometer (Prometheus-metrics + trace-id i logs over HTTP og RabbitMQ), Prometheus, Loki, Grafana Alloy, Grafana – valgfri (compose-profil / Kustomize-komponent) |
| Tests               | JUnit 5, Testcontainers (Postgres + RabbitMQ), Spring GraphQL Tester, WireMock (system-test) |

## Komponenter

| Service           | Ansvar                                            | Port (compose) | GraphQL-endpoint                          |
|-------------------|---------------------------------------------------|----------------|-------------------------------------------|
| `frontend`        | SPA: afgange, book, betaling, min booking, bagage, butikker | 8080  | –                                          |
| [`flight-service`](flight-service/README.md)  | Flyselskaber, fly, afgange, sæder (kilde til sandhed) | 8081       | `http://localhost:8081/api/flights/graphql` |
| [`booking-service`](booking-service/README.md) | Passagerer og bookinger, booking-sagaen, read model til *Min booking* | 8082          | `http://localhost:8082/api/bookings/graphql` |
| [`payment-service`](payment-service/README.md) | Simuleret betalingsgateway, refunds               | 8083           | `http://localhost:8083/api/payments/graphql` |
| [`baggage-service`](baggage-service/README.md) | Bagage bundet til booking, status-tracking, **REST v1** | 8084      | `http://localhost:8084/api/baggage/graphql` + REST `/api/baggage/v1` |
| [`shop-service`](shop-service/README.md)    | Butikker + navigation (Dijkstra) + "Spørg om vej" (lokal AI) | 8085 | `http://localhost:8085/api/shops/graphql` |
| [`notification-job`](notification-job/README.md) | Dansk mail pr. booking-event fra køen `notifications`; kører kun, når der er beskeder | – | – (profil `jobs` i compose, KEDA ScaledJob i Kubernetes) |
| Ollama            | Lokal sprogmodel til `askRoute` (valgfri)          | 11434          | kun med `docker compose --profile ai` |
| RabbitMQ          | Events mellem services                            | 5672 / 15672   | Management UI: http://localhost:15672 (airport/airport) |
| Keycloak          | OpenID Connect-login, roller PASSENGER/OPERATIONS | 8180           | http://localhost:8180/realms/airport (admin: /admin/, admin/admin) |
| PostgreSQL ×5     | `flight_db`, `booking_db`, `payment_db`, `baggage_db`, `shop_db` | 5433–5437 | – |
| Grafana / Prometheus | Dashboard, logs (Loki via Alloy) og alarmer (valgfri)    | 3000 / 9090    | kun med `docker compose --profile observability` |

Hver service har GraphiQL på `http://localhost:808x/graphiql?path=/api/<x>/graphql` (slået fra i `prod`-profilen),
health-endpoints på `/actuator/health/liveness` og `/actuator/health/readiness` og metrics på `/actuator/prometheus`.

**Hver service har sin egen README** (linket i tabellen) bygget over samme skabelon,
[docs/service-readme-template.md](docs/service-readme-template.md): ansvar og data, API med roller og et kørt eksempel,
forretningsregler, events ind og ud, konfiguration, kør lokalt, tests, drift og designvalg.

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
| OpenAPI (YAML)       | http://localhost:8084/v3/api-docs.yaml     | – (Ingress-reglen `/v3/api-docs` matcher kun hele segmenter; brug JSON eller den eksporterede fil) |

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
Servicen beder Ollama indlæse modellen lige efter opstart (`AI_WARM_UP`), så første spørgsmål ikke venter på det;
et svar tager derefter typisk 2–3 s på en laptop-CPU. Sådan virker det, og hvorfor modellen kun *foreslår* en butik,
står i [docs/architecture.md](docs/architecture.md#ai-spørg-om-vej-lokal-sprogmodel).
I Kubernetes tændes modellen med overlayet `k8s/overlays/demo` (se [k8s/README.md](k8s/README.md)).

### Prøv flows fra frontenden

Afgange og butikker kræver ikke login. *Book*, *Betaling*, *Min booking* og *Bagage* sender dig til
Keycloaks login-side (brug `anna`/`anna`) og tilbage igen; *Log ind*/*Log ud* står øverst til højre sammen med
brugernavn og rolle. Operations-panelet under *Afgange* vises kun for brugere med rollen OPERATIONS (`ops`/`ops`).

- **Flow A – booking og betaling:** *Afgange* → *Book* på et fly → udfyld passager, vælg sæde → *Bekræft booking*
  → *Betaling*: brug fx kort `4242 4242 4242 4242`, udløb `12/30`, cvv `123` → bookingen bliver `CONFIRMED`.
  Kort der slutter på `0000` giver `Insufficient funds`; udløbet dato giver `Card expired` (bookingen annulleres).
- **Flow B – bagage:** *Bagage* → indtast bookingreference, vægt 23, type CHECKED → tag genereres →
  opdatér status til `LOADED` / "Belt 4" → se det under *Min booking*.
- **Min booking (CQRS):** uden reference viser siden dine bookinger (`myBookings`); med en reference hentes booking,
  passager, betalinger og bagage i **ét** kald til booking-service (`bookingOverview`), som svarer fra sin read model
  `booking_overview` – browserens netværksfane viser ingen kald til payment- eller baggage-service. Betalinger og
  bagage når read-modellen via events typisk inden for et halvt sekund. Hvorfor og hvordan:
  [docs/architecture.md](docs/architecture.md#cqrs-booking_overview-til-min-booking).
- **Flow C – aflysning:** log ind som `ops` → *Afgange* → slå *Vis operations-panel* til → sæt status `CANCELLED` på flyet →
  bookinger annulleres, betalinger refunderes, bagage sendes til `RETURN_DESK`, sæder frigives.
- **Flow D – navigation:** *Butikker* → vælg "Security T2" → "Gate B12" → *Find rute* → trin-for-trin rute,
  afstand, estimeret tid, butikker undervejs og et SVG-kort med gangnetværk, nummererede trin, instruktionstekst,
  afstand pr. delstrækning og retningspile.
- **Flow E – spørg om vej (AI):** *Butikker* → vælg hvor du er → skriv fx
  *"Hvor finder jeg en kop kaffe på vej til gate B12?"* → *Spørg om vej*. En lokal sprogmodel (Ollama) tolker
  spørgsmålet og foreslår en butik, koden vælger butikken og et evt. mål i spørgsmålet, og ruten tegnes som i Flow D.
  Svaret viser, hvordan spørgsmålet blev forstået, og om det var modellen eller nødløsningen (nøgleordssøgning), der
  svarede. Kræver `docker compose --profile ai up` – uden den svarer nøgleordssøgningen, og siden siger det tydeligt.
  Prøv også eksemplet *"Hvor kan jeg få noget mod køresyge?"*: modellen finder Apoteket, nøgleordssøgningen intet.

### Serverless-demo: notification-job

`notification-job` er en lille, ren Java-proces (ingen Spring), der tømmer køen `notifications`, skriver én dansk
mail pr. booking-event i sin log og stopper igen. I compose køres den ved behov:

```bash
./scripts/e2e-smoke.sh                                   # laver bookinger, betalinger og aflysninger -> events i køen
docker compose build notification-job
docker compose --profile jobs up notification-job        # mails i loggen, exit 0 når køen er tom
```

I Kubernetes kører den som KEDA `ScaledJob`, der starter jobs efter kølængden og ingen pods har, når køen er tom:
`kubectl apply -k k8s/overlays/demo/` og `./scripts/demo-keda.sh` (5 bookinger → jobs starter, køen tømmes på få
sekunder, og jobbene forsvinder igen). Installation af KEDA og målinger står i
[k8s/README.md](k8s/README.md#demo-overlay-ai-og-serverless-keda), designet i
[docs/architecture.md](docs/architecture.md#serverless-notification-job-som-keda-scaledjob) og selve jobbet i
[notification-job/README.md](notification-job/README.md).

### Observability: metrics, logs og alarmer

Prometheus, Loki, Alloy og Grafana startes med compose-profilen `observability` (samme konfigurationsfiler som
Kubernetes-komponenten `k8s/components/observability`):

```bash
docker compose --profile observability up -d
./scripts/e2e-smoke.sh               # lidt trafik og events at se på
```

- **Grafana** <http://localhost:3000/> åbner dashboardet *Airport – services og events*: services oppe, events i
  outbox og i dead-letter queues, aktive alarmer, requests/s og svartider, events publiceret og behandlet pr. service,
  kødybder, JVM – og nederst logs. Anonyme besøgende kan se; `admin`/`admin` kan redigere og bruge *Explore*.
- **Følg ét flow:** hver loglinje har `[traceId-spanId]`, og trace-id'en følger flowet gennem HTTP, outbox og RabbitMQ.
  Kopiér trace-id'en fra en linje (fx `Booking G262RH confirmed after payment`) ind i feltet *Trace-id* øverst på
  dashboardet: panelet nederst viser så linjerne fra payment-, booking-, flight- og baggage-service for netop den
  betaling. I *Explore* (som admin): `{app=~".+-service"} |= "<trace-id>"`.
- **Prometheus** <http://localhost:9090/> – `Status → Targets` (fem services + RabbitMQ) og `/alerts` (fem regler,
  fx `MessagesDeadLettered`, som fyrer efter "Test af retry + dead-letter queue" nedenfor).
- **Metrics direkte:** <http://localhost:8082/actuator/prometheus> (åben uden token; `events_published_total`,
  `events_consumed_total`, `outbox_pending` …).

I Kubernetes er det samme en del af `k8s/overlays/demo` med Grafana på <http://localhost:8090/grafana/> – se
[k8s/README.md](k8s/README.md#observability-prometheus-loki-alloy-og-grafana). Hvorfor netop disse værktøjer, hvordan
trace-id'en kommer gennem outboxen, og hvad hver alarm betyder:
[docs/architecture.md](docs/architecture.md#observability-metrics-logs-og-alarmer).

### Automatisk smoke-test af Flow A–D

Med stakken kørende kan alle fire flows køres end-to-end fra kommandolinjen (kræver `curl` og `jq`):

```bash
./scripts/e2e-smoke.sh
```

Scriptet henter først tokens fra Keycloak for `anna` (PASSENGER) og `ops` (OPERATIONS) med password grant,
tjekker at en mutation uden token giver `UNAUTHORIZED` og at anna får `FORBIDDEN` på en OPERATIONS-mutation,
og kører så flows: anna booker et sæde, betaler og registrerer bagage, ops sætter bagagestatus og aflyser flyet,
og scriptet verificerer at bookingen bliver `CANCELLED`, betalingen `REFUNDED`, bagagen står ved `RETURN_DESK` og
sædet er frigivet, og at booking-services read model `bookingOverview` (*Min booking*) har fået betaling, bagage og
aflysning med – og slutter med en rute fra Security T2 til Gate B12. Mod kind sættes `KEYCLOAK_URL` og
service-URL'erne som vist i [k8s/README.md](k8s/README.md#alternativ-kind). Bemærk at Flow C aflyser et fly fra
seed-data; `docker compose down -v` nulstiller.

### Saga-demo: betalingstimeout og for sen betaling

En booking, der ikke betales inden `PAYMENT_TIMEOUT` (15 min.), aflyses, og en betaling, der kommer bagefter,
refunderes automatisk. Med en kort timeout kan begge kompensationer ses i løbet af et minut:

```bash
PAYMENT_TIMEOUT=30s docker compose up -d --wait booking-service
./scripts/demo-saga.sh
# == 1. Booking 3XR63W ... is PENDING_PAYMENT, pay before 2026-09-17T06:09:09Z
#    after 47 s: CANCELLED - Payment not received within 30 seconds
# == 2. The passenger pays anyway
#    payment 37: COMPLETED
#    payment-service: REFUNDED (booking-service published booking.payment.rejected)
#    Min booking: CANCELLED | Payment not received within 30 seconds | payments ["REFUNDED"]
docker compose up -d --wait booking-service      # tilbage til 15 minutter
```

I frontenden viser *Betaling* fristen ("Betal senest kl. …"), og *Min booking* viser årsagen. Hvorfor og hvordan:
[docs/architecture.md](docs/architecture.md#saga-bookingen-som-en-kæde-af-lokale-transaktioner).

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
curl -s localhost:8082/actuator/prometheus | grep '^outbox_pending'                     # 0.0 = tom backlog
```

### Kør en enkelt service uden Docker (udvikling)

```bash
docker compose up -d flight-db rabbitmq      # infrastruktur
cd flight-service && mvn spring-boot:run     # bruger dev-defaults i application.yml (localhost:5433)
```

## Tests

290 tests i syv Maven-moduler, alle kørt af CI ved hvert push. Integrationstestene starter rigtig PostgreSQL 16 og
RabbitMQ 3.13 med Testcontainers, så **Docker skal køre**. De kalder API'erne over HTTP gennem Spring Securitys
filterkæde med test-JWT'er fra `TestTokens` (`passenger()` = anna, `operations()` = ops), signeret med en testnøgle,
så Keycloak ikke behøver køre.

```bash
cd booking-service && mvn -Pci verify     # tests + Checkstyle + SpotBugs/find-sec-bugs, præcis som CI
```

| Modul | Tests | Unit tests af kernelogik | Integrationstests (Testcontainers) |
|-------|------:|--------------------------|------------------------------------|
| flight-service | 32 | `PricingServiceTest`, `SeatGeneratorTest`, `SeatTest` | `FlightServiceIntegrationTest`, `EventOrderIntegrationTest`, `SecurityIntegrationTest` |
| booking-service | 51 | `BookingStateTest`, `BookingReferenceGeneratorTest`, `OverviewLinesTest`, `PaymentTimeoutJobTest` | `BookingServiceIntegrationTest`, `BookingOverviewIntegrationTest`, `EventOrderIntegrationTest`, `SagaCompensationIntegrationTest`, `ObservabilityIntegrationTest`, `SecurityIntegrationTest` |
| payment-service | 27 | `PaymentSimulatorTest` | `PaymentServiceIntegrationTest`, `SecurityIntegrationTest` |
| baggage-service | 68 | `BaggageRulesTest`, `TagGeneratorTest`, `BookingSnapshotTest` | `BaggageServiceIntegrationTest`, `BaggageRestIntegrationTest`, `EventOrderIntegrationTest`, `SecurityIntegrationTest` |
| shop-service | 71 | `DijkstraTest`, `OpeningHoursTest`, `KeywordMatcherTest`, `AiConciergeServiceTest` | `ShopServiceIntegrationTest`, `AiConciergeIntegrationTest`, `SecurityIntegrationTest` |
| notification-job | 36 | `MailRendererTest`, `JobConfigTest` | `NotificationJobIntegrationTest` |
| system-tests | 5 | – | `CooperationTest`: booking- og payment-service som de byggede images (se nedenfor) |

Hvad hver testklasse viser, står i test-afsnittet i den enkelte service-README. På tværs af services dækker testene
bl.a. sikkerhed (rolle pr. operation, udløbne/fremmede tokens), outbox-garantierne (rollback, retry til broker-
bekræftelse), idempotent eventforbrug, alle rækkefølger af events (`EventOrderIntegrationTest`), sagaens
kompensationer, samtidige kald (idempotency key, betaling) og trace-id gennem outbox og RabbitMQ.
`scripts/e2e-smoke.sh` kører desuden Flow A–D gennem hele den kørende stak (compose i CI, kind lokalt).

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
mod `main`; et nyt push til samme branch afbryder den kørsel, der stadig er i gang. Seks uafhængige jobs (seneste
kørsel på `dev_max`, 17-09-2026: alle grønne på 4,8 min):

| Job         | Hvad                                                                                                          |
|-------------|---------------------------------------------------------------------------------------------------------------|
| `backend`   | Én matrix-kørsel pr. Java-modul (fem services + `notification-job`): `mvn -Pci verify` = unit + Testcontainers-tests, Checkstyle og SpotBugs/find-sec-bugs. Surefire-, Checkstyle- og SpotBugs-rapporter uploades som artifacts. |
| `frontend`  | `npm ci && npx eslint js/` i `frontend/` (ESLint *recommended* + browser-globals; frontenden har intet build-step) |
| `manifests` | `kubectl kustomize` + `kubeconform -strict` mod Kubernetes-API-skemaerne for hver kustomization under `k8s/`   |
| `contracts` | `docs/asyncapi.yaml` valideres af AsyncAPI CLI (også governance-warnings fejler), og `scripts/check-asyncapi.sh` tjekker, at hvert eventnavn og hver kø i koden findes i kontrakten |
| `security`  | **gitleaks** over hele git-historikken (`fetch-depth: 0`), **Trivy** over repoet (hemmeligheder, fejlkonfiguration i Dockerfiles og Kubernetes-manifests, npm-afhængigheder) og over alle syv byggede images (Alpine-pakker + hver jar i imaget). HIGH/CRITICAL-fund med en rettelse gør jobbet rødt |
| `system`    | Hele systemet som en bruger kører det: `docker compose build` + `up --wait`, `scripts/e2e-smoke.sh` (Flow A–D), `docker compose down -v` og derefter `system-tests/` mod de byggede images. Fejler noget, uploades `docker compose logs` som artifact |

**Sikkerhedsscanning.** gitleaks og Trivy installeres som release-binærer, fastlåst på version *og* SHA-256, i stedet
for via deres GitHub Actions: en action kører med workflowets token og kan ændre sig bag et flyttet tag, det kan en
checksum ikke. Accepterede fund står med begrundelse i [.gitleaks.toml](.gitleaks.toml) (dev-brugeren
`airport:airport` i curl-eksemplerne og én falsk positiv i kravmatrixens tech stack-celle) og [.trivyignore.yaml](.trivyignore.yaml) (read-only rodfilsystem og
security context for tredjeparts-images som Postgres, RabbitMQ og Keycloak). Vores egne workloads har ingen
undtagelser: de fem services og notification-job kører som uid 100 med read-only rodfilsystem, uden capabilities og
uden privilege escalation, og images kører `apk upgrade` oven på base-imaget. Den første scanning (16-09-2026) fandt
CRITICAL-sårbarheder i Tomcat 10.1.55 og HIGH i PostgreSQL-driveren og RabbitMQ-klienten, som Spring Boot 3.5.16
(den sidste 3.5.x) stadig styrer, og efter opgraderingen af RabbitMQ-klienten også i den Netty, den trækker ind – de
er løftet med versions-properties i hver pom (`tomcat.version`, `postgresql.version`, `rabbit-amqp-client.version`,
`netty.version`), med CVE-numrene i en kommentar.

Maven-profilen `ci` findes i alle fem poms og bruger `config/checkstyle.xml` (Google-stil med 4 spaces og
120 tegn) og `config/spotbugs-exclude.xml` (hver undtagelse er begrundet i filen). Uden `-Pci` er
`mvn package`/Docker-buildet uændret. Kør det samme lokalt:

```bash
cd flight-service && mvn -Pci verify          # som pipelinen: tests + Checkstyle + SpotBugs
cd frontend && npm ci && npx eslint js/
kubectl kustomize k8s/ | kubeconform -strict -summary
npx --yes @asyncapi/cli@6.1.0 validate --fail-severity=warn docs/asyncapi.yaml && ./scripts/check-asyncapi.sh
gitleaks git --config .gitleaks.toml --redact .
trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --ignore-unfixed \
  --ignorefile .trivyignore.yaml --skip-files '**/pom.xml' --exit-code 1 .
trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 airport/flight-service:local
```

## Kubernetes

Manifests ligger i `k8s/` (Kustomize): namespace `airport`, Deployment (1 replica, dimensioneret til en laptop – se
[Ressourcer på en laptop](k8s/README.md#ressourcer-på-en-laptop)) + Service + ConfigMap + Secret pr. backend-service, StatefulSet + PVC + Service pr. database, RabbitMQ StatefulSet, frontend og én Ingress.
Selve systemet ligger i `k8s/base/` (`kubectl apply -k k8s/`); pgAdmin som dev/demo-værktøj på `/pgadmin` ligger i
`k8s/tools/` og deployes kun med overlayet `kubectl apply -k k8s/overlays/dev-tools/` – det er ikke en del af selve systemet.
Overlayet `kubectl apply -k k8s/overlays/demo/` lægger den lokale AI-model (Ollama), notification-job som KEDA
`ScaledJob` og observability (Prometheus, Loki, Alloy, Grafana på `/grafana`) oven på systemet, og shop-service har en
HorizontalPodAutoscaler (1–3 pods), der kræver metrics-server – se
[Demo-overlay](k8s/README.md#demo-overlay-ai-og-serverless-keda),
[Observability](k8s/README.md#observability-prometheus-loki-alloy-og-grafana) og
[Autoscaling](k8s/README.md#autoscaling-hpa). Et deploymentdiagram og en oversigt over base, components og overlays står i
[docs/architecture.md](docs/architecture.md#deployment-i-kubernetes).

Manifests er verificeret på et **kind**-cluster (demo-overlayet med observability 17-09-2026: 18/18 pods Ready efter
148 s på et nyt cluster inkl. image-pulls, alle flows grønne gennem Ingress). Kort version for minikube – se
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
| `/v3/api-docs`, `/swagger-ui`, `/swagger-ui.html` | baggage-service | OpenAPI (JSON) og Swagger UI |
| `/auth`                 | keycloak         | login, `/auth/realms/airport`, admin console `/auth/admin/` |
| `/grafana`              | grafana          | kun med observability-komponenten (overlayet `demo`) |
| `/pgadmin`              | pgadmin          | kun med overlayet `dev-tools`: pgAdmin UI, åbner uden login |

I Kubernetes erstattes `frontend/js/config.js` af en ConfigMap med relative paths (`/api/.../graphql`),
så frontend og API deler origin.

## Login og roller

Login sker via **Keycloak** (OpenID Connect, realm `airport`). Frontenden er en public client med Authorization Code
+ PKCE; de fem services er resource servers, der validerer JWT'et og læser rollen i `realm_access.roles`.
Realm'et importeres fra `k8s/keycloak/realm-airport.json` ved hver opstart – samme fil i compose og Kubernetes.

| Bruger  | Kode   | Rolle        | Må                                                                          |
|---------|--------|--------------|-----------------------------------------------------------------------------|
| –       | –      | (ingen)      | læse: afgange, sæder, butikker, ruter og *Spørg om vej*, en booking pr. reference/id, en betaling pr. id, en bagage pr. tag |
| `anna`  | `anna` | `PASSENGER`  | booke, betale, checke ind, annullere, registrere bagage, se *Min booking* (`bookingOverview`, `myBookings`), betalinger og bagage pr. booking, egne bookinger (`bookingsByPassenger` kun med egen e-mail) |
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
| `PAYMENT_TIMEOUT`      | Kun booking-service: en ubetalt booking aflyses efter (saga-kompensation) | `15m` (default) |
| `PAYMENT_TIMEOUT_CHECK_INTERVAL_MS` | Kun booking-service: hvor ofte der ledes efter ubetalte bookinger | `30000` (default) |

## Repository-struktur

```
/
  README.md                docker-compose.yml
  .github/workflows/       ci.yml – GitHub Actions: backend (matrix), frontend, manifests, contracts, security, system
  config/                  checkstyle.xml, spotbugs-exclude.xml – regler for Maven-profilen `ci`
  docs/                    architecture.md, events.md, asyncapi.yaml, openapi/baggage-v1.yaml, requirements-matrix.md,
                           service-readme-template.md
  frontend/                Dockerfile, nginx.conf, index.html, css/, js/ (config.js, api.js, app.js, pages/), eslint.config.js + package.json (kun lint)
  flight-service/          README.md, pom.xml, Dockerfile, src/main/java/dk/airport/flight/{domain,repository,service,graphql,messaging,config}
  booking-service/         ... dk/airport/booking/...
  payment-service/         ... dk/airport/payment/...
  baggage-service/         ... dk/airport/baggage/...
  shop-service/            ... dk/airport/shop/...
    (hver: README.md, src/main/resources/graphql/schema.graphqls, db/migration/V1__init.sql …, src/test/java;
     booking-service også query/ (read model), baggage-service rest/ (REST v1), shop-service ai/ (Spørg om vej))
  notification-job/        pom.xml, Dockerfile, README.md, src/main/java/dk/airport/notification/ (ren Java, ingen Spring)
  ollama/                  Dockerfile – Ollama med modellen qwen2.5:1.5b bagt ind
  k8s/                     base/ (namespace, rabbitmq/, databases/, services/ inkl. shop-service-hpa.yaml, frontend/, ingress.yaml), keycloak/ (realm-airport.json), tools/ (pgAdmin), components/ (ollama/, notification-job/, observability/ inkl. config/ som compose også bruger), overlays/ (dev-tools/, demo/)
  scripts/e2e-smoke.sh     end-to-end smoke-test af Flow A-D mod en kørende compose-stak (eller kind)
  scripts/check-asyncapi.sh  tjekker, at alle events og køer i koden står i docs/asyncapi.yaml (CI-job contracts)
  scripts/demo-keda.sh     KEDA-demo på kind: 5 bookinger -> notification-jobs starter og forsvinder igen
  scripts/demo-saga.sh     saga-demo: ubetalt booking aflyses efter PAYMENT_TIMEOUT, for sen betaling refunderes
  scripts/load-shops.sh    belastning af shop-service, så HPA'en skalerer op og ned
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
