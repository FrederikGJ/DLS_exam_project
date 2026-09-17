# flight-service

Kilden til sandhed for flyselskaber, fly, afgange og sæder. Servicen udstiller dem i et GraphQL-API (læsning for alle,
ændringer kun for `OPERATIONS`), beregner sædepriser og publicerer et event, når en afgang oprettes, skifter status
eller gate eller aflyses. Den kender ikke bookinger eller passagerer: et sæde markeres kun som optaget eller frit ud
fra booking-service's events, og det er booking-service, der forhindrer dobbeltbooking.

| | |
|---|---|
| Ansvar | Flyselskaber, fly, afgange og sæder (inkl. pris) |
| Port | compose `8081` · Kubernetes via Ingress `/api/flights` |
| API | GraphQL `/api/flights/graphql` |
| Database | `flight_db` (PostgreSQL 16, Flyway `V1`–`V5`) |
| Events ud | `flight.created`, `flight.status.changed`, `flight.gate.changed`, `flight.cancelled` |
| Events ind | `booking.confirmed`, `booking.cancelled` (kø `flight-service.booking-events`) |
| Image | `airport/flight-service:local` |

## Ansvar og data

- **Ejer:** `airline` (unik `iata_code`), `aircraft` (unik `registration`, `total_seats` > 0), `flight` (unik på
  `(flight_number, scheduled_departure)`, `base_price` ≥ 0, `currency` altid `DKK`) og `seat` (unik
  `(flight_id, seat_number)`, `seat_class`, `is_available`, `availability_changed_at`) – `V1__init.sql` og
  `V4__seat_event_order.sql`. Tekniske tabeller: `processed_event` (`V1`) og `outbox_event` (`V3__outbox.sql`,
  `V5__outbox_traceparent.sql`).
- **Seed** (`V2__seed.sql`): 3 selskaber (`SK`, `DY`, `LH`), 5 fly og 10 afgange fra `CPH` med sæder. Afgangstiderne
  regnes fra næste hele time *da migrationen kørte* og flytter sig ikke bagefter (`docker compose down -v` nulstiller).
  `LH0831` er `BOARDING`, `DY1050` `DELAYED`, resten `SCHEDULED`.
- **Kopier af andres data:** ingen. `seat.is_available` sættes dog udelukkende af `booking.confirmed` (optaget) og
  `booking.cancelled` (frit); `availability_changed_at` er `occurredAt` på det event, der sidst afgjorde værdien.
- **Synkron klient:** booking-service (`FlightClient`) kalder ved `createBooking` uden token `flight(id)` med
  `seat(seatNumber) { seatClass isAvailable price }` (timeout `FLIGHT_SERVICE_TIMEOUT`, 5 s) og tager afgangsdata, pris
  og sædestatus derfra – se [Flow A](../docs/architecture.md#flow-a--booking-og-betaling).
- **Gør bevidst ikke:** reserverer ikke et sæde ved `booking.created` – sædet står som frit, indtil bookingen er betalt
  og `booking.confirmed` er behandlet. Bookingreglerne ("et `CANCELLED`/`DEPARTED` fly kan ikke bookes", `SEAT_TAKEN`)
  håndhæves i booking-service, hvor det partielle unikke indeks `ux_booking_active_seat` forhindrer dobbeltbooking.

## API

`@PreAuthorize` sidder på mutationerne i `graphql/FlightController.java`; queries og `@SchemaMapping`-felterne
(`Airline.aircraft`/`flights`, `Flight.seats(onlyAvailable)`/`seat(seatNumber)`/`availableSeatCount`, `Seat.price`) er åbne.

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `airlines`, `airline(id)` | query | Alle | Flyselskaber; ukendt id giver `null` |
| `flights(filter)` | query | Alle | Afgange sorteret efter afgangstid; `FlightFilter { destination, date, status }` (destination uden forskel på store/små bogstaver, `date` = UTC-døgn) |
| `flight(id)` | query | Alle | Én afgang; ukendt id giver `null` |
| `flightByNumber(flightNumber, date)` | query | Alle | Tidligste afgang med nummeret (uden forskel på store/små bogstaver) i UTC-døgnet `date`, ellers `null` |
| `availableSeats(flightId)` | query | Alle | Frie sæder sorteret `1A` … `9F`, `10A` …; ukendt fly giver `NOT_FOUND` |
| `createAirline(input)` | mutation | OPERATIONS | Nyt selskab (`iataCode` gemmes med store bogstaver) |
| `createAircraft(input)` | mutation | OPERATIONS | Nyt fly på et selskab |
| `createFlight(input)` | mutation | OPERATIONS | Ny afgang med status `SCHEDULED` og genererede sæder → `flight.created` |
| `updateFlightStatus(flightId, status)` | mutation | OPERATIONS | Statusskift → `flight.status.changed` (+ `flight.cancelled`) |
| `updateGate(flightId, gate)` | mutation | OPERATIONS | Gateskift → `flight.gate.changed` |

Fejlkoder i `errors[].extensions.code`, som servicen faktisk giver: `NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_STATE`,
`CONFLICT`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL_ERROR` (`SEAT_TAKEN` findes i `ErrorCode`, men kastes ikke her).
Fælles fejlmodel og roller: [GraphQL-fejl](../docs/architecture.md#graphql-fejl) og
[Operation × rolle](../docs/architecture.md#operation--rolle).

Kørt mod kind gennem Ingress (17-09-2026) – offentlig query uden token:

```bash
Q='{ flight(id: 2) { flightNumber status gate basePrice currency availableSeatCount
     business: seat(seatNumber: "1A") { seatClass price isAvailable }
     economy: seat(seatNumber: "12C") { seatClass price isAvailable } } }'
jq -n --arg q "$Q" '{query: $q}' \
  | curl -s http://localhost:8090/api/flights/graphql -H 'Content-Type: application/json' -d @- | jq -c .data
# {"flight":{"flightNumber":"SK1409","status":"SCHEDULED","gate":"A3","basePrice":649.00,"currency":"DKK",
#  "availableSeatCount":150,"business":{"seatClass":"BUSINESS","price":1622.50,"isAvailable":true},
#  "economy":{"seatClass":"ECONOMY","price":649.00,"isAvailable":true}}}
```

Mutation med OPERATIONS-token (her en gate, afgangen allerede har – en no-op uden event):

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8090/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s http://localhost:8090/api/flights/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { updateGate(flightId: 2, gate: \"a3\") { flightNumber gate status } }"}' | jq -c .
# {"data":{"updateGate":{"flightNumber":"SK1409","gate":"A3","status":"SCHEDULED"}}}
# samme kald uden token:   extensions.code "UNAUTHORIZED"   ("Authentication required: send a Bearer token")
# med anna/anna-token:     extensions.code "FORBIDDEN"      ("Your role does not allow this operation")
# gate: " ":               extensions.code "VALIDATION_ERROR" ("gate: must not be blank")
# availableSeats(flightId: 999): extensions.code "NOT_FOUND" ("Flight not found: 999")
# Authorization: Bearer not.a.jwt -> HTTP 401, WWW-Authenticate: Bearer error="invalid_token"
```

## Forretningsregler

- **Sædepris** = `basePrice` × klassemultiplikator (`ECONOMY` 1.00, `BUSINESS` 2.50, `FIRST` 4.00), afrundet
  `HALF_UP` til 2 decimaler (`PricingService`, `SeatClass`). Prisen gemmes ikke, men beregnes ved hvert opslag.
- **Sædelayout** (`SeatGenerator`, samme regel i `V2__seed.sql`): præcis `totalSeats` sæder i rækker á 6 (`A`–`F`),
  række 1–2 er `BUSINESS`, resten `ECONOMY`; den sidste række kan være kort (7 sæder → `1A`–`1F`, `2A`). Ingen af dem
  laver `FIRST`-sæder.
- **Status:** alle skift er tilladt, men `CANCELLED` er endelig – ethvert `updateFlightStatus` på en aflyst afgang giver
  `INVALID_STATE`. Samme status igen er en no-op uden event. `CANCELLED` giver to events i samme transaktion:
  `flight.status.changed` og derefter `flight.cancelled`.
- **Gate:** trimmes og gemmes med store bogstaver (`a3` → `A3`); samme gate igen er en no-op uden event. Tom gate →
  `VALIDATION_ERROR`. `updateGate` er ikke blokeret på en aflyst afgang.
- **Oprettelse:** `scheduledArrival` skal være efter `scheduledDeparture` (`VALIDATION_ERROR`); ukendt selskab/fly →
  `NOT_FOUND`; eksisterende `iataCode`, `registration` eller `flightNumber` + afgangstid → `CONFLICT`. Det kontrolleres
  ikke, at flyet tilhører selskabet.
- **Inputvalidering** (Bean Validation → `VALIDATION_ERROR`): `iataCode` 2–3 alfanumeriske tegn, `flightNumber` 3–8,
  `origin`/`destination` præcis 3 tegn, `gate` ≤ 10, `basePrice` ≥ 0 med højst 8+2 cifre, `totalSeats` 1–1000.
  `flightNumber`, `origin`, `destination` og `registration` gemmes med store bogstaver.
- **Sæde fra events:** last-writer-wins på eventets `occurredAt` (mikrosekunder); ved uafgjort vinder "optaget", og et
  event uden `occurredAt` tæller som 1970 (`Seat.applyAvailability`). Rækken låses med `SELECT … FOR UPDATE` først.

## Events

**Publicerer** (via transactional outbox; producer `flight-service`, routing key = eventnavn):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `flight.created` | `createFlight` | `flightId`, `flightNumber`, `airlineCode`, `origin`, `destination`, `scheduledDeparture`, `scheduledArrival`, `gate`, `status` |
| `flight.status.changed` | `updateFlightStatus` med en ny status | `flightId`, `flightNumber`, `oldStatus`, `newStatus`, `scheduledDeparture`, `gate` |
| `flight.gate.changed` | `updateGate` med en ny gate | `flightId`, `flightNumber`, `oldGate`, `newGate`, `scheduledDeparture` |
| `flight.cancelled` | `updateFlightStatus(…, CANCELLED)`, efter `flight.status.changed` | `flightId`, `flightNumber`, `scheduledDeparture`, `reason` (`"Flight cancelled by airline"`) |

Lyttere: booking-service (`flight.status.changed`, `flight.gate.changed`, `flight.cancelled`), baggage-service
(`flight.cancelled`) og shop-service (logger `flight.gate.changed`); `flight.created` bruges ikke af nogen i dag – se
[events.md](../docs/events.md#events-fra-flight-service) og [Flow C](../docs/architecture.md#flow-c--aflysning).

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `flight-service.booking-events` | `booking.#` | `booking.confirmed` | Sædet (`payload.flightId` + `seatNumber`) sættes optaget, medmindre et nyere event allerede har afgjort det |
| | | `booking.cancelled` | Sædet sættes frit (samme regel); uden `flightId`/`seatNumber` sker intet |
| | | øvrige `booking.*` | Ignoreres, men registreres i `processed_event` |

Leveringsgarantier: events skrives til `outbox_event` i samme transaktion som ændringen og sendes af `OutboxRelay` med
publisher confirms (at-least-once, rækkefølge bevaret pr. producent); indgående events dedupliceres på `eventId` i
`processed_event`. Et forældet event er ikke en fejl (logges "Ignoring stale event …" og kvitteres), men et ukendt
sæde eller fly (`NOT_FOUND`) fejler 3 gange og ender i `flight-service.dlq`. Detaljer:
[Leveringsgarantier](../docs/events.md#leveringsgarantier),
[Rækkefølge og kommutativitet](../docs/events.md#rækkefølge-og-kommutativitet) og [asyncapi.yaml](../docs/asyncapi.yaml).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/flight_db` | `flight_db`; compose-porten `5433` er flight-db's |
| `DB_USERNAME` / `DB_PASSWORD` | `flight` / `flight` | DB-credentials (Secret `flight-service-secret` i Kubernetes) |
| `GRAPHQL_PATH` | `/api/flights/graphql` | Samme sti lokalt og bag Ingress |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender usendte events |
| `SERVER_PORT` | `8080` | HTTP-port i containeren (compose mapper til `8081`) |
| `SPRING_PROFILES_ACTIVE` | – | `prod` (Kubernetes): GraphiQL slået fra og JSON-logs; compose sætter `dev` |
| `RABBITMQ_*`, `OIDC_ISSUER_URI`, `JWK_SET_URI`, `CORS_ALLOWED_ORIGINS` | `localhost:5672` `airport`/`airport`, Keycloak på `localhost:8180`, `http://localhost:8080` | Fælles for alle services – se [Konfiguration](../README.md#konfiguration) |

Faste værdier uden variabel: `app.outbox.batch-size` 100, `confirm-timeout-ms` 5000, `retention` 7d (ældre sendte
rækker slettes, tjekkes hver time), listener-retry 3 forsøg (500 ms, ×2, højst 3 s). Kubernetes-værdierne står i
ConfigMap `flight-service-config` i `k8s/base/services/flight-service.yaml`.

## Kør lokalt

```bash
# med compose (starter flight-db og rabbitmq via depends_on; keycloak kun for at få et token til mutationer)
docker compose up -d --build keycloak flight-service    # API: http://localhost:8081/api/flights/graphql
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)

# uden Docker for selve servicen (dev-defaults i application.yml peger på compose-portene)
docker compose up -d flight-db rabbitmq keycloak
cd flight-service && mvn spring-boot:run          # http://localhost:8080/api/flights/graphql
```

GraphiQL (ikke i `prod`): `http://localhost:8081/graphiql?path=/api/flights/graphql` i compose,
`http://localhost:8080/graphiql?path=/api/flights/graphql` med `mvn spring-boot:run`. På kind:
`http://localhost:8090/api/flights/graphql` gennem Ingress (se [k8s/README.md](../k8s/README.md#alternativ-kind)).

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `PricingServiceTest` (5) | Unit | ECONOMY = basispris, BUSINESS × 2,5, FIRST × 4, `HALF_UP`-afrunding (333,33 × 2,5 = 833,33), negativ basispris afvises |
| `SeatGeneratorTest` (4) | Unit | 180 unikke sæder `1A`–`30F`, række 1–2 BUSINESS, antal der ikke går op i 6, 0 sæder afvises |
| `SeatTest` (4) | Unit | Last-writer-wins: nyere event vinder, første event gælder altid, "optaget" vinder uafgjort i begge rækkefølger, samme event to gange er en no-op |
| `FlightServiceIntegrationTest` (9) | Integration (HTTP) | Seed via Flyway; `flightByNumber` uden forskel på store/små bogstaver og pr. dato; filter + pris `1A` på LHR = 2247,50; `booking.confirmed` optager sædet idempotent og `booking.cancelled` frigiver det; aflysning giver `flight.status.changed` + `flight.cancelled` og anden aflysning `INVALID_STATE`; `createFlight` giver 100 sæder og `flight.created` gennem outboxen; outbox: `publish` uden transaktion afvises, rollback efterlader intet event, relayet prøver igen til brokeren bekræfter (`outbox.pending` = 0) |
| `SecurityIntegrationTest` (8) | Integration (HTTP) | Queries og readiness uden token; mutation uden token `UNAUTHORIZED`, PASSENGER `FORBIDDEN`, OPERATIONS tilladt; udløbet/fremmed/ugyldigt token → HTTP 401; `/actuator/metrics` kræver OPERATIONS, `/actuator/prometheus` er åben med `outbox_pending`; CORS-preflight fra `http://localhost:8080` |
| `EventOrderIntegrationTest` (2) | Integration (handler direkte) | Samme booking-events om et sæde i alle rækkefølger giver samme resultat: bekræft/aflys/ny booking (6 rækkefølger) ender optaget, bekræft/aflys (2) ender frit |

`TestTokens` erstatter Keycloak (JWT'er signeret med en testnøgle: `passenger()` = anna, `operations()` = ops).
Integrationstestene starter PostgreSQL 16 og RabbitMQ 3.13 med Testcontainers, så **Docker skal køre**.

```bash
cd flight-service
mvn test              # 32 tests
mvn -Pci verify       # det samme som CI: tests + Checkstyle (../config/checkstyle.xml) + SpotBugs/FindSecBugs
```

## Drift

- **Probes** (`k8s/base/services/flight-service.yaml`): startup og liveness på `/actuator/health/liveness`, readiness
  på `/actuator/health/readiness` (grupperne `readinessState`, `db`, `rabbit`). En initContainer venter med
  `pg_isready` på `flight-db` ([hvorfor](../k8s/README.md#initcontainer-vent-på-databasen)).
- **Ressourcer:** requests `150m`/`256Mi`, limits `500m`/`640Mi`, 1 replica, ikke-root (uid 100), read-only
  rodfilsystem med `emptyDir` på `/tmp`; image med
  [CDS-arkiv](../k8s/README.md#class-data-sharing-cds-hurtigere-boot-uden-flere-ressourcer). Flere replicas kræver
  ingen kodeændring: relayet tager en advisory lock, og sæde-handleren låser rækken.
- **Metrics** på `/actuator/prometheus` (åben, pod-annotationerne `prometheus.io/*`; Ingress router ikke `/actuator`):
  `outbox_pending`, `events_published_total{type}`, `events_consumed_total{type, outcome}` (`processed`,
  `duplicate`, `failed`; en ulæselig besked tælles som `type="unreadable"`), alle med `application="flight-service"`.
  Bemærk, at ignorerede `booking.*`-typer også tæller som `processed`. Relevante alarmer: `OutboxBacklog`,
  `MessagesDeadLettered` (`flight-service.dlq`) og `EventHandlingFailing` – se
  [Observability](../docs/architecture.md#observability-metrics-logs-og-alarmer).
- **Loglinjer at kende:** `Created flight … with … seats`, `Flight … status … -> …`, `Flight … gate … -> …`,
  `Seat … on flight … is now taken|available`, `Ignoring stale event for seat …`, `Skipping already processed event …`,
  `Queued event … in outbox` / `Published event …` og WARN `Outbox: … event(s) could not be published (attempt …)`
  (første forsøg og hvert 20.).
- **Trace-id:** et indgående HTTP-kald (fx `FlightClient` i booking-service) og en `booking.*`-besked fortsætter
  afsenderens trace via `traceparent`; events, servicen selv udsender, får trace'en med i `outbox_event.traceparent` og
  AMQP-headeren. `traceId` står i hver loglinje
  ([Én trace-id gennem HTTP, outbox og RabbitMQ](../docs/architecture.md#én-trace-id-gennem-http-outbox-og-rabbitmq)).

Kørt på kind (17-09-2026, `kubectl -n airport port-forward deploy/flight-service 18081:8080`; uddrag, tallene vokser):

```bash
curl -s localhost:18081/actuator/prometheus | grep -E '^(outbox_pending|events_)'
# events_consumed_total{application="flight-service",outcome="processed",type="booking.confirmed"} 3.0
# events_published_total{application="flight-service",type="flight.cancelled"} 1.0
# outbox_pending{application="flight-service"} 0.0
```

## Designvalg

| Valg | Begrundelse | Se |
|------|-------------|----|
| Pris beregnes af `basePrice` × klasse, gemmes ikke pr. sæde | Én pris at vedligeholde pr. afgang; spec'ens sædetabel har ingen pris | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| booking-service henter pris og sæde synkront | Altid aktuel pris og sædestatus uden en kopi af sædekortet i `booking_db` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Sædelayout efter fast regel (6 pr. række, række 1–2 BUSINESS) | Deterministisk og ens i seed og `SeatGenerator` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Sæder last-writer-wins på `occurredAt` | Et sæde kan gå optaget → frit → optaget; kun tidspunktet kan afgøre en sen genlevering, og alle sæde-events kommer fra ét ur | [Kommutative handlers](../docs/architecture.md#kommutative-handlers) |
| Transactional outbox | Afgang og event committes atomisk; intet tabt event ved broker-nedbrud | [Messaging](../docs/architecture.md#messaging) |
| Offentlige queries, `@PreAuthorize` pr. mutation | Afgangstavlen skal kunne læses uden login på samme endpoint | [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller) |
| Samme status/gate igen er en no-op | Gentagne mutationer giver ingen ekstra events | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – overblik, flows A og C, sikkerhed, observability
- [docs/events.md](../docs/events.md) og [docs/asyncapi.yaml](../docs/asyncapi.yaml) – event-kontrakten
- [booking-service/README.md](../booking-service/README.md) – den synkrone klient og producenten af `booking.*`
- [README.md](../README.md#kør-lokalt-med-docker) – hele stakken og testbrugere;
  [retry/DLQ-demo](../README.md#test-af-retry--dead-letter-queue)
- [k8s/README.md](../k8s/README.md) – kind/minikube, ressourcer, Keycloak
