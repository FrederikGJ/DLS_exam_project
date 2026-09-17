# baggage-service

Bagage bundet til en booking: registrering med bagagetag, statusskift gennem lufthavnen og events til resten af
systemet. Servicen er den eneste med **både** et GraphQL-API og et versioneret REST-API (v1), begge oven på den samme
forretningslogik. Den ejer ikke bookinger – den kender dem kun fra `booking.*`-events og kalder aldrig andre services
synkront.

| | |
|---|---|
| Ansvar | Bagage og bagagetags |
| Port | compose `8084` · Kubernetes via Ingress `/api/baggage` (+ `/v3/api-docs`, `/swagger-ui`) |
| API | GraphQL `/api/baggage/graphql` + REST `/api/baggage/v1` |
| Database | `baggage_db` (PostgreSQL 16, Flyway `V1`–`V6`) |
| Events ud | `baggage.registered`, `baggage.status.changed` |
| Events ind | `booking.created`, `booking.confirmed`, `booking.checkedin`, `booking.cancelled` (kø `baggage-service.booking-events`), `flight.cancelled` (kø `baggage-service.flight-events`) |
| Image | `airport/baggage-service:local` |

## Ansvar og data

| Tabel | Indhold | Migration |
|-------|---------|-----------|
| `baggage` | Kilde til sandhed for bagage: `tag_number` (UNIQUE), booking, passagernavn, flynummer, vægt, `type`, `status`, `last_location`, `idempotency_key` (UNIQUE, NULL tilladt), `version` (optimistisk låsning) | `V1`, `V3`, `V5` |
| `booking_snapshot` | Læsekopi af bookinger: reference, passagernavn, `flight_number`, `flight_id`, `status`, `event_occurred_at` (nyeste anvendte `occurredAt`, kun diagnostik) | `V1`, `V4` |
| `processed_event` | `eventId` for behandlede events (idempotente consumers) | `V1` |
| `outbox_event` | Transactional outbox, inkl. `traceparent` | `V2`, `V6` |

- `booking_snapshot` bygges fra events, så registrering virker, selv om booking-service er nede. Status går kun fremad
  i `BookingSnapshot.LIFECYCLE` (`PENDING_PAYMENT` < `CONFIRMED` < `CHECKED_IN` < `CANCELLED`); en aflyst booking
  slettes ikke, men bliver `CANCELLED` ([Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot)).
- Ikke her: bookinger, betaling og sæder (booking-, payment- og flight-service) og bagagen under *Min booking*
  (booking-services read-model `booking_overview`, fodret af servicens events). Ejerskab tjekkes ikke: rollen PASSENGER
  er nok til at registrere og liste bagage på enhver kendt bookingreference.

## API

Roller og fejlmodel er fælles – se [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller) og
[GraphQL-fejl](../docs/architecture.md#graphql-fejl). GraphQL-endpointet er åbent på URL-niveau og afgøres pr. operation
med `@PreAuthorize` (`graphql/BaggageController`); REST afgøres pr. URL i `config/SecurityConfig` med de samme roller.

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `baggage(tagNumber)` | query | alle | Én bagage eller `null` |
| `baggageByBooking(reference)` | query | PASSENGER / OPERATIONS | Bookingens bagage, sorteret efter oprettelse |
| `baggageByFlight(flightNumber)` | query | alle | Flyets bagage, sorteret efter oprettelse |
| `bookingSnapshot(reference)` | query | alle | Servicens kopi af bookingen (`null`, hvis intet event er modtaget) |
| `registerBaggage(bookingReference, weightKg, type, idempotencyKey)` | mutation | PASSENGER / OPERATIONS | Registrerer bagage → `baggage.registered` |
| `updateBaggageStatus(tagNumber, status, location)` | mutation | OPERATIONS | Ny status/lokation → `baggage.status.changed` |
| `POST /api/baggage/v1/baggage` | REST | PASSENGER / OPERATIONS | Som `registerBaggage` (nøglen i header `Idempotency-Key`); `201` + relativ `Location` |
| `GET /api/baggage/v1/baggage/{tagNumber}` | REST | alle | Som `baggage`; `200` / `404` |
| `GET /api/baggage/v1/bookings/{reference}/baggage` | REST | PASSENGER / OPERATIONS | Som `baggageByBooking`; `200` (evt. tom liste) |
| `PATCH /api/baggage/v1/baggage/{tagNumber}/status` | REST | OPERATIONS | Som `updateBaggageStatus`, body `{status, location}`; `200` |

**REST v1 og OpenAPI.** `baggageByFlight` og `bookingSnapshot` har ingen REST-modpart; frontendens bagage-side bruger
de fire endpoints og `bookingSnapshot` over GraphQL. Svaret har felterne fra GraphQL-typen `Baggage` plus `createdAt`.
Fejl er RFC 9457 problem details (`application/problem+json`) med et ekstra felt `code` i samme vokabular som
`errors[].extensions.code` (`rest/RestExceptionHandler`; 401/403 opstår i filterkæden og skrives af
`config/ProblemAuthHandlers`). springdoc genererer det offentlige dokument fra controller og DTO'er
(`config/OpenApiConfig`, kun `/api/baggage/v1/**`):

| | compose | kind (Ingress) |
|-|---------|----------------|
| OpenAPI (JSON / YAML) | `http://localhost:8084/v3/api-docs` / `…/v3/api-docs.yaml` | `http://localhost:8090/v3/api-docs` (`.yaml` rammer frontenden: Ingress-reglen er `Prefix` på hele segmenter) |
| Swagger UI | `http://localhost:8084/swagger-ui.html` | `http://localhost:8090/swagger-ui.html` (302 → `/swagger-ui/index.html`) |

Kopien [docs/openapi/baggage-v1.yaml](../docs/openapi/baggage-v1.yaml) er eksporteret fra den kørende service
(17-09-2026 indholdsmæssigt identisk med `/v3/api-docs` på kind). I Swagger UI tager **Authorize** et access token.

**Versionering.** Versionen står i stien. Et brud (felt fjernet, betydning eller statuskode ændret, ny påkrævet
parameter) bliver `/api/baggage/v2` **ved siden af** v1 over samme `BaggageService`; tilføjelser sker i v1. GraphQL
udvikles med `@deprecated` og events med suffiks – se [API-versionering](../docs/architecture.md#api-versionering).

**Idempotens.** `registerBaggage` tager en valgfri nøgle på højst 64 tegn (GraphQL-argument `idempotencyKey`,
REST-header `Idempotency-Key`). Samme nøgle igen giver den bagage, første kald registrerede – intet nyt INSERT og intet
nyt `baggage.registered`. REST svarer som første gang (`201`, samme `Location`) med `Idempotent-Replayed: true`
(første gang `false`; ved replay læses kroppen fra databasen, fx `weightKg` `23.00`); GraphQL har ingen markør.
Nøglen ligger i én kolonne, så en nøgle brugt over REST genkendes også over GraphQL. Samme nøgle med anden booking,
vægt eller type er `CONFLICT`. Se [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages).

**Fejlkoder.** GraphQL giver samme `code` med HTTP 200; et udløbet eller fremmed token afvises med 401 før GraphQL.

| `code` | REST | Hvornår |
|--------|------|---------|
| `VALIDATION_ERROR` | `400` / `422` | `400`: forkert form (Bean Validation på body, sti eller header, ulæselig JSON, ukendt enum). `422`: vægt ≤ 0 eller > 32 kg |
| `NOT_FOUND` | `404` | Ukendt tag; booking, servicen ikke har hørt om |
| `INVALID_STATE` | `409` | Bookingen er ikke `CONFIRMED`/`CHECKED_IN` |
| `BAGGAGE_LIMIT_EXCEEDED` | `422` | Fjerde `CHECKED`-stykke på bookingen |
| `CONFLICT` | `409` | Idempotency key brugt til andre data; unik-constraint brudt; bagagen ændret samtidig (`@Version`) |
| `UNAUTHORIZED` / `FORBIDDEN` | `401` / `403` | Intet eller ugyldigt token / rollen tillader det ikke |
| `INTERNAL_ERROR` | `500` | Uventet fejl |

**Eksempel** – kørt mod kind gennem Ingress 17-09-2026 (i compose har hver service sin port, baggage `8084`, og tokenet
hentes på `http://localhost:8180/realms/airport/...` uden `/auth`):

```bash
API=http://localhost:8090
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  $API/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)
gql() { curl -s "$API/api/$1/graphql" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg q "$2" '{query:$q}')"; }

# forudsætning: en betalt booking (Flow A); fly 4 er SCHEDULED, og 25E var ledig i availableSeats
REF=$(gql bookings 'mutation { createBooking(flightId: 4, seatNumber: "25E", passenger: {firstName: "Anna",
  lastName: "Jensen", email: "anna@example.com", passportNumber: "DK1234567"}) { bookingReference } }' \
  | jq -r .data.createBooking.bookingReference)                     # 2N9XNP
gql payments "mutation { pay(bookingReference: \"$REF\", amount: 3999.00, cardNumber: \"4242424242424242\",
  expiry: \"12/30\", cvv: \"123\") { status } }"                     # {"data":{"pay":{"status":"COMPLETED"}}}
sleep 2; gql baggage "{ bookingSnapshot(reference: \"$REF\") { status } }"   # ..."status":"CONFIRMED"

KEY=$(uuidgen)
for i in 1 2; do
  curl -si -X POST $API/api/baggage/v1/baggage -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
    -d "{\"bookingReference\":\"$REF\",\"weightKg\":23.0,\"type\":\"CHECKED\"}" | grep -E '^(HTTP|Location|Idempotent)'
done
# HTTP/1.1 201
# Location: /api/baggage/v1/baggage/BAG-J9E9DT62
# Idempotent-Replayed: false
# HTTP/1.1 201
# Location: /api/baggage/v1/baggage/BAG-J9E9DT62
# Idempotent-Replayed: true
# fra samme kørsel (svarkroppe forkortet):
# PATCH …/BAG-J9E9DT62/status {"status":"LOADED","location":"Belt 4"} som anna -> 403 {"code":"FORBIDDEN",...}
# samme PATCH som ops                            -> 200 {"status":"LOADED","lastLocation":"Belt 4",...}
# POST med samme Idempotency-Key, weightKg 20    -> 409 {"code":"CONFLICT","detail":"The idempotency key was already used for another registration (bag BAG-… on booking …)"}
# fjerde CHECKED-stykke                          -> 422 {"code":"BAGGAGE_LIMIT_EXCEEDED","detail":"A booking may have at most 3 CHECKED bags"}
# POST uden token                                -> 401 {"code":"UNAUTHORIZED",...} + WWW-Authenticate: Bearer
```

## Forretningsregler

- Bookingen skal være kendt fra et `booking.*`-event (ellers `NOT_FOUND`, 404) og have status `CONFIRMED` eller
  `CHECKED_IN` (`BaggageRules.assertEligible`, ellers `INVALID_STATE`, 409). `booking.created` gemmes som
  `PENDING_PAYMENT`, så en ubetalt booking giver `INVALID_STATE`, ikke `NOT_FOUND`.
- 0 < vægt ≤ 32 kg pr. stykke (`assertWeight`) → `VALIDATION_ERROR` (422). GraphQL-argumentet har desuden
  `@DecimalMin("0.1")`, REST-body'en kun `@Digits(integer = 3, fraction = 2)`: 0,05 kg afvises over GraphQL, ikke over REST.
- Højst 3 `CHECKED` pr. booking (`assertCheckedLimit`) → `BAGGAGE_LIMIT_EXCEEDED` (422); `CABIN` og `SPECIAL` tæller ikke.
- `bookingReference` er 6 alfanumeriske tegn (ellers `VALIDATION_ERROR`, 400), gemmes med store bogstaver; opslag er
  case-insensitive. Tagget er `BAG-` + 8 tegn fra `A-Z0-9` (`TagGenerator`, `SecureRandom`, op til 10 forsøg ved
  kollision). Ny bagage får status `REGISTERED` og `lastLocation` `CHECK_IN`.
- Statusskift har ingen overgangsregler: OPERATIONS kan sætte enhver `BaggageStatus`; uden `location` (≤ 100 tegn)
  beholdes den gamle. Samme status og samme eller ingen lokation igen er en no-op uden event.
- `flight.cancelled` sætter bagage, der ikke er `ARRIVED`/`LOST`, til `REGISTERED` @ `RETURN_DESK`. Bagage matches kun
  på flynummer (snapshots på `flight_id` eller flynummer), så to afgange med samme flynummer skelnes ikke.
- Idempotency key over 64 tegn → `VALIDATION_ERROR`; genbrug med andre data → `CONFLICT` (409). Ændrer to transaktioner
  samme bagage (fx `flight.cancelled` og en operatør), fejler den med den gamle kopi: operatøren får `CONFLICT`,
  listeneren prøver igen.

## Events

**Publicerer** (via transactional outbox: `EventPublisher` → `outbox_event` → `OutboxRelay`; lytter er booking-service,
kø `booking-service.baggage-events` → `booking_overview`):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `baggage.registered` | Ny bagage (ikke ved replay med idempotency key) | `tagNumber`, `bookingReference`, `passengerName`, `flightNumber`, `weightKg`, `type`, `status`, `lastLocation` (tilføjet additivt september 2026) |
| `baggage.status.changed` | Statusskift med ændring; `flight.cancelled` pr. berørt stykke | `tagNumber`, `bookingReference`, `flightNumber`, `oldStatus`, `newStatus`, `location` |

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `baggage-service.booking-events` | `booking.#` | `booking.created`, `booking.confirmed`, `booking.checkedin`, `booking.cancelled` | Opretter/fletter `booking_snapshot` under rækkelås (`lockByBookingReference`); status fra payloadens `status` eller eventtypen, kun fremad. Andre `booking.*` (fx `booking.payment.rejected`) registreres som behandlet og ignoreres |
| `baggage-service.flight-events` | `flight.#` | `flight.cancelled` | Bagage → `REGISTERED` @ `RETURN_DESK`; snapshots på flyet (rækkelås `lockByFlight`) → `CANCELLED`. Andre `flight.*` ignoreres |

Outbox med publisher confirms (at-least-once, rækkefølge pr. producent), `processed_event` pr. `eventId`, 3 forsøg
(500 ms, ×2, max 3 s) → `airport.events.dlx` → `baggage-service.dlq`, og kommutative handlers – se
[Leveringsgarantier](../docs/events.md#leveringsgarantier), [Rækkefølge og kommutativitet](../docs/events.md#rækkefølge-og-kommutativitet) og [asyncapi.yaml](../docs/asyncapi.yaml).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5436/baggage_db` / `baggage` / `baggage` | Servicens database (compose udstiller `baggage-db` på 5436) |
| `RABBITMQ_*`, `OIDC_ISSUER_URI`, `JWK_SET_URI`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE` | se `application.yml` | Fælles – se [rod-README'ens tabel](../README.md#konfiguration). CORS tillader `GET`, `POST`, `PATCH`, `OPTIONS` og eksponerer `Location` og `Idempotent-Replayed` |
| `GRAPHQL_PATH` | `/api/baggage/graphql` | GraphQL-stien (også den sti, `SecurityConfig` åbner) |
| `SERVER_PORT` | `8080` | HTTP-port |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender ventende events |

Uden egen variabel: resten af `app.outbox.*` (batch 100, confirm-timeout 5 s, retention 7d) og kønavnene i `app.messaging`.
Profilen `prod` (Kubernetes) slår GraphiQL fra og logger JSON (`logstash`).

## Kør lokalt

```bash
# hele stakken (profil dev): GraphQL http://localhost:8084/api/baggage/graphql, GraphiQL /graphiql, Swagger /swagger-ui.html
docker compose up --build

# kun servicen på værten; Keycloak skal kun køre, når der sendes tokens
docker compose up -d baggage-db rabbitmq keycloak
cd baggage-service && SERVER_PORT=8084 mvn spring-boot:run     # defaults: localhost:5436, :5672, :8180
```

Kubernetes: `kubectl apply -k k8s/` (image og `kind load`: [k8s/README.md](../k8s/README.md#alternativ-kind)); GraphiQL er fra.

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `service/BaggageRulesTest` | unit, 7 `@Test` + 2 `@ParameterizedTest` (12 kørsler) | `CONFIRMED`/`CHECKED_IN` tilladt, øvrige og `null` → `INVALID_STATE`; 32 kg tilladt, 32,01/0/negativ/`null` afvist; 3. `CHECKED` ok, 4. → `BAGGAGE_LIMIT_EXCEEDED`; `CABIN`/`SPECIAL` ubegrænset |
| `service/TagGeneratorTest` | unit, 1 `@Test` + `@RepeatedTest(20)` (21 kørsler) | Format `^BAG-[A-Z0-9]{8}$`; 1000 tags er unikke |
| `domain/BookingSnapshotTest` | unit, 3 | Sen `CONFIRMED` genåbner ikke `CANCELLED`; status kun fremad, samme status igen er no-op; `cancel` gælder altid; `eventOccurredAt` er den nyeste |
| `BaggageServiceIntegrationTest` | Testcontainers, GraphQL over HTTP, 12 | Ukendt booking `NOT_FOUND`, ubetalt `INVALID_STATE`; dubleret `booking.confirmed` behandles én gang; `baggage.registered` med envelope og `lastLocation`; 32 kg- og 3-stk.-regel; `baggage.status.changed`; `flight.cancelled` → `RETURN_DESK`; queries; idempotency key (replay, `CONFLICT`, ét event) og no-op-statusskift; outbox: `publish` uden transaktion afvises, rollback efterlader intet, relayet prøver igen efter brokerfejl |
| `BaggageRestIntegrationTest` | Testcontainers, MockMvc gennem filterkæden, 10 | `201` + `Location` + `baggage.registered`; offentligt opslag, liste kræver login; `404`/`422`/`409`/`400` som problem+json med `code`; `401` med `WWW-Authenticate`; `403` for PASSENGER på `PATCH`; OpenAPI-dokumentet er offentligt med de fire endpoints; `Idempotency-Key` (replay-header, `CONFLICT`, uden nøgle = ny bagage); 8 samtidige kald med samme nøgle → én bagage |
| `SecurityIntegrationTest` | Testcontainers, HTTP, 6 | Offentlig query og readiness uden token; beskyttede operationer uden token → `UNAUTHORIZED`; PASSENGER registrerer, kun OPERATIONS skifter status; udløbet, fremmed og ugyldigt token → HTTP 401 `invalid_token`; `/actuator/prometheus` åben (med `outbox_pending`), `/actuator/metrics` → 401; CORS-preflight fra `http://localhost:8080` |
| `EventOrderIntegrationTest` | Testcontainers, handlere kaldt direkte, 4 | Alle rækkefølger giver samme snapshot: 24 (fuld livscyklus → `CANCELLED`), 6 (→ `CHECKED_IN`), 120 (med `flight.cancelled` → `CANCELLED`); `@Version` får transaktionen med den gamle kopi til at fejle |

```bash
cd baggage-service && mvn -Pci verify
```

Kræver Docker: Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`; tokens udstedes af
`TestTokens` (ingen Keycloak). 46 testmetoder giver 68 testkørsler; profilen `ci` kører desuden Checkstyle (`validate`)
og SpotBugs + find-sec-bugs (`verify`) med reglerne i `../config`. CI kører samme kommando (`.github/workflows/ci.yml`).

## Drift

- **Health:** `/actuator/health/liveness` (startup- og livenessProbe), `/actuator/health/readiness` (gruppen
  `readinessState`, `db`, `rabbit`). Health, info og prometheus er åbne, resten af actuator kræver OPERATIONS, og
  Ingress'en router ikke `/actuator`.
- **Metrics** (`/actuator/prometheus`, tag `application="baggage-service"`, pod-annotationer `prometheus.io/*`):
  `outbox_pending`, `events_published_total{type}`, `events_consumed_total{type,outcome}` og
  `http_server_requests_seconds` (histogram) – se [Observability](../docs/architecture.md#observability-metrics-logs-og-alarmer).
- **Alarmer** (`k8s/components/observability/config/alerts.yml`): `OutboxBacklog`, `MessagesDeadLettered`
  (`baggage-service.dlq`), `EventHandlingFailing`, `ServiceDown`, `HighServerErrorRate` (ser REST's 5xx, ikke GraphQL-fejl).
- **Loglinjer:** `Registered baggage … on booking …`, `Idempotent replay: baggage …`, `Baggage … status … -> … at …`,
  `Booking snapshot … -> …`, `Ignoring late … for booking snapshot …` (INFO, ingen fejl), `Flight … cancelled: …
  bag(s) sent to RETURN_DESK`, `Outbox: … event(s) could not be published` (WARN); `eventId`/`eventType` i MDC.
- **Kubernetes** (`k8s/base/services/baggage-service.yaml`): ConfigMap + Secret (dev-værdier), 1 replica, ingen HPA,
  initContainer `wait-for-db`, requests `150m`/`256Mi`, limits `500m`/`640Mi`, non-root med read-only rodfilsystem
  (`/tmp` som `emptyDir`); imaget har et CDS-arkiv. Flere replicas går: kun én `OutboxRelay` sender ad gangen (advisory lock).

## Designvalg

| Valg | Begrundelse | Uddybet |
|------|-------------|---------|
| GraphQL og REST v1 over samme `BaggageService` | Begge API-stilarter vises; regler, events og roller findes ét sted, kun controller og DTO'er er dobbelte | [REST: version i stien](../docs/architecture.md#rest-version-i-stien) |
| Version i stien, ikke i en header | Synlig i browser, logs, Ingress-regler og curl; `v2` kan køre ved siden af `v1` | [API-versionering](../docs/architecture.md#api-versionering) |
| Relativ `Location` | Bag Ingress'en ser podden ikke klientens port (`X-Forwarded-Port` er 80, ikke 8090) | `rest/BaggageRestController` |
| Idempotency key fra klienten | To ens kufferter er legitime – kun klienten kan skelne dem fra et dobbeltklik. `register` er bevidst ikke én transaktion, så taberen af et kapløb om `UNIQUE`-nøglen slår op igen og finder vinderens bagage | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |
| `booking_snapshot` fra events, intet synkront kald | Registrering virker, når booking-service er nede; ubetalt/aflyst booking giver `INVALID_STATE` frem for `NOT_FOUND` | [Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot) |
| Snapshot-status ordnet efter livscyklus, ikke tid | Kræver intet ur, så events fra booking- og flight-service kan blandes frit | [Kommutative handlers](../docs/architecture.md#kommutative-handlers) |
| Rækkelås på snapshot, `@Version` på `baggage` | Kommutativ er ikke trådsikker: to køer og brugernes mutationer skriver i de samme rækker | [events.md](../docs/events.md#rækkefølge-og-kommutativitet) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – [Flow B](../docs/architecture.md#flow-b--bagage),
  [Flow C](../docs/architecture.md#flow-c--aflysning), sikkerhed, designmønstre
- [docs/events.md](../docs/events.md#events-fra-baggage-service) og [docs/asyncapi.yaml](../docs/asyncapi.yaml) – eventkontrakter
- [docs/openapi/baggage-v1.yaml](../docs/openapi/baggage-v1.yaml) – REST-kontrakten
- [README.md](../README.md#apier-graphql-og-rest) – REST-fejltabel og curl mod compose
- [k8s/README.md](../k8s/README.md) – deploy på kind, Ingress-adresser, CDS-måling
- `scripts/e2e-smoke.sh` – Flow B og aflysning end-to-end over GraphQL
