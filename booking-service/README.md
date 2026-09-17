# booking-service

Bookinger og passagerer: en passager reserverer et sæde på et fly, betaler, checker ind eller aflyser. Servicen er
kilde til sandhed for bookingens tilstand og driver *booking-sagaen* – den reagerer på betalinger, aflyste fly og
manglende betaling og fortæller resten af systemet om hver ændring med events. Den håndterer ikke selve betalingen
(payment-service), sæderne (flight-service) eller bagagen (baggage-service), men har en read model, der samler
det hele til *Min booking*.

| | |
|---|---|
| Ansvar | Bookinger, passagerer og booking-sagaen |
| Port | compose `8082` · Kubernetes via Ingress `/api/bookings` |
| API | GraphQL `/api/bookings/graphql` |
| Database | `booking_db` (PostgreSQL 16, Flyway `V1`–`V6`) |
| Events ud | `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.checkedin`, `booking.payment.rejected` |
| Events ind | `payment.*` (kø `booking-service.payment-events`), `flight.*` (`booking-service.flight-events`), `baggage.*` (`booking-service.baggage-events`) |
| Synkront kald | flight-service GraphQL (pris og ledigt sæde ved `createBooking`) |
| Image | `airport/booking-service:local` |

## Ansvar og data

| Tabel | Indhold | Rolle |
|-------|---------|-------|
| `passenger` | Navn, e-mail (unik, case-insensitiv), pasnummer, fødselsdato | Skrivemodel. En passager genbruges på e-mailen og opdateres ved næste booking |
| `booking` | Reference, passager, fly, sæde, pris, status, `cancellation_reason`, `version` | Skrivemodel og kilde til sandhed. Et partielt unikt indeks `(flight_id, seat_number) WHERE status <> 'CANCELLED'` sikrer ét aktivt sæde |
| `booking_overview` | Én række pr. booking med passager, betalinger og bagage (JSONB) | Read model (CQRS) til *Min booking* – skrives kun af `BookingOverviewProjector` |
| `outbox_event` | Events, der venter på eller er sendt til RabbitMQ, med `traceparent` | Transactional outbox |
| `processed_event` | `eventId` for hvert modtaget event | Idempotens for consumers |

- **Snapshot af flydata:** `flight_number`, `departure_time`, `gate` og `flight_status` kopieres fra flight-service,
  når bookingen oprettes, og holdes opdateret fra `flight.*`-events (last-writer-wins på `occurredAt`, med hvert sit
  tidsstempel for status og gate; `CANCELLED` er endelig). *Min booking* viser gate og status uden et kald til
  flight-service.
- **Read model:** betalinger (`payment.*`) og bagage (`baggage.*`) projiceres ind i `booking_overview`, så *Min
  booking* er ét opslag. Bookingens egne ændringer projiceres i samme transaktion som kommandoen.
- **Gør bevidst ikke:** gemmer ingen kortdata og kender ikke betalingsstatus som andet end en kopi; bestemmer ikke,
  om et sæde er ledigt (flight-service svarer synkront), og markerer ikke sæder optaget (flight-service gør det selv
  på `booking.confirmed`).

## API

GraphQL på `/api/bookings/graphql` (GraphiQL på `/graphiql` i `dev`-profilen). Rollerne håndhæves med
`@PreAuthorize` på `BookingController` og `BookingOverviewController`:

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `booking(id)`, `bookingByReference(reference)`, `passenger(id)` | query | Alle | Skrivemodellen – bruges af booking- og betalingsflowet (fx polling på `CONFIRMED`). `Booking.paymentDueAt` viser betalingsfristen |
| `bookingsByPassenger(email)` | query | PASSENGER (kun egen e-mail fra tokenet) / OPERATIONS | Bookinger for en e-mail, nyeste først |
| `bookingOverview(reference)` | query | PASSENGER / OPERATIONS | Read model: booking, passager, betalinger og bagage i ét opslag |
| `myBookings` | query | PASSENGER / OPERATIONS | Read model for e-mailen i tokenet |
| `createBooking(flightId, seatNumber, passenger)` | mutation | PASSENGER / OPERATIONS | Henter pris og sæde hos flight-service, opretter bookingen i `PENDING_PAYMENT` |
| `cancelBooking(reference)` | mutation | PASSENGER / OPERATIONS | Aflyser (årsag "Cancelled by passenger"); betalingen refunderes af payment-service |
| `checkIn(reference)` | mutation | PASSENGER / OPERATIONS | `CONFIRMED` → `CHECKED_IN` |

Fejlkoder i `errors[].extensions.code` (fælles fejlmodel i
[architecture.md](../docs/architecture.md#graphql-fejl)):

| Kode | Hvornår |
|------|---------|
| `SEAT_TAKEN` | Sædet har allerede en aktiv booking, eller flight-service siger, at det ikke er ledigt |
| `INVALID_STATE` | Forkert tilstand (check-in før betaling, aflyse to gange, booke et aflyst eller afgået fly) |
| `NOT_FOUND` | Ukendt reference, fly eller sæde |
| `UPSTREAM_UNAVAILABLE` | flight-service svarer ikke eller svarer med en fejl |
| `CONFLICT` | Bookingen blev ændret samtidig (optimistisk låsning) – prøv igen |
| `VALIDATION_ERROR` | Fx sædenummer, der ikke ligner `12C`, ugyldig e-mail eller pasnummer (5–20 bogstaver/cifre) |
| `UNAUTHORIZED` / `FORBIDDEN` | Intet gyldigt token / forkert rolle eller en andens e-mail i `bookingsByPassenger` |

Eksempel gennem Ingress'en på kind (kørt 17-09-2026):

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  http://localhost:8090/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)

curl -s http://localhost:8090/api/bookings/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createBooking(flightId: 2, seatNumber: \"14B\", passenger: {firstName: \"Anna\", lastName: \"Jensen\", email: \"anna@example.com\", passportNumber: \"P1234567\"}) { bookingReference status price currency paymentDueAt } }"}'
# {"data":{"createBooking":{"bookingReference":"CU4WJB","status":"PENDING_PAYMENT","price":649.0,"currency":"DKK","paymentDueAt":"2026-09-17T07:37:36.695Z"}}}

curl -s http://localhost:8090/api/bookings/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"{ bookingOverview(reference: \"CU4WJB\") { status flightNumber seatNumber payments { status } baggage { tagNumber status } } }"}'
# {"data":{"bookingOverview":{"status":"PENDING_PAYMENT","flightNumber":"SK1409","seatNumber":"14B","payments":[],"baggage":[]}}}
```

## Forretningsregler

- **Livscyklus:** `PENDING_PAYMENT` → `CONFIRMED` (kun ved `payment.completed`) → `CHECKED_IN` (kun fra `CONFIRMED`);
  `CANCELLED` fra alle andre tilstande og aldrig tilbage. Overgangene ligger i `domain/Booking`.
- **Ét aktivt sæde:** et sæde kan kun have én booking, der ikke er `CANCELLED` (tjek før insert + unikt indeks mod
  race conditions) → `SEAT_TAKEN`. En aflyst booking frigiver sædet med det samme.
- **Pris** fastsættes af flight-service ved oprettelsen (`basePrice` × klassemultiplikator) og ændres ikke bagefter.
- **Reference:** 6 tegn fra `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (uden 0/O/1/I), op til 5 forsøg ved kollision.
- **Passager:** samme e-mail (uanset store/små bogstaver) er samme passager; navn og pas opdateres ved en ny booking,
  også i read modellen for tidligere bookinger.
- **Betalingstimeout:** en booking, der ikke er betalt efter `PAYMENT_TIMEOUT` (15 min.), aflyses af
  `PaymentTimeoutJob` med årsagen "Payment not received within 15 minutes".
- **For sen betaling:** `payment.completed` for en `CANCELLED` booking ændrer ikke bookingen; servicen publicerer
  `booking.payment.rejected`, og payment-service refunderer betalingen.
- **Aflyst fly:** `flight.cancelled` aflyser alle ikke-aflyste bookinger på flyet (årsag "Flight cancelled").
- **Afvist betaling:** `payment.failed` aflyser en `PENDING_PAYMENT`-booking ("Payment failed: <årsag>").

## Events

**Publicerer** (via transactional outbox; payload beskrevet i [events.md](../docs/events.md#events-fra-booking-service)):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `booking.created` | `createBooking` | `bookingReference`, `flightId`, `seatNumber`, `price`, `status`, `passenger` |
| `booking.confirmed` | `payment.completed` for en ubetalt booking | Samme payload, `status: CONFIRMED` |
| `booking.cancelled` | Passageren aflyser, betaling afvist, fly aflyst, betalingstimeout | Samme payload + `reason` |
| `booking.checkedin` | `checkIn` | Samme payload, `status: CHECKED_IN` |
| `booking.payment.rejected` | `payment.completed` for en allerede aflyst booking | `bookingReference`, `paymentId`, `amount`, `currency`, `reason` |

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `booking-service.payment-events` | `payment.#` | `payment.completed`, `payment.failed`, `payment.refunded` | Bekræft / aflys / `booking.payment.rejected`; betalingslinjen i read modellen |
| `booking-service.flight-events` | `flight.#` | `flight.status.changed`, `flight.gate.changed`, `flight.cancelled` | Flysnapshot på bookingerne; aflys ved aflyst fly |
| `booking-service.baggage-events` | `baggage.#` | `baggage.registered`, `baggage.status.changed` | Kun read modellen (bagagelinjer) |

Servicen erklærer også notification-jobs kø `notifications` (`booking.#`), så booking-events gemmes, før jobbet har
kørt første gang. Leveringsgarantier (outbox, `processed_event`, 3 forsøg → `booking-service.dlq`, rækkefølge og
kommutative handlers) står i [events.md](../docs/events.md#leveringsgarantier); den maskinlæsbare kontrakt i
[asyncapi.yaml](../docs/asyncapi.yaml).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `FLIGHT_SERVICE_URL` | `http://localhost:8081/api/flights/graphql` | flight-service's GraphQL-endpoint (compose: `http://flight-service:8080/...`) |
| `FLIGHT_SERVICE_TIMEOUT` | `5s` | Connect- og read-timeout for kaldet til flight-service |
| `PAYMENT_TIMEOUT` | `15m` | Hvor længe en booking må være ubetalt, før den aflyses |
| `PAYMENT_TIMEOUT_CHECK_INTERVAL_MS` | `30000` | Hvor ofte `PaymentTimeoutJob` leder efter ubetalte bookinger |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte outbox-relayet sender ventende events |
| `GRAPHQL_PATH` | `/api/bookings/graphql` | Stien GraphQL serveres på (samme lokalt og bag Ingress) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/booking_db`, `booking`/`booking` | Database |
| `RABBITMQ_*`, `OIDC_ISSUER_URI`, `JWK_SET_URI`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE` | se rod-README | Fælles for alle services – [Konfiguration](../README.md#konfiguration) |

## Kør lokalt

```bash
# hele stakken (fra repo-roden); servicen på http://localhost:8082/api/bookings/graphql, GraphiQL på /graphiql
docker compose up -d --build

# kun denne service fra IDE/terminal: Postgres (5434), RabbitMQ, Keycloak og flight-service fra compose
docker compose up -d booking-db rabbitmq keycloak flight-service
cd booking-service && mvn spring-boot:run

# betalingstimeout til en demo (se scripts/demo-saga.sh)
PAYMENT_TIMEOUT=30s docker compose up -d --wait booking-service
```

## Test

`mvn -Pci verify` kører 51 tests (kræver Docker til Testcontainers: PostgreSQL 16 og RabbitMQ 3.13) samt Checkstyle og
SpotBugs. flight-service er mocket (`@MockitoBean FlightClient`); JWT'er udstedes af `TestTokens`.

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `BookingServiceIntegrationTest` (9) | integration | Hele livscyklussen, `SEAT_TAKEN`, idempotent forbrug af `payment.completed`, flysnapshot og aflysning, validering, outbox-garantier (rollback, retry indtil broker-bekræftelse), kontrakt for køen `notifications` |
| `BookingOverviewIntegrationTest` (7) | integration | Read modellen: egne ændringer uden ventetid, betalinger og bagage fra events, events i omvendt rækkefølge, ukendte bookinger, `myBookings`, login påkrævet |
| `EventOrderIntegrationTest` (3) | integration | Alle rækkefølger af `flight.*`-events giver samme status/gate (6 og 24 permutationer); `@Version` stopper et lost update |
| `SagaCompensationIntegrationTest` (2) | integration | Betalingstimeout (3 s i testen) og `booking.payment.rejected` ved for sen betaling |
| `ObservabilityIntegrationTest` (2) | integration | Trace-id fra `traceparent` i logs, outbox og næste events header; tællerne `events.consumed`/`events.published` |
| `SecurityIntegrationTest` (7) | integration | Roller pr. operation, egen e-mail i `bookingsByPassenger`, udløbne/fremmede tokens, CORS, `/actuator/prometheus` åben |
| `BookingStateTest` (10) | unit | Tilstandsmaskinen og reglerne for flysnapshot (last-writer-wins, `CANCELLED` endelig) |
| `OverviewLinesTest` (7) | unit | Fletning af betalings- og bagagelinjer er kommutativ og idempotent |
| `BookingReferenceGeneratorTest` (3) | unit | Referenceformat og alfabet |
| `PaymentTimeoutJobTest` (1) | unit | Årsagsteksten for timeout |

Samarbejdet med payment-service med de rigtige images testes i [system-tests](../system-tests/README.md).

## Drift

- **Probes:** `/actuator/health/liveness` og `/actuator/health/readiness` (readiness inkluderer database og RabbitMQ).
- **Metrics** på `/actuator/prometheus`: `outbox_pending`, `events_published_total{type}`,
  `events_consumed_total{type,outcome}`, HTTP-svartider som histogram og `http_client_requests_seconds` for kaldet til
  flight-service. Relevante alarmer: `OutboxBacklog`, `MessagesDeadLettered` (`booking-service.dlq`),
  `EventHandlingFailing` – se [Observability](../docs/architecture.md#observability-metrics-logs-og-alarmer).
- **Logs:** JSON i `prod`-profilen med `traceId`/`spanId`; nyttige linjer: `Created booking …`, `Booking … confirmed
  after payment`, `Booking … cancelled: …`, `Payment … arrived for CANCELLED booking …`, `Ignoring …` (forældet event).
- **Kubernetes:** `k8s/base/services/booking-service.yaml` – 1 replica, `requests` 150m/256Mi, `limits` 500m/640Mi,
  initContainer der venter på `booking-db`, read-only rodfilsystem som uid 100. Flere replicas er sikre: outbox-relayet
  tager en advisory lock, consumers er idempotente, og `PaymentTimeoutJob` beskyttes af `@Version`.

## Designvalg

| Valg | Begrundelse | Mere |
|------|-------------|------|
| Synkront kald til flight-service ved `createBooking` | Altid korrekt pris og ledighed; ingen kopi af sædekortet | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Read model `booking_overview` (CQRS) | *Min booking* i ét kald, uafhængigt af payment- og baggage-service | [CQRS](../docs/architecture.md#cqrs-booking_overview-til-min-booking) |
| Choreograferet saga med timeout og `booking.payment.rejected` | Ingen distribueret transaktion; et nyt event frem for et gentaget `booking.cancelled`, som ville frigive et genbooket sæde | [Saga](../docs/architecture.md#saga-bookingen-som-en-kæde-af-lokale-transaktioner) |
| Last-writer-wins på flysnapshot, `@Version` på `booking` | Flyevents kan komme i vilkårlig orden, og tre tråde skriver samme række | [Rækkefølge og kommutativitet](../docs/events.md#rækkefølge-og-kommutativitet) |
| Transactional outbox med `traceparent` | Tilstand og event committes atomisk; trace-id følger eventet | [Messaging](../docs/architecture.md#messaging) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – Flow A og C, sikkerhed, CQRS, saga, observability
- [docs/events.md](../docs/events.md) og [docs/asyncapi.yaml](../docs/asyncapi.yaml) – eventkontrakterne
- [scripts/e2e-smoke.sh](../scripts/e2e-smoke.sh) og [scripts/demo-saga.sh](../scripts/demo-saga.sh)
- [system-tests/README.md](../system-tests/README.md) – booking ↔ payment med de byggede images
