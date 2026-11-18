# booking-service

Bookinger og passagerer: en passager reserverer et sæde på et fly, betaler, checker ind eller aflyser. Servicen er
kilde til sandhed for bookingens tilstand – den reagerer på betalinger og flyændringer og fortæller resten af
systemet om hver ændring med events. Bookinger er persondata, så alle operationer kræver login (PASSENGER eller
OPERATIONS), og en passager kan kun se og ændre sine egne. Den håndterer ikke selve betalingen (payment-service),
sæderne (flight-service) eller bagagen (baggage-service), men gemmer et snapshot af flydata, så *Min booking* kan vise
gate og flystatus uden et kald til flight-service.

| | |
|---|---|
| Ansvar | Bookinger og passagerer |
| Port | compose `8082` · Kubernetes via Ingress `/api/bookings` |
| API | GraphQL `/api/bookings/graphql` (Bearer-token fra Keycloak) · REST kun `/actuator/*` (health, info, metrics) |
| Database | `booking_db` (PostgreSQL 16, Flyway `V1`–`V2`) |
| Events ud | `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.checkedin` |
| Events ind | `payment.completed`, `payment.failed` (kø `booking-service.payment-events`), `flight.status.changed`, `flight.gate.changed`, `flight.cancelled` (kø `booking-service.flight-events`) |
| Synkront kald | flight-service GraphQL uden token (pris og ledigt sæde ved `createBooking`; flight-queries er offentlige) |
| Image | `airport/booking-service:local` |

## Ansvar og data

| Tabel | Indhold | Rolle |
|-------|---------|-------|
| `passenger` | Navn, e-mail (unik, case-insensitiv: `ux_passenger_email` på `lower(email)`), pasnummer, fødselsdato | En passager genbruges på e-mailen og opdateres ved næste booking. E-mailen er også ejerskabsnøglen mod tokenet |
| `booking` | Reference, passager, `flight_id`, sæde, pris, valuta, status, `created_at`/`updated_at` + flysnapshot | Kilde til sandhed for bookingen. Det partielle unikke indeks `ux_booking_active_seat` på `(flight_id, seat_number) WHERE status <> 'CANCELLED'` sikrer ét aktivt sæde |
| `outbox_event` | Events, der venter på eller er sendt til RabbitMQ (`published_at`, `attempts`, `last_error`) | Transactional outbox; sendte rækker slettes efter 7 dage |
| `processed_event` | `event_id` og `event_type` for hvert modtaget event | Idempotens for consumers |

- **Snapshot af flydata:** `flight_number`, `departure_time`, `gate` og `flight_status` kopieres fra flight-service,
  når bookingen oprettes. `flight.status.changed` (status, og gate hvis feltet er sat), `flight.gate.changed` (gate)
  og `flight.cancelled` (status `CANCELLED`) opdaterer `flight_status`/`gate` på alle bookinger på flyet – kun felter
  med en ny værdi røres, og værdien fra det senest behandlede event gælder. `departure_time` opdateres ikke.
- **Gør bevidst ikke:** gemmer ingen kortdata og ingen betalingsstatus (den reagerer kun på `payment.*`); bestemmer
  ikke, om et sæde er ledigt i flight-service (det spørger synkront), og markerer ikke sæder optaget (flight-service
  gør det selv på `booking.confirmed`); kender ikke bagagen; udsteder ingen tokens og kender ingen kodeord
  (Keycloak) – den validerer kun tokenet.

## API

GraphQL på `/api/bookings/graphql` (GraphiQL på `/graphiql?path=/api/bookings/graphql`, slået fra i `prod`-profilen).
Skemaet ligger i `src/main/resources/graphql/schema.graphqls`, resolverne i `graphql/BookingController`. Alle
operationer har `@PreAuthorize("hasAnyRole('PASSENGER', 'OPERATIONS')")`, og controlleren tjekker derudover
ejerskab (se [Forretningsregler](#forretningsregler)). Rollerne for hele systemet står i
[Operation × rolle](../docs/architecture.md#operation--rolle):

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `booking(id)` | query | PASSENGER (kun egne) / OPERATIONS | Én booking på id; `null` hvis den ikke findes |
| `bookingByReference(reference)` | query | PASSENGER (kun egne) / OPERATIONS | Én booking på reference (store/små bogstaver er ligegyldige); `null` hvis ukendt. Bruges af frontenden til polling på `CONFIRMED` og til *Min booking* |
| `bookingsByPassenger(email)` | query | PASSENGER (kun egen e-mail) / OPERATIONS | Alle bookinger for en e-mail (case-insensitiv), nyeste først |
| `passenger(id)` | query | PASSENGER (kun sig selv) / OPERATIONS | Passageren med feltet `bookings` (nyeste først; feltet følger reglen for `passenger`) |
| `createBooking(flightId, seatNumber, passenger)` | mutation | PASSENGER (kun med egen e-mail) / OPERATIONS | Henter pris og sæde hos flight-service og opretter bookingen i `PENDING_PAYMENT` |
| `cancelBooking(reference)` | mutation | PASSENGER (kun egne) / OPERATIONS | Aflyser (årsag "Cancelled by passenger"); payment-service refunderer en gennemført betaling |
| `checkIn(reference)` | mutation | PASSENGER (kun egne) / OPERATIONS | `CONFIRMED` → `CHECKED_IN` |
| `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` | REST (GET) | Alle | Probes og info, se [Drift](#drift) |
| `/actuator/metrics/**` | REST (GET) | OPERATIONS | Metrics, fx `outbox.pending` |

`Booking` har ud over `id`, `bookingReference`, `status` og `flightId` flysnapshottet (`flightNumber`, `departureTime`,
`gate`, `flightStatus`), `seatNumber`, `price`, `currency`, `passenger`, `createdAt` og `updatedAt`.

Fejlkoder i `errors[].extensions.code` (fælles fejlmodel i [architecture.md](../docs/architecture.md#graphql-fejl),
login-fejl i [Fejl og frontendens reaktion](../docs/architecture.md#fejl-og-frontendens-reaktion)):

| Kode | Hvornår |
|------|---------|
| `SEAT_TAKEN` | Sædet har allerede en booking, der ikke er `CANCELLED`, eller flight-service siger, at det ikke er ledigt |
| `INVALID_STATE` | Forkert tilstand (check-in før betaling, aflyse to gange, booke et `CANCELLED` eller `DEPARTED` fly) |
| `NOT_FOUND` | Ukendt reference i `cancelBooking`/`checkIn`, ukendt fly eller sæde i `createBooking` |
| `UPSTREAM_UNAVAILABLE` | flight-service svarer ikke (inden for `FLIGHT_SERVICE_TIMEOUT`) eller svarer med en GraphQL-fejl |
| `VALIDATION_ERROR` | Fx sædenummer, der ikke ligner `12C`, tomt navn, ugyldig e-mail, pasnummer der ikke er 5–20 bogstaver/cifre, fødselsdato der ikke ligger i fortiden – feltnavnene står i beskeden |
| `CONFLICT` | Ingen unik reference efter 5 forsøg, eller en anden unik-konflikt i databasen |
| `UNAUTHORIZED` | Intet token (HTTP 200 med GraphQL-fejlen) |
| `FORBIDDEN` | Forkert rolle, en anden passagers booking, eller en e-mail, der ikke er tokenets |
| `INTERNAL_ERROR` | Uventet fejl (logges med stacktrace) |

Et udløbet, fremmed (forkert `iss`) eller ugyldigt token når aldrig GraphQL: svaret er HTTP 401 med
`WWW-Authenticate: Bearer error="invalid_token"`.

Eksempel mod compose-stakken som `anna` (PASSENGER, `anna@example.com`). Smoke-testen aflyser det første
`SCHEDULED` fly, så eksemplet tager det sidste; med friske seed-data er det `LH0833` (id `10`, CPH → FRA, gate `C5`,
grundpris 729 DKK). Sæde `15C` er ECONOMY og findes på alle seed-fly. Referencen er tilfældig:

```bash
# et fly, der kan bookes (flight-service's queries er offentlige)
FLIGHT=$(curl -s http://localhost:8081/api/flights/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ flights(filter: {status: SCHEDULED}) { id } }"}' | jq -r '.data.flights[-1].id')

# token for anna (lever 5 minutter)
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)

curl -s http://localhost:8082/api/bookings/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createBooking(flightId: '"$FLIGHT"', seatNumber: \"15C\", passenger: {firstName: \"Anna\", lastName: \"Jensen\", email: \"anna@example.com\", passportNumber: \"P1234567\"}) { bookingReference status flightNumber seatNumber price currency } }"}'
# {"data":{"createBooking":{"bookingReference":"K7Q2ZP","status":"PENDING_PAYMENT","flightNumber":"LH0833","seatNumber":"15C","price":729.0,"currency":"DKK"}}}
# uden token                         -> HTTP 200, errors[0].extensions.code = "UNAUTHORIZED"
# som anna med email "bo@example.com" -> "FORBIDDEN"; samme sæde igen -> "SEAT_TAKEN"

REF=K7Q2ZP   # bookingReference fra svaret ovenfor
curl -s http://localhost:8082/api/bookings/graphql -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"{ bookingByReference(reference: \"'"$REF"'\") { status flightNumber flightStatus gate seatNumber passenger { email } } }"}'
# {"data":{"bookingByReference":{"status":"PENDING_PAYMENT","flightNumber":"LH0833","flightStatus":"SCHEDULED","gate":"C5","seatNumber":"15C","passenger":{"email":"anna@example.com"}}}}
# med et token for ops (OPERATIONS) -> samme svar; uden token -> "UNAUTHORIZED"
```

## Forretningsregler

- **Ejerskab:** en booking tilhører passageren med den e-mail, den er lavet med. En PASSENGER må kun booke med sin
  egen e-mail (tokenets `email`-claim, sammenlignet case-insensitivt) og kun se, annullere og checke ind på egne
  bookinger, se egne passagerdata og liste bookinger for egen e-mail – ellers `FORBIDDEN`. OPERATIONS må alt for
  alle bookinger. En ukendt booking giver stadig `null` (queries) eller `NOT_FOUND` (mutations).
- **Livscyklus:** `PENDING_PAYMENT` → `CONFIRMED` (kun ved `payment.completed`) → `CHECKED_IN` (kun fra `CONFIRMED`);
  `CANCELLED` fra alle andre tilstande – også efter check-in – og aldrig tilbage. Overgangene ligger i
  `domain/Booking`; en ulovlig overgang giver `INVALID_STATE`.
- **Ét aktivt sæde:** et sæde kan kun have én booking, der ikke er `CANCELLED` – tjek før insert og det partielle
  unikke indeks mod race conditions → `SEAT_TAKEN`. Tjekket sker, før flight-service kaldes, så et sæde med en
  ubetalt booking er optaget her, selv om flight-service stadig viser det ledigt. En aflyst booking frigiver sædet
  med det samme.
- **Kun bookbare fly:** flyet og sædet skal findes (`NOT_FOUND`), og flyet må ikke være `CANCELLED` eller `DEPARTED`
  (`INVALID_STATE`).
- **Pris og valuta** fastsættes af flight-service ved oprettelsen (`basePrice` × klassemultiplikator) og ændres ikke
  bagefter.
- **Reference:** 6 tegn fra `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (uden 0/O/1/I), op til 5 forsøg ved kollision.
  Referencer og sædenumre normaliseres til store bogstaver.
- **Passager:** samme e-mail (uanset store/små bogstaver) er samme passager; e-mailen gemmes med små bogstaver, og
  navn og pasnummer opdateres ved en ny booking (fødselsdato kun, hvis den er angivet).
- **Ubetalt booking** bliver i `PENDING_PAYMENT`, indtil der kommer et betalings-event, eller den aflyses.
- **Betaling gennemført:** `payment.completed` bekræfter en `PENDING_PAYMENT`-booking. For en booking, der allerede er
  `CONFIRMED`/`CHECKED_IN`, ignoreres eventet; for en `CANCELLED` booking ændres intet, og der logges en advarsel.
- **Afvist betaling:** `payment.failed` aflyser en `PENDING_PAYMENT`-booking med årsagen
  `Payment failed: <failureReason>` (bare `Payment failed`, hvis `failureReason` mangler); i andre tilstande ignoreres
  eventet.
- **Aflyst fly:** `flight.cancelled` aflyser alle ikke-aflyste bookinger på flyet (årsag "Flight cancelled") og sætter
  `flight_status = CANCELLED` på alle flyets bookinger.

## Events

**Publicerer** (via transactional outbox; payload beskrevet i
[events.md](../docs/events.md#events-fra-booking-service)):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `booking.created` | `createBooking` | `bookingId`, `bookingReference`, `flightId`, `flightNumber`, `departureTime`, `seatNumber`, `price`, `currency`, `status`, `passenger` |
| `booking.confirmed` | `payment.completed` for en `PENDING_PAYMENT`-booking | Samme payload, `status: CONFIRMED` |
| `booking.cancelled` | `cancelBooking` (passager eller OPERATIONS), betaling afvist, fly aflyst | Samme payload + `reason` |
| `booking.checkedin` | `checkIn` | Samme payload, `status: CHECKED_IN` |

Lyttere: flight-service (`booking.confirmed` → sædet optaget, `booking.cancelled` → sædet frigives), payment-service
(`booking.cancelled` → refundering) og baggage-service (alle fire → `booking_snapshot`).

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `booking-service.payment-events` | `payment.#` | `payment.completed`, `payment.failed` | Bekræft / aflys bookingen (`payment.refunded` modtages, men ignoreres) |
| `booking-service.flight-events` | `flight.#` | `flight.status.changed`, `flight.gate.changed`, `flight.cancelled` | Flysnapshot på bookingerne; aflys alle bookinger ved aflyst fly (`flight.created` modtages, men ignoreres) |

Leveringsgarantier – outbox med publisher confirms, idempotens via `processed_event` og 3 forsøg, før beskeden
dead-letteres via `airport.events.dlx` til `booking-service.dlq` – står i
[events.md](../docs/events.md#leveringsgarantier).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `FLIGHT_SERVICE_URL` | `http://localhost:8081/api/flights/graphql` | flight-service's GraphQL-endpoint (compose og Kubernetes: `http://flight-service:8080/api/flights/graphql`) |
| `FLIGHT_SERVICE_TIMEOUT` | `5s` | Read-timeout for kaldet til flight-service |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/airport` | Den `iss`, tokenet skal have (compose: samme; kind: `http://localhost:8090/auth/realms/airport`) – se [issuer og JWKS](../docs/architecture.md#konfiguration-issuer-og-jwks) |
| `JWK_SET_URI` | `http://localhost:8180/realms/airport/protocol/openid-connect/certs` | Hvor signeringsnøglerne hentes (compose: `http://keycloak:8080/realms/airport/protocol/openid-connect/certs`; kind: `http://keycloak:8080/auth/realms/airport/protocol/openid-connect/certs`) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Kommasepareret; læses som `app.cors.allowed-origins` af `SecurityConfig`, så preflight aldrig kræver token (compose: `http://localhost:8080,http://127.0.0.1:8080`; Kubernetes: `http://airport.local,http://localhost,http://localhost:8090`) |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte outbox-relayet sender ventende events |
| `GRAPHQL_PATH` | `/api/bookings/graphql` | Stien GraphQL serveres på (samme lokalt og bag Ingress) |
| `SERVER_PORT` | `8080` | HTTP-port i containeren (compose mapper den til `8082`) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/booking_db`, `booking`/`booking` | Database |
| `RABBITMQ_*`, `SPRING_PROFILES_ACTIVE` | se rod-README | Fælles for alle services – [Konfiguration](../README.md#konfiguration) |

Outboxens øvrige indstillinger står fast i `app.outbox.*`: batch på 100 rækker, 5 s til broker-bekræftelse, sendte
rækker slettes efter 7 dage (oprydning hver time).

## Kør lokalt

```bash
# hele stakken (fra repo-roden) inkl. Keycloak på http://localhost:8180 (anna/anna, ops/ops);
# servicen på http://localhost:8082/api/bookings/graphql, GraphiQL på /graphiql?path=/api/bookings/graphql
docker compose up -d --build

# kun denne service fra IDE/terminal: Postgres (5434), RabbitMQ, Keycloak (8180) og flight-service (8081) fra
# compose - defaults i application.yml peger på dem; servicen kører så på http://localhost:8080/api/bookings/graphql
docker compose up -d booking-db rabbitmq keycloak flight-service
cd booking-service && mvn spring-boot:run
```

Token til `curl` og testbrugerne står i rod-README'ens [Login og roller](../README.md#login-og-roller).

## Test

`mvn -B -ntp -Pci verify` – det samme som CI-jobbet `backend · booking-service` kører (tests + Checkstyle + SpotBugs;
rapporterne uploades som artefakten `reports-booking-service`, [ci.yml](../.github/workflows/ci.yml)) – kører 24
tests. Integrationstestene kræver Docker til Testcontainers
(`postgres:16-alpine` og `rabbitmq:3.13-management-alpine`), men ingen Keycloak: `TestTokens` (hjælpeklasse, ingen
tests) minter RS256-JWT'er med samme claims som Keycloak og erstatter `JwtDecoder`, så kaldene går gennem den rigtige
HTTP-filterkæde. flight-service er mocket (`@MockitoBean FlightClient`).

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `BookingServiceIntegrationTest` (8) | integration | Hele livscyklussen (opret → `SEAT_TAKEN` → `payment.completed` leveret to gange giver præcis ét `booking.confirmed` → check-in → snapshot via `bookingByReference` → `flight.gate.changed` → `flight.cancelled` → sædet ledigt igen), `payment.failed`, aflysning (en anden passager får `FORBIDDEN`, anden aflysning giver `INVALID_STATE`) og `bookingsByPassenger` (case-insensitiv), validering (`VALIDATION_ERROR` med feltnavne), ukendt reference (`NOT_FOUND` i `checkIn`, `null` fra `bookingByReference`), outbox-garantier (publish uden transaktion afvises, rollback efterlader intet event, relayet prøver igen, indtil brokeren bekræfter) |
| `SecurityIntegrationTest` (7) | integration | Readiness uden token; uden token → `UNAUTHORIZED` (alle fire queries, `createBooking` og `checkIn`); ejerskab (egne bookinger, egen e-mail ved `createBooking` – også med andre store/små bogstaver – og `bookingsByPassenger`, en anden passager får `FORBIDDEN` på `bookingByReference`, `passenger`, `checkIn` og `cancelBooking`); OPERATIONS lister og læser andres bookinger, booker og aflyser; udløbet/fremmed/ugyldigt token → HTTP 401 `invalid_token`; CORS-preflight fra `http://localhost:8080` |
| `BookingStateTest` (6) | unit | Tilstandsmaskinen og at snapshot-opdateringer kun rører ændrede felter |
| `BookingReferenceGeneratorTest` (3) | unit | Referenceformat, alfabet og tilfældighed |

## Drift

- **Probes:** `/actuator/health/liveness` og `/actuator/health/readiness` (readiness inkluderer database og RabbitMQ)
  er åbne uden token. Compose bruger readiness som healthcheck.
- **Outbox-backlog:** gauge `outbox.pending` på `/actuator/metrics/outbox.pending` – kræver et OPERATIONS-token (uden
  token HTTP 401); detaljerne står i `outbox_event` (`published_at IS NULL`, `attempts`, `last_error`).
- **Logs:** JSON (logstash-format) i `prod`-profilen, læsbare linjer ellers; consumers lægger `eventId`/`eventType` i
  MDC. Nyttige linjer: `Created booking …`, `Booking … confirmed after payment`, `Booking … cancelled: …`,
  `Flight … cancelled -> … booking(s) cancelled`, `payment.completed received for CANCELLED booking …`,
  `Skipping already processed event …`, `flight-service call failed: …`, `Outbox: … could not be published …`.
- **Kubernetes:** `k8s/services/booking-service.yaml` – ConfigMap `booking-service-config` (`prod`-profil,
  `FLIGHT_SERVICE_URL`, `OIDC_ISSUER_URI`, `JWK_SET_URI` m.fl.), Secret `booking-service-secret` (DB og RabbitMQ),
  ClusterIP-Service og Deployment med 2 replicas, `requests` 250m/512Mi, `limits` 1000m/1Gi. startupProbe på liveness
  (5 s × 30), liveness efter 40 s og readiness efter 20 s. Ingress `/api/bookings` → `booking-service:8080`; Keycloak
  ligger bag `/auth` ([Keycloak (login)](../k8s/README.md#keycloak-login)).
- **Flere replicas:** outbox-relayet tager en Postgres advisory lock, så kun én pod sender events ad gangen;
  podsene deler køerne, og `processed_event` fjerner dubletter; sædereglen holdes af det unikke indeks i databasen.
  Kæden er stateless (ingen sessioner), så ethvert request kan ramme enhver pod.

## Designvalg

| Valg | Begrundelse | Mere |
|------|-------------|------|
| Synkront kald til flight-service ved `createBooking` | Altid korrekt pris og ledighed; ingen kopi af sædekortet | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Flysnapshot (`gate`, `flight_status`, `currency`) på `booking` | `flight.*`-events har noget at opdatere, og *Min booking* klarer sig med ét kald | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Passager-nøgle = e-mail (case-insensitivt unikt indeks) | Enkel identitet; samme e-mail står i Keycloak-tokenet | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Login på alle operationer, ejerskab via tokenets e-mail, `@PreAuthorize` pr. operation | Bookinger er persondata; samme endpoint bærer flere operationer, så reglen kan ikke ligge på URL'en | [Hvad er offentligt?](../docs/architecture.md#hvad-er-offentligt), [Ejerskab](../docs/architecture.md#ejerskab-egen-booking) |
| Partielt unikt indeks på aktivt sæde | Dobbeltbooking forhindres også ved samtidige requests og flere replicas | [Flow A](../docs/architecture.md#flow-a--booking-og-betaling) |
| Annullering også efter check-in | Spec'en forbyder det ikke | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Ubetalte bookinger forbliver `PENDING_PAYMENT` | Ikke krævet af spec'en; sædet er reserveret i `booking_db`, men vises ledigt i flight-service indtil betaling | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Transactional outbox | Tilstand og event committes atomisk; relayet leverer at-least-once | [Messaging](../docs/architecture.md#messaging) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – [Flow A](../docs/architecture.md#flow-a--booking-og-betaling) og
  [Flow C](../docs/architecture.md#flow-c--aflysning), messaging,
  [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller) og designvalg
- [docs/events.md](../docs/events.md) – eventkontrakterne, køer og dead-letter
- [README.md – Login og roller](../README.md#login-og-roller) – testbrugere og token til `curl`
- [k8s/README.md](../k8s/README.md) – deployment på minikube/kind og [Keycloak (login)](../k8s/README.md#keycloak-login)
- [scripts/e2e-smoke.sh](../scripts/e2e-smoke.sh) – smoke-test af Flow A–D mod compose-stakken
- [docs/service-readme-template.md](../docs/service-readme-template.md) – skabelonen for denne README
