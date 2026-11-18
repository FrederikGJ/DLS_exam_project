# flight-service

Kilden til sandhed for flyselskaber, fly, afgange og sæder. Servicen udstiller dem i et GraphQL-API (læsning for alle,
ændringer kun for `OPERATIONS`), beregner sædepriser og publicerer et event, når en afgang oprettes, skifter status
eller gate eller aflyses. Den kender ikke bookinger eller passagerer: et sæde markeres kun som optaget eller frit ud
fra booking-service's events, og det er booking-service, der forhindrer dobbeltbooking.

| | |
|---|---|
| Ansvar | Flyselskaber, fly, afgange og sæder (inkl. pris) |
| Port | compose `8081` · Kubernetes via Ingress `/api/flights` |
| API | GraphQL `/api/flights/graphql` · REST kun `/actuator` (`health`, `info`, `metrics`) |
| Database | `flight_db` (PostgreSQL 16, Flyway `V1`–`V3`) |
| Events ud | `flight.created`, `flight.status.changed`, `flight.gate.changed`, `flight.cancelled` |
| Events ind | `booking.confirmed`, `booking.cancelled` (kø `flight-service.booking-events`) |
| Image | `airport/flight-service:local` |

## Ansvar og data

- **Ejer:** `airline` (unik `iata_code`), `aircraft` (unik `registration`, `total_seats` > 0), `flight` (unik på
  `(flight_number, scheduled_departure)`, `base_price` ≥ 0, `currency` altid `DKK`) og `seat` (unik
  `(flight_id, seat_number)`, `seat_class`, `is_available`) – `V1__init.sql`. Tekniske tabeller: `processed_event`
  (`V1`) og `outbox_event` (`V3__outbox.sql`).
- **Seed** (`V2__seed.sql`): 3 selskaber (`SK`, `DY`, `LH`), 5 fly og 10 afgange fra `CPH` med sæder. Afgangstiderne
  regnes fra næste hele time *da migrationen kørte* og flytter sig ikke bagefter (`docker compose down -v` nulstiller).
  `LH0831` er `BOARDING`, `DY1050` `DELAYED`, resten `SCHEDULED`.
- **Kopier af andres data:** ingen. `seat.is_available` sættes dog udelukkende af `booking.confirmed` (optaget) og
  `booking.cancelled` (frit).
- **Synkron klient:** booking-service (`FlightClient`) kalder ved `createBooking` uden token `flight(id)` med
  `seat(seatNumber) { seatNumber seatClass isAvailable price }` (timeout `FLIGHT_SERVICE_TIMEOUT`, 5 s) og tager
  afgangsdata, pris og sædestatus derfra – se [Flow A](../docs/architecture.md#flow-a--booking-og-betaling).
- **Gør bevidst ikke:** reserverer ikke et sæde ved `booking.created` – sædet står som frit, indtil bookingen er betalt
  og `booking.confirmed` er behandlet. Bookingreglerne ("et `CANCELLED`/`DEPARTED` fly kan ikke bookes", `SEAT_TAKEN`)
  håndhæves i booking-service, hvor det partielle unikke indeks `ux_booking_active_seat` forhindrer dobbeltbooking.

## API

Operationerne står i `src/main/resources/graphql/schema.graphqls` og er implementeret i `graphql/FlightController.java`.
`@PreAuthorize("hasRole('OPERATIONS')")` sidder på de fem mutationer; queries og `@SchemaMapping`-felterne er åbne.
Felterne `Airline.aircraft` (sorteret efter `registration`), `Airline.flights` (efter afgangstid),
`Flight.seats(onlyAvailable = false)`, `Flight.seat(seatNumber)` (uden forskel på store/små bogstaver, ukendt sæde giver
`null`), `Flight.availableSeatCount` og `Seat.price` (beregnet) løses af `@SchemaMapping`-metoder i samme controller.
Rollen læses fra tokenets `realm_access.roles` (`config/KeycloakRoleConverter`: `OPERATIONS` → `ROLE_OPERATIONS`).

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
| `GET /actuator/health/liveness`, `/readiness` | REST | Alle | Probes (readiness-gruppen: `readinessState`, `db`, `rabbit`) |
| `GET /actuator/info` | REST | Alle | Spring Boot Actuator |
| `GET /actuator/metrics/**` | REST | OPERATIONS | Fx `outbox.pending` (se [Drift](#drift)); alle andre stier end GraphQL, health, info og GraphiQL kræver OPERATIONS (`config/SecurityConfig`) |

Fejlkoder i `errors[].extensions.code`, som servicen faktisk giver: `NOT_FOUND`, `VALIDATION_ERROR`, `INVALID_STATE`,
`CONFLICT`, `UNAUTHORIZED` (mutation uden token), `FORBIDDEN` (token uden rollen `OPERATIONS`, fx anna) og
`INTERNAL_ERROR` (`SEAT_TAKEN` findes i `ErrorCode`, men kastes ikke her); GraphQL-fejlene kommer med HTTP 200. Et
udløbet, fremmed eller ugyldigt token afvises med HTTP 401 og `WWW-Authenticate: Bearer error="invalid_token"`, før
GraphQL kører – også på en åben query. Fælles fejlmodel og roller: [GraphQL-fejl](../docs/architecture.md#graphql-fejl),
[Operation × rolle](../docs/architecture.md#operation--rolle) og
[Fejl og frontendens reaktion](../docs/architecture.md#fejl-og-frontendens-reaktion).

Åben query uden token mod compose-stakken (`flight(id: 2)` er `SK1409` til `ARN` i seed-data; status og ledige sæder
er udeladt, fordi smoke-testen aflyser og booker afgange):

```bash
Q='{ flight(id: 2) { flightNumber destination gate basePrice currency
     business: seat(seatNumber: "1A") { seatClass price }
     economy: seat(seatNumber: "12C") { seatClass price } } }'
jq -n --arg q "$Q" '{query: $q}' \
  | curl -s http://localhost:8081/api/flights/graphql -H 'Content-Type: application/json' -d @- | jq -c .data
# {"flight":{"flightNumber":"SK1409","destination":"ARN","gate":"A3","basePrice":649.00,"currency":"DKK",
#  "business":{"seatClass":"BUSINESS","price":1622.50},"economy":{"seatClass":"ECONOMY","price":649.00}}}
```

Mutation med OPERATIONS-token (afgangen har allerede gate `A3`; `a3` normaliseres til `A3`, så det er en no-op uden
event – også på en aflyst afgang). Tokenet gælder 5 minutter:

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s http://localhost:8081/api/flights/graphql -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { updateGate(flightId: 2, gate: \"a3\") { flightNumber gate } }"}' | jq -c .
# {"data":{"updateGate":{"flightNumber":"SK1409","gate":"A3"}}}
# samme kald uden token:          extensions.code "UNAUTHORIZED"     ("Authentication required: send a Bearer token")
# med anna/anna-token:            extensions.code "FORBIDDEN"        ("Your role does not allow this operation")
# Authorization: Bearer not.a.jwt -> HTTP 401, WWW-Authenticate: Bearer error="invalid_token", …
# gate: " " (med ops-token):      extensions.code "VALIDATION_ERROR" ("gate: must not be blank")
# availableSeats(flightId: 999):  extensions.code "NOT_FOUND"        ("Flight not found: 999")
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
- **Gate:** `updateGate` trimmer og gemmer med store bogstaver (`a3` → `A3`); samme gate igen er en no-op uden event.
  Tom gate → `VALIDATION_ERROR`. `updateGate` er ikke blokeret på en aflyst afgang. Ved `createFlight` gemmes `gate`
  som angivet (højst 10 tegn).
- **Oprettelse:** `scheduledArrival` skal være efter `scheduledDeparture` (`VALIDATION_ERROR`); ukendt selskab/fly →
  `NOT_FOUND`; eksisterende `iataCode`, `registration` eller `flightNumber` + afgangstid → `CONFLICT`. Det kontrolleres
  ikke, at flyet tilhører selskabet.
- **Inputvalidering** (Bean Validation → `VALIDATION_ERROR`): `iataCode` 2–3 alfanumeriske tegn, `flightNumber` 3–8
  alfanumeriske tegn, `origin`/`destination` præcis 3 tegn, `gate` ≤ 10, `basePrice` ≥ 0 med højst 8+2 cifre,
  `totalSeats` 1–1000, `registration` ≤ 20, `name`/`country`/`model` ≤ 100. `iataCode`, `flightNumber`, `origin`,
  `destination` og `registration` gemmes med store bogstaver.
- **Sæde fra events:** `booking.confirmed` sætter `is_available = false`, `booking.cancelled` sætter `true`
  (`FlightService.setSeatAvailability`). Handleren sætter blot flaget; et ukendt fly eller sæde giver `NOT_FOUND`, og
  beskeden ender i DLQ'en (se [Events](#events)).

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
| `flight-service.booking-events` | `booking.#` | `booking.confirmed` | Sædet (`payload.flightId` + `seatNumber`) sættes optaget |
| | | `booking.cancelled` | Sædet sættes frit; uden `flightId`/`seatNumber` sker intet |
| | | øvrige `booking.*` | Ignoreres, men registreres i `processed_event` |

Leveringsgarantier: events skrives til `outbox_event` i samme transaktion som ændringen og sendes af `OutboxRelay` med
publisher confirms (at-least-once, rækkefølge bevaret pr. producent); indgående events dedupliceres på `eventId` i
`processed_event` i samme transaktion som sæde-ændringen. En besked, der fejler (ukendt sæde eller fly, ulæselig JSON),
prøves 3 gange og ender derefter via `airport.events.dlx` i `flight-service.dlq`. Detaljer:
[Køer, bindings og dead-letter](../docs/events.md#køer-bindings-og-dead-letter) og
[Leveringsgarantier](../docs/events.md#leveringsgarantier).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/flight_db` | `flight_db`; compose-porten `5433` er flight-db's |
| `DB_USERNAME` / `DB_PASSWORD` | `flight` / `flight` | DB-credentials (Secret `flight-service-secret` i Kubernetes) |
| `GRAPHQL_PATH` | `/api/flights/graphql` | Samme sti lokalt og bag Ingress |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender usendte events |
| `SERVER_PORT` | `8080` | HTTP-port i containeren (compose mapper til `8081`) |
| `SPRING_PROFILES_ACTIVE` | – | `prod` (Kubernetes): GraphiQL slået fra og JSON-logs; compose sætter `dev` (= defaults) |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/airport` | Den streng, tokenets `iss` skal være lig med; compose samme værdi, kind `http://localhost:8090/auth/realms/airport` |
| `JWK_SET_URI` | `http://localhost:8180/realms/airport/protocol/openid-connect/certs` | Hvor Keycloaks signeringsnøgler hentes; compose `http://keycloak:8080/realms/airport/protocol/openid-connect/certs`, kind `http://keycloak:8080/auth/realms/airport/protocol/openid-connect/certs` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Kommasepareret (`app.cors.allowed-origins`); anvendes i `SecurityConfig` på alle stier, så preflight ikke kræver token |
| `RABBITMQ_*` | `localhost:5672`, `airport`/`airport` | Fælles for alle services – se [Konfiguration](../README.md#konfiguration) |

Begge OIDC-værdier er sat, så Spring bruger aldrig OIDC discovery – se
[Konfiguration: issuer og JWKS](../docs/architecture.md#konfiguration-issuer-og-jwks). Faste værdier uden variabel:
`app.outbox.batch-size` 100, `confirm-timeout-ms` 5000, `retention` 7d (ældre sendte rækker slettes, tjekkes hver
time), listener-retry 3 forsøg (500 ms, ×2, højst 3 s) og derefter reject uden requeue, CORS-metoderne `GET`, `POST`,
`OPTIONS`. Kubernetes-værdierne står i ConfigMap `flight-service-config` og Secret `flight-service-secret` i
`k8s/services/flight-service.yaml`.

## Kør lokalt

```bash
# med compose (starter flight-db og rabbitmq via depends_on; keycloak kun for at få et token til mutationer)
docker compose up -d --build keycloak flight-service    # API: http://localhost:8081/api/flights/graphql
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)

# uden Docker for selve servicen (defaults i application.yml peger på compose-portene 5433, 5672 og 8180)
docker compose up -d flight-db rabbitmq keycloak
cd flight-service && mvn spring-boot:run          # http://localhost:8080/api/flights/graphql
```

Testbrugere: `anna`/`anna` (PASSENGER) og `ops`/`ops` (OPERATIONS) – se [Login og roller](../README.md#login-og-roller).

Kører hele compose-stakken samtidig, optager frontenden port 8080 – start så med `SERVER_PORT=8091 mvn spring-boot:run`.

GraphiQL (åben, ikke i `prod`): `http://localhost:8081/graphiql?path=/api/flights/graphql` i compose,
`http://localhost:8080/graphiql?path=/api/flights/graphql` med `mvn spring-boot:run`; til mutationer sættes
`{"Authorization": "Bearer <token>"}` under *Headers*. I Kubernetes (uden GraphiQL) er API'et
`http://localhost:8090/api/flights/graphql` gennem Ingress på kind ([k8s/README.md](../k8s/README.md#alternativ-kind))
og `http://<minikube ip>/api/flights/graphql` på [minikube](../k8s/README.md#minikube); token fra
`http://localhost:8090/auth/realms/airport/protocol/openid-connect/token` ([Keycloak](../k8s/README.md#keycloak-login)).

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `PricingServiceTest` (5) | Unit | ECONOMY = basispris, BUSINESS × 2,5 (899 → 2247,50), FIRST × 4, `HALF_UP`-afrunding (333,33 × 2,5 = 833,33), negativ basispris afvises |
| `SeatGeneratorTest` (4) | Unit | 180 unikke sæder `1A`–`30F`, række 1–2 BUSINESS, antal der ikke går op i 6, 0 sæder afvises |
| `FlightServiceIntegrationTest` (8) | Integration (HTTP) | Seed via Flyway; filter (`destination: "lhr"`) + pris `1A` på LHR = 2247,50; `booking.confirmed` optager sædet idempotent (samme `eventId` to gange) og `booking.cancelled` frigiver det; aflysning giver `flight.status.changed` + `flight.cancelled` og anden aflysning `INVALID_STATE`; `createFlight` giver 100 sæder og `flight.created` gennem outboxen; outbox: `publish` uden transaktion afvises, rollback efterlader intet event, relayet prøver igen til brokeren bekræfter (`outbox.pending` = 0); mutationerne sendes med et OPERATIONS-token |
| `SecurityIntegrationTest` (7) | Integration (HTTP) | `flights` og readiness uden token; `updateGate`/`updateFlightStatus` uden token `UNAUTHORIZED`, PASSENGER `FORBIDDEN`, OPERATIONS tilladt (`DELAYED`); udløbet/fremmed/ugyldigt token → HTTP 401 med `invalid_token`; `/actuator/metrics` giver 401 uden token og 200 med OPERATIONS; CORS-preflight fra `http://localhost:8080` med `authorization`-header |

`TestTokens` (ingen tests) erstatter Keycloak: den minter RS256-JWT'er med Keycloaks claims, signeret med en testnøgle
(`passenger()` = anna, `operations()` = ops), og leverer en `JwtDecoder`, der stoler på nøglen. Begge integrationstests
går gennem den rigtige HTTP-filterkæde og starter PostgreSQL 16 og RabbitMQ 3.13 med Testcontainers, så **Docker skal
køre**.

```bash
cd flight-service
mvn test                 # 24 tests
mvn -B -ntp -Pci verify  # det samme som backend-jobbet i CI (.github/workflows/ci.yml): tests + Checkstyle + SpotBugs
```

CI kører kommandoen for hver service ved hvert push – se [CI (GitHub Actions)](../README.md#ci-github-actions).

## Drift

- **Health** (åben, uden token): liveness på `/actuator/health/liveness`, readiness på `/actuator/health/readiness`
  (grupperne `readinessState`, `db`, `rabbit`; `show-details: always`). Compose' healthcheck kalder readiness, og
  booking-service starter først, når flight-service er `healthy`.
- **Probes** (`k8s/services/flight-service.yaml`): startup (op til 30 × 5 s) og liveness på `/actuator/health/liveness`,
  readiness på `/actuator/health/readiness`. Der er ingen initContainer: er `flight-db` ikke klar, fejler opstarten
  (Flyway), og Kubernetes genstarter containeren.
- **Ressourcer:** 2 replicas, requests `250m`/`512Mi`, limits `1000m`/`1Gi`,
  `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`. Imaget bygges i to trin (Maven 3.9/Temurin 21 →
  `eclipse-temurin:21-jre-alpine`) og kører som ikke-root-brugeren `app`.
- **Flere replicas:** `OutboxRelay` tager en Postgres advisory lock (`pg_try_advisory_xact_lock`), så kun én pod sender
  events pr. poll, og rækkefølgen bevares. Begge pods lytter på `flight-service.booking-events` (competing consumers);
  `processed_event` fanger dubletter. To forskellige events om samme sæde kan behandles i hver sin pod, og da handleren
  blot sætter flaget, bestemmer den transaktion, der committer sidst, værdien. HTTP-siden er stateless (ingen session;
  hver pod validerer selv tokenet med nøglerne fra `JWK_SET_URI`), så ethvert kald kan ramme enhver pod.
- **Outbox-backlog:** gauge `outbox.pending` på `/actuator/metrics/outbox.pending`, som kræver et OPERATIONS-token
  (Ingress router ikke `/actuator`; kommandoen står under listen), og rækker med `published_at IS NULL`, `attempts`
  og `last_error` i `outbox_event` – se
  [Test af transactional outbox](../README.md#test-af-transactional-outbox-broker-nede).
- **Loglinjer at kende:** `Created flight … with … seats`, `Flight … status … -> …`, `Flight … gate … -> …`,
  `Received event … eventId=…`, `Seat … on flight … is now taken|available`, `Skipping already processed event …`,
  `Queued event … in outbox` / `Published event …` og WARN `Outbox: … event(s) could not be published (attempt …)`
  (første forsøg og hvert 20.). Mens et indgående event behandles, står `eventId` og `eventType` i MDC. I `prod` er
  loggen JSON (logstash-format).

Outbox-backloggen i compose, med `TOKEN` for `ops` fra [Kør lokalt](#kør-lokalt):

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8081/actuator/metrics/outbox.pending | jq '.measurements[0].value'
# 0.0 = intet usendt i outboxen; uden token svarer endpointet HTTP 401
```

## Designvalg

| Valg | Begrundelse | Se |
|------|-------------|----|
| Pris beregnes af `basePrice` × klasse, gemmes ikke pr. sæde | Én pris at vedligeholde pr. afgang; spec'ens sædetabel har ingen pris | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| booking-service henter pris og sæde synkront | Altid aktuel pris og sædestatus uden en kopi af sædekortet i `booking_db` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Sædelayout efter fast regel (6 pr. række, række 1–2 BUSINESS) | Deterministisk og ens i seed og `SeatGenerator` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Sædet optages først ved `booking.confirmed` | En ubetalt booking holder sædet i `booking_db` (`ux_booking_active_seat`), men sædet vises ledigt her indtil betaling – kendt begrænsning | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Transactional outbox | Afgang og event committes atomisk; intet tabt event ved broker-nedbrud | [Messaging](../docs/architecture.md#messaging) |
| Offentlige queries, `@PreAuthorize` pr. mutation | Afgangstavlen skal kunne læses uden login på samme endpoint, som mutationerne bruger | [Hvad er offentligt?](../docs/architecture.md#hvad-er-offentligt) |
| Binding `booking.#` | `*` matcher kun ét segment | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Samme status/gate igen er en no-op | Gentagne mutationer giver ingen ekstra events | `FlightService.updateStatus`/`updateGate` |

## Se også

- [docs/architecture.md](../docs/architecture.md) – overblik, flows A og C, messaging, designvalg;
  [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller) – login, roller, issuer/JWKS
- [docs/events.md](../docs/events.md) – event-kontrakten
- [booking-service/README.md](../booking-service/README.md) – den synkrone klient og producenten af `booking.*`
- [README.md](../README.md#kør-lokalt-med-docker) – hele stakken og [testbrugere](../README.md#login-og-roller);
  [retry/DLQ-demo](../README.md#test-af-retry--dead-letter-queue)
- [k8s/README.md](../k8s/README.md) – minikube/kind og [Keycloak](../k8s/README.md#keycloak-login)
