# baggage-service

Bagage bundet til en booking: registrering med bagagetag, statusskift gennem lufthavnen og events til resten af
systemet. Servicen ejer ikke bookinger – den kender dem kun fra `booking.*`-events og kalder aldrig andre services
synkront. Alle operationer kræver et Keycloak-token med rollen PASSENGER eller OPERATIONS; statusskift og listen over
et flys bagage er forbeholdt OPERATIONS.

| | |
|---|---|
| Ansvar | Bagage og bagagetags |
| Port | compose `8084` · Kubernetes via Ingress `/api/baggage` |
| API | GraphQL `/api/baggage/graphql` · REST kun actuator (`/actuator/health/*`, `/actuator/info`; `/actuator/metrics` kræver OPERATIONS) |
| Database | `baggage_db` (PostgreSQL 16, Flyway `V1`–`V3`) |
| Events ud | `baggage.registered`, `baggage.status.changed` |
| Events ind | `booking.created`, `booking.confirmed`, `booking.checkedin`, `booking.cancelled` (kø `baggage-service.booking-events`), `flight.cancelled` (kø `baggage-service.flight-events`) |
| Image | `airport/baggage-service:local` |

## Ansvar og data

| Tabel | Indhold | Migration |
|-------|---------|-----------|
| `baggage` | Kilde til sandhed for bagage: `tag_number` (UNIQUE), `booking_reference`, `passenger_name`, `flight_number`, `weight_kg` (`CHECK > 0`), `type`, `status` (CHECK på enum-værdierne), `last_location`, `created_at`, `updated_at`; `idempotency_key` (UNIQUE, `NULL` uden nøgle) | `V1`, `V3` |
| `booking_snapshot` | Læsekopi af bookinger: `booking_reference` (PK), `passenger_name`, `flight_number`, `flight_id`, `status`, `updated_at` | `V1` |
| `processed_event` | `event_id` for behandlede events (idempotente consumers) | `V1` |
| `outbox_event` | Transactional outbox: hele envelopen som `JSONB`, `published_at`, `attempts`, `last_error`; partielt indeks på usendte rækker | `V2` |

- `booking_snapshot` bygges fra `booking.*`- og `flight.cancelled`-events, så registrering virker, selv om
  booking-service er nede. Status overskrives af det senest behandlede event; tomme felter i et event overskriver
  ikke de gemte. En aflyst booking slettes ikke, men bliver `CANCELLED`.
- Ved registrering kopieres passagernavn og flynummer fra snapshottet ind i `baggage`; senere ændringer i snapshottet
  rører ikke eksisterende bagage.
- Ikke her: bookinger, betaling og sæder (booking-, payment- og flight-service). Frontendens *Min booking* henter
  bagagen direkte her med `baggageByBooking`.
- Ejerskab tjekkes ikke: servicen kender ikke passagerens e-mail, så rollen PASSENGER er nok til at registrere og slå
  bagage op på enhver kendt bookingreference ([Ejerskab](../docs/architecture.md#ejerskab-egen-booking)).

## API

Roller og fejlmodel er fælles – se [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller),
[Operation × rolle](../docs/architecture.md#operation--rolle) og [GraphQL-fejl](../docs/architecture.md#graphql-fejl).
GraphQL-endpointet er åbent på URL-niveau, og adgangen afgøres pr. operation med `@PreAuthorize` i
`graphql/BaggageController` (`@Validated`, Bean Validation på argumenterne); skemaet er
`src/main/resources/graphql/schema.graphqls`.

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `baggage(tagNumber)` | query | PASSENGER / OPERATIONS | Én bagage eller `null` |
| `baggageByBooking(reference)` | query | PASSENGER / OPERATIONS | Bookingens bagage, sorteret efter oprettelse (tom liste, hvis ingen) |
| `baggageByFlight(flightNumber)` | query | OPERATIONS | Flyets bagage, sorteret efter oprettelse |
| `bookingSnapshot(reference)` | query | PASSENGER / OPERATIONS | Servicens kopi af bookingen (`null`, hvis intet event er modtaget); `status` er en streng |
| `registerBaggage(bookingReference, weightKg, type, idempotencyKey)` | mutation | PASSENGER / OPERATIONS | Registrerer bagage → `baggage.registered`; samme `idempotencyKey` igen giver den samme bagage og intet nyt event |
| `updateBaggageStatus(tagNumber, status, location)` | mutation | OPERATIONS | Ny status/lokation → `baggage.status.changed`; samme status og samme (eller ingen) lokation igen er en no-op uden event |

Opslag på tag, reference og flynummer skelner ikke mellem store og små bogstaver. `Baggage` har `id`, `tagNumber`,
`bookingReference`, `passengerName`, `flightNumber`, `weightKg` (`BigDecimal`), `type` (`CHECKED`/`CABIN`/`SPECIAL`),
`status` (`REGISTERED`, `SECURITY`, `LOADED`, `IN_TRANSIT`, `ARRIVED`, `LOST`), `lastLocation` og `updatedAt`.
Frontendens bagage-side kræver login og bruger `registerBaggage` (med en ny `idempotencyKey`, UUID, pr. formular),
`baggageByBooking`, `updateBaggageStatus` (lykkes kun som `ops`) og `baggage`; `bookingSnapshot` bruges af
`scripts/e2e-smoke.sh` og testene, `baggageByFlight` af testene.

**Fejlkoder** (`errors[].extensions.code`, `graphql/GraphQlExceptionResolver`). GraphQL svarer HTTP 200 med koden i
`errors[]`; et udløbet, fremmed eller ugyldigt token afvises derimod med HTTP 401 og
`WWW-Authenticate: Bearer error="invalid_token"` i filterkæden, før GraphQL.

| `code` | Hvornår |
|--------|---------|
| `UNAUTHORIZED` | Intet token – alle operationer kræver login |
| `FORBIDDEN` | Rollen tillader ikke operationen: PASSENGER på `updateBaggageStatus` eller `baggageByFlight` |
| `VALIDATION_ERROR` | Bean Validation: tom streng, `bookingReference` ikke 6 alfanumeriske tegn, `weightKg` under 0,1 / over 32,0 / mere end 3 heltals- eller 2 decimalcifre, `location` over 100 tegn, `idempotencyKey` over 64 tegn. Beskeden har feltnavnet, fx `weightKg: …` |
| `NOT_FOUND` | Booking, servicen ikke har hørt om (`registerBaggage`); ukendt tag i `updateBaggageStatus`. Queries svarer `null`/tom liste i stedet |
| `INVALID_STATE` | Bookingen er ikke `CONFIRMED`/`CHECKED_IN` |
| `BAGGAGE_LIMIT_EXCEEDED` | Fjerde `CHECKED`-stykke på bookingen |
| `CONFLICT` | Samme `idempotencyKey` brugt med en anden booking, vægt eller type (`registerBaggage`); ellers unik-constraint brudt (`DataIntegrityViolationException`) |
| `INTERNAL_ERROR` | Uventet fejl, fx intet ledigt tag efter 10 forsøg |

Ukendte enum-værdier og manglende argumenter afvises af graphql-java, før controlleren kaldes, og har ingen `code`.

**Eksempel** mod compose-stakken (`docker compose up --build`; kræver `curl` og `jq`). Tokens hentes hos Keycloak på
`localhost:8180` med password grant ([Login og roller](../README.md#login-og-roller)) og lever 5 minutter:

```bash
FLIGHTS=http://localhost:8081/api/flights/graphql BOOKINGS=http://localhost:8082/api/bookings/graphql
PAYMENTS=http://localhost:8083/api/payments/graphql BAGGAGE=http://localhost:8084/api/baggage/graphql
token() { curl -s -d client_id=airport-frontend -d grant_type=password -d username=$1 -d password=$1 \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token; }   # kode = brugernavn
ANNA=$(token anna); OPS=$(token ops)                                     # PASSENGER / OPERATIONS
gql() { curl -s "$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg q "$3" '{query:$q}')"; }

# uden token: GraphQL-fejl, HTTP 200
curl -s $BAGGAGE -H 'Content-Type: application/json' \
  -d '{"query":"{ bookingSnapshot(reference: \"ZZZZZZ\") { status } }"}' | jq -r '.errors[0].extensions.code'
# UNAUTHORIZED

# 1) virker på en frisk stak: en booking, servicen ikke har fået events om
gql $ANNA $BAGGAGE '{ bookingSnapshot(reference: "ZZZZZZ") { status } }'  # {"data":{"bookingSnapshot":null}}
gql $ANNA $BAGGAGE 'mutation { registerBaggage(bookingReference: "ZZZZZZ", weightKg: 23, type: CHECKED) {
  tagNumber } }' | jq -c '.errors[0] | {code: .extensions.code, message}'
# {"code":"NOT_FOUND","message":"Booking ZZZZZZ is not known to baggage-service (no confirmed booking event received)"}

# 2) Flow B: kræver en betalt booking (Flow A); som PASSENGER må anna kun booke med sin egen e-mail
FID=$(gql $ANNA $FLIGHTS '{ flights(filter: {status: SCHEDULED}) { id } }' | jq -r '.data.flights[0].id')
SEAT=$(gql $ANNA $FLIGHTS "{ availableSeats(flightId: $FID) { seatNumber } }" \
  | jq -r '.data.availableSeats[-1].seatNumber')
B=$(gql $ANNA $BOOKINGS "mutation { createBooking(flightId: $FID, seatNumber: \"$SEAT\", passenger: {firstName: \"Anna\",
  lastName: \"Andersen\", email: \"anna@example.com\", passportNumber: \"DK1234567\"}) { bookingReference price } }")
REF=$(jq -r .data.createBooking.bookingReference <<<"$B"); PRICE=$(jq -r .data.createBooking.price <<<"$B")
sleep 2; gql $ANNA $BAGGAGE "mutation { registerBaggage(bookingReference: \"$REF\", weightKg: 23, type: CHECKED) {
  tagNumber } }" | jq -r '.errors[0].extensions.code'            # INVALID_STATE (snapshot er PENDING_PAYMENT)
gql $ANNA $PAYMENTS "mutation { pay(bookingReference: \"$REF\", amount: $PRICE, cardNumber: \"4242424242424242\",
  expiry: \"12/30\", cvv: \"123\") { status } }"                   # {"data":{"pay":{"status":"COMPLETED"}}}
sleep 3; gql $ANNA $BAGGAGE "{ bookingSnapshot(reference: \"$REF\") { status } }"
# {"data":{"bookingSnapshot":{"status":"CONFIRMED"}}}
R=$(gql $ANNA $BAGGAGE "mutation { registerBaggage(bookingReference: \"$REF\", weightKg: 23, type: CHECKED) {
  tagNumber status lastLocation passengerName } }"); echo "$R"; TAG=$(jq -r .data.registerBaggage.tagNumber <<<"$R")
# {"data":{"registerBaggage":{"tagNumber":"BAG-…","status":"REGISTERED","lastLocation":"CHECK_IN",
#   "passengerName":"Anna Andersen"}}}
gql $ANNA $BAGGAGE "mutation { updateBaggageStatus(tagNumber: \"$TAG\", status: LOADED, location: \"Belt 4\") {
  status } }" | jq -c '.errors[0] | {code: .extensions.code, message}'
# {"code":"FORBIDDEN","message":"Your role does not allow this operation"}
gql $OPS $BAGGAGE "mutation { updateBaggageStatus(tagNumber: \"$TAG\", status: LOADED, location: \"Belt 4\") {
  status lastLocation } }"
# {"data":{"updateBaggageStatus":{"status":"LOADED","lastLocation":"Belt 4"}}}
gql $ANNA $BAGGAGE "mutation { registerBaggage(bookingReference: \"$REF\", weightKg: 33, type: CHECKED) {
  tagNumber } }" | jq -c '.errors[0] | {code: .extensions.code, message}'
# {"code":"VALIDATION_ERROR","message":"weightKg: must be less than or equal to 32.0"}
```

Eksempel 2 booker et sæde på den første `SCHEDULED`-afgang; `docker compose down -v` nulstiller seed-data. På kind
hentes tokenet på `http://localhost:8090/auth/realms/airport/protocol/openid-connect/token`, og API'et ligger på
`http://localhost:8090/api/baggage/graphql`.

## Forretningsregler

- Bookingen skal være kendt fra et `booking.*`-event (ellers `NOT_FOUND`) og have status `CONFIRMED` eller
  `CHECKED_IN` (`BaggageRules.assertEligible`, ellers `INVALID_STATE`). `booking.created` gemmes som
  `PENDING_PAYMENT`, så en ubetalt booking giver `INVALID_STATE`, ikke `NOT_FOUND`.
- 0 < vægt ≤ 32 kg pr. stykke → `VALIDATION_ERROR`. Reglen står to steder: Bean Validation på GraphQL-argumentet
  (`@DecimalMin("0.1")`, `@DecimalMax("32.0")`, `@Digits(integer = 3, fraction = 2)`), som rammer først, og
  `BaggageRules.assertWeight` i `BaggageService`.
- Højst 3 `CHECKED` pr. booking (`assertCheckedLimit`) → `BAGGAGE_LIMIT_EXCEEDED`; `CABIN` og `SPECIAL` tæller ikke.
  Alle `CHECKED`-stykker på bookingen tæller, uanset status. Optælling og indsættelse sker i samme transaktion
  uden lås.
- `bookingReference` er 6 alfanumeriske tegn (ellers `VALIDATION_ERROR`) og gemmes med store bogstaver. Tagget er
  `BAG-` + 8 tegn fra `A-Z0-9` (`TagGenerator`, `SecureRandom`); det tjekkes mod databasen, op til 10 forsøg, derefter
  `INTERNAL_ERROR`. Ny bagage får status `REGISTERED` og `lastLocation` `CHECK_IN`.
- `idempotencyKey` er valgfri (højst 64 tegn, ellers `VALIDATION_ERROR`). Et gentaget `registerBaggage` med samme nøgle
  returnerer den bagage, første kald registrerede – intet nyt INSERT og intet nyt `baggage.registered`; samme nøgle med
  en anden booking, vægt eller type → `CONFLICT`. Nøglen slås op før de øvrige regler; to samtidige kald med samme
  nøgle afgøres af UNIQUE-constrainten (`V3`), og taberen får vinderens bagage
  ([Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages)).
- Statusskift har ingen overgangsregler: enhver `BaggageStatus` kan sættes. Uden `location` (eller med en tom)
  beholdes den gamle; en ny trimmes. Samme status med samme (eller ingen) lokation er en no-op: intet ændres, og der
  publiceres intet `baggage.status.changed`. Alle andre kald publicerer ét.
- `flight.cancelled` sætter bagage, der ikke er `ARRIVED`/`LOST`, til `REGISTERED` @ `RETURN_DESK`. Bagage matches kun
  på flynummer (snapshots på `flight_id` eller flynummer), så to afgange med samme flynummer skelnes ikke.

## Events

**Publicerer** (via transactional outbox: `EventPublisher` → `outbox_event` → `OutboxRelay`, producer
`baggage-service`). Ingen anden service binder til `baggage.#` i dag:

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `baggage.registered` | Ny bagage (ikke ved en gentagelse med samme `idempotencyKey`) | `tagNumber`, `bookingReference`, `passengerName`, `flightNumber`, `weightKg`, `type`, `status` |
| `baggage.status.changed` | `updateBaggageStatus`, der ændrer status eller lokation; `flight.cancelled` pr. berørt stykke | `tagNumber`, `bookingReference`, `flightNumber`, `oldStatus`, `newStatus`, `location` (den angivne, ellers bagagens `lastLocation`) |

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `baggage-service.booking-events` | `booking.#` | `booking.created`, `booking.confirmed`, `booking.checkedin`, `booking.cancelled` | Opretter/opdaterer `booking_snapshot`: status fra payloadens `status`, ellers afledt af eventtypen; passagernavn = `passenger.firstName` + `lastName` (ellers `Unknown passenger`). Andre `booking.*` registreres som behandlet og ignoreres |
| `baggage-service.flight-events` | `flight.#` | `flight.cancelled` | Bagage → `REGISTERED` @ `RETURN_DESK` (ét `baggage.status.changed` pr. stykke); snapshots på flyet → `CANCELLED`. `flight.created`, `flight.status.changed` og `flight.gate.changed` registreres som behandlet og ignoreres |

Outbox med publisher confirms (at-least-once, rækkefølge pr. producent), `processed_event` pr. `eventId` i samme
transaktion som ændringen, 3 forsøg (500 ms, ×2, max 3 s) → `airport.events.dlx` (routing key `baggage-service`)
→ `baggage-service.dlq` – se [Leveringsgarantier](../docs/events.md#leveringsgarantier) og
[Køer, bindings og dead-letter](../docs/events.md#køer-bindings-og-dead-letter).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5436/baggage_db` / `baggage` / `baggage` | Servicens database (compose udstiller `baggage-db` på 5436) |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `localhost` / `5672` / `airport` / `airport` | Broker. Fælles – se [rod-README'ens tabel](../README.md#konfiguration) |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/airport` | Den `iss`, tokenet skal have (Keycloaks browser-vendte adresse); compose samme værdi, kind `http://localhost:8090/auth/realms/airport` |
| `JWK_SET_URI` | `http://localhost:8180/realms/airport/protocol/openid-connect/certs` | Hvor servicen henter Keycloaks signeringsnøgler; compose `http://keycloak:8080/realms/…`, kind `http://keycloak:8080/auth/realms/…` – se [Issuer og JWKS](../docs/architecture.md#konfiguration-issuer-og-jwks) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Kommasepareret (`app.cors.allowed-origins`); `config/SecurityConfig` bruger den for alle stier med `GET`, `POST`, `OPTIONS`, og preflight kræver intet token. Compose: `http://localhost:8080,http://127.0.0.1:8080` |
| `SPRING_PROFILES_ACTIVE` | – (ingen profil) | `dev` i compose, `prod` i Kubernetes |
| `GRAPHQL_PATH` | `/api/baggage/graphql` | GraphQL-stien (samme lokalt og bag Ingress) |
| `SERVER_PORT` | `8080` | HTTP-port |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender ventende events |

Uden egen variabel: resten af `app.outbox.*` (batch 100, confirm-timeout 5 s, retention 7d, oprydning hver time),
kønavnene i `app.messaging` og listener-retry. Profilen `prod` (Kubernetes) slår GraphiQL fra og logger JSON
(`logstash`); compose sætter `dev`, som kører på defaults.

## Kør lokalt

```bash
# hele stakken: GraphQL http://localhost:8084/api/baggage/graphql,
# GraphiQL http://localhost:8084/graphiql?path=/api/baggage/graphql
docker compose up --build

# kun servicen på værten (Java 21 + Maven); database, broker og Keycloak (tokens) fra compose
docker compose up -d baggage-db rabbitmq keycloak
cd baggage-service && SERVER_PORT=8084 mvn spring-boot:run     # defaults: localhost:5436, :5672 og Keycloak på :8180
```

Kører servicen alene, kommer der ingen snapshots: start også booking-service (og flight-/payment-service for Flow A),
eller publicér et `booking.confirmed` med envelope som i [events.md](../docs/events.md#envelope) via RabbitMQ's
management-API, som i [rod-README'ens DLQ-test](../README.md#test-af-retry--dead-letter-queue).

Kubernetes: `kubectl apply -k k8s/` (images og `kind load`: [k8s/README.md](../k8s/README.md#alternativ-kind));
GraphiQL er fra, og på kind nås API'et på `http://localhost:8090/api/baggage/graphql`.

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `service/BaggageRulesTest` | unit, 7 `@Test` + 2 `@ParameterizedTest` (12 kørsler) | `CONFIRMED`/`CHECKED_IN` tilladt; `PENDING_PAYMENT`, `CANCELLED` og ukendt → `INVALID_STATE`, `null` afvist; 32 kg tilladt, 32,01 → `VALIDATION_ERROR`, 0/negativ/`null` afvist; 3. `CHECKED` ok, 4. → `BAGGAGE_LIMIT_EXCEEDED`; `CABIN`/`SPECIAL` ubegrænset |
| `service/TagGeneratorTest` | unit, 1 `@Test` + `@RepeatedTest(20)` (21 kørsler) | Format `^BAG-[A-Z0-9]{8}$`; 1000 tags er unikke |
| `BaggageServiceIntegrationTest` | Testcontainers, `HttpGraphQlTester` over HTTP (tilfældig port, gennem filterkæden) med tokens fra `TestTokens`, 12 | 9 trin i fast rækkefølge: ukendt booking `NOT_FOUND`; `booking.created` → snapshot `PENDING_PAYMENT` → `INVALID_STATE`; dubleret `booking.confirmed` (samme `eventId`) behandles én gang; `baggage.registered` med envelope; 32 kg-regel; 3-stk.-regel og `CABIN`; `baggage.status.changed` og ukendt tag `NOT_FOUND`; `flight.cancelled` → `RETURN_DESK` og snapshot `CANCELLED`; queries; idempotency key (`registrationWithTheSameIdempotencyKeyIsReplayedNotRepeated`: samme nøgle → samme tag og én bagage, anden vægt → `CONFLICT`, gentaget statusopdatering → ét `baggage.status.changed`, ét `baggage.registered`). Outbox: `publish` uden transaktion afvises, rollback efterlader hverken række eller event, relayet prøver igen efter brokerfejl (`outbox.pending` = 0 bagefter) |
| `SecurityIntegrationTest` | Testcontainers, `HttpGraphQlTester` og `TestRestTemplate` med tokens fra `TestTokens`, 6 | `/actuator/health/readiness` uden token; alle seks operationer uden token → `UNAUTHORIZED`; PASSENGER på `baggageByFlight` → `FORBIDDEN`, OPERATIONS må; PASSENGER registrerer og læser (`baggageByBooking`, `baggage`), men får `FORBIDDEN` på `updateBaggageStatus`, som OPERATIONS må; udløbet, fremmed (anden issuer) og ugyldigt token → HTTP 401 med `invalid_token`; CORS-preflight fra `http://localhost:8080` tilladt |

```bash
cd baggage-service && mvn -B -ntp -Pci verify   # som CI: tests + Checkstyle + SpotBugs
```

Kræver Docker: Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`. Keycloak kræves
ikke: `TestTokens` (hjælpeklasse, ingen tests) udsteder JWT'er med samme claims som Keycloak (`anna`/PASSENGER,
`ops`/OPERATIONS) og en `JwtDecoder`, der stoler på testnøglen. Der er 51 testkørsler i alt (12 + 21 + 12 + 6); om
idempotens se [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages). CI kører samme
kommando i jobbet `backend · baggage-service` (matrix i [.github/workflows/ci.yml](../.github/workflows/ci.yml)) og
uploader Surefire-, Checkstyle- og SpotBugs-rapporterne som artefakten `reports-baggage-service`, også ved fejl – se
[CI](../README.md#ci-github-actions).

## Drift

- **Health:** `/actuator/health/liveness` (startup- og livenessProbe), `/actuator/health/readiness` (gruppen
  `readinessState`, `db`, `rabbit`; compose-healthcheck og readinessProbe). Actuator udstiller `health`, `info` og
  `metrics`; `health` og `info` er åbne, resten kræver et OPERATIONS-token (`config/SecurityConfig`). Ingress'en
  router ikke `/actuator`.
- **Outbox-backlog:** gauge `outbox.pending` på `/actuator/metrics/outbox.pending` (med OPERATIONS-token);
  ubekræftede events står i `outbox_event` med `published_at IS NULL`, `attempts` og `last_error`.
- **Loglinjer:** `Registered baggage … on booking …`, `Baggage … status … -> … at …`, `Booking snapshot … -> …`,
  `Flight … cancelled: … bag(s) sent to RETURN_DESK`, `Skipping already processed event …`,
  `Idempotent replay: baggage … was already registered with this key`, `Baggage … is already … at … - nothing to do`,
  `Outbox: … event(s) could not be published (attempt …), will retry` (WARN, første gang og hvert 20. forsøg);
  `eventId`/`eventType` i MDC, mens et event behandles.
- **Kubernetes** (`k8s/services/baggage-service.yaml`): ConfigMap `baggage-service-config` (profil `prod`, DB-URL,
  RabbitMQ, CORS, `GRAPHQL_PATH`, `OIDC_ISSUER_URI`, `JWK_SET_URI`) + Secret `baggage-service-secret` (dev-værdier),
  ClusterIP-Service på 8080, 2 replicas, startupProbe (5 s × 30), livenessProbe (efter 40 s, hvert 10 s),
  readinessProbe (efter 20 s, hvert 10 s), requests `250m`/`512Mi`, limits `1000m`/`1Gi`. Databasen er
  `k8s/databases/baggage-db.yaml` (StatefulSet, 1 replica).
- **Flere replicas:** kun én `OutboxRelay` sender ad gangen (Postgres advisory lock), så rækkefølgen ud bevares. Ind
  er podsene konkurrerende consumers på samme køer: `processed_event` fanger dubletter, men to events om samme booking
  kan behandles på hver sin pod i vilkårlig rækkefølge, og snapshottet får status fra det sidst behandlede.

## Designvalg

| Valg | Begrundelse | Uddybet |
|------|-------------|---------|
| `booking_snapshot` fra events, intet synkront kald | Registrering virker, når booking-service er nede; ingen service læser i en andens database | [Overblik](../docs/architecture.md#overblik) |
| `booking.created` gemmes også, og snapshot har `flight_id` | Ubetalt booking giver `INVALID_STATE` frem for `NOT_FOUND`; `flight.cancelled` kan matche på id og nummer | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) (*Bagage-snapshot*) |
| `last_location` = `CHECK_IN` ved registrering, `RETURN_DESK` ved aflysning | Spec'en kræver kun `RETURN_DESK`; `CHECK_IN` giver en meningsfuld startværdi; `ARRIVED`/`LOST` springes over | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) (*Bagage-lokation*) |
| Regler i `BaggageRules` uden Spring-afhængigheder | Reglerne kan unit-testes uden database og broker | `service/BaggageRulesTest` |
| Transactional outbox | Tilstand og event committes atomisk; en mutation lykkes, selv om RabbitMQ er nede | [Messaging](../docs/architecture.md#messaging) |
| Idempotency key på `registerBaggage`, no-op ved gentaget statusskift | Dobbeltklik eller retry giver hverken to stykker bagage eller ekstra events | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – [Flow B](../docs/architecture.md#flow-b--bagage),
  [Flow C](../docs/architecture.md#flow-c--aflysning), [Messaging](../docs/architecture.md#messaging)
- [docs/events.md](../docs/events.md#events-fra-baggage-service) – eventkontrakter og leveringsgarantier
- [README.md](../README.md#kør-lokalt-med-docker) – compose-stakken, DLQ- og outbox-forsøg
- [k8s/README.md](../k8s/README.md) – deploy på minikube/kind, Ingress-adresser
- [scripts/e2e-smoke.sh](../scripts/e2e-smoke.sh) – Flow B og aflysning end-to-end over GraphQL
- [docs/service-readme-template.md](../docs/service-readme-template.md) – skabelonen for denne README
