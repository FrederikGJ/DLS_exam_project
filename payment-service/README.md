# payment-service

Betalinger for bookinger: en simuleret betalingsgateway, der godkender eller afviser et kort, gemmer resultatet og
melder det til resten af systemet med events – plus refunderinger, manuelt af OPERATIONS eller automatisk, når en
booking aflyses. Servicen kender bevidst ikke bookingerne: den bekræfter eller aflyser dem ikke (det gør
booking-service på `payment.*`-events), og den tjekker hverken, at referencen findes, eller at beløbet er bookingens pris.

| | |
|---|---|
| Ansvar | Betalinger og refunderinger (simuleret gateway) |
| Port | compose `8083` · Kubernetes via Ingress `/api/payments` |
| API | GraphQL `/api/payments/graphql` |
| Database | `payment_db` (PostgreSQL 16, Flyway `V1`–`V4`) |
| Events ud | `payment.completed`, `payment.failed`, `payment.refunded` |
| Events ind | `booking.cancelled`, `booking.payment.rejected` (kø `payment-service.booking-events`) |
| Image | `airport/payment-service:local` |

## Ansvar og data

- **`payment`** (`V1__init.sql`) er kilde til sandhed for betalinger: én række pr. forsøg med `booking_reference`
  (6 tegn, indekseret), `amount` (`NUMERIC(10,2)`, `CHECK > 0`), `currency` (altid `DKK`), `card_last4`, `status`,
  `failure_reason`, `created_at` og `updated_at`. booking-service har kun en kopi i sin read model `booking_overview`.
- **`processed_event`** (`V1`) er idempotens for forbrugte events; **`outbox_event`** (`V2__outbox.sql`, kolonnen
  `traceparent` fra `V3__outbox_traceparent.sql`) er den transactional outbox, events sendes fra.
- **`ux_payment_one_completed`** (`V4__payment_one_completed.sql`): partielt unikt indeks på `booking_reference`
  `WHERE status = 'COMPLETED'` – højst én gennemført betaling pr. booking, også ved samtidige kald.
- **Ingen kopier af andres data:** intet booking-snapshot. `bookingReference` er en fri streng, og beløbet kommer fra
  klienten (frontenden sender bookingens pris).
- **Gør bevidst ikke:** bekræfter eller aflyser bookinger (booking-service), sender mails (notification-job lytter kun
  på `booking.#`), taler med en rigtig gateway eller gemmer fuldt kortnummer og CVV.
- `PENDING` findes i enum, schema og `CHECK`-constraint, men sættes aldrig: gatewayen er synkron, så en betaling
  gemmes direkte som `COMPLETED` eller `FAILED`.

## API

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `payment(id)` | query | Alle (ingen `@PreAuthorize`) | Én betaling; `null` for et ukendt id |
| `paymentsByBooking(reference)` | query | PASSENGER / OPERATIONS | Alle betalinger for referencen, ældste først (tom liste, hvis ingen). Referencen trimmes og gøres til store bogstaver. Intet ejerskabstjek: enhver PASSENGER kan slå enhver reference op |
| `pay(bookingReference, amount, cardNumber, expiry, cvv)` | mutation | PASSENGER / OPERATIONS | Validerer, kører `PaymentSimulator`, gemmer `COMPLETED`/`FAILED` og lægger `payment.completed`/`payment.failed` i outboxen |
| `refund(paymentId)` | mutation | OPERATIONS | `COMPLETED` → `REFUNDED` + `payment.refunded`; bookingen røres ikke |

| Kode (`errors[].extensions.code`) | Hvornår |
|-----------------------------------|---------|
| `VALIDATION_ERROR` | `pay` med argumenter, der bryder `PayInput` (alle brud i én besked med feltnavne); `paymentsByBooking` med tom reference |
| `ALREADY_PAID` | `pay` på en reference, der allerede har en `COMPLETED` betaling |
| `INVALID_STATE` | `refund` af en betaling, der ikke er `COMPLETED` |
| `NOT_FOUND` | `refund` af et ukendt id |
| `UNAUTHORIZED` / `FORBIDDEN` | Beskyttet operation uden token / med forkert rolle; et ugyldigt token giver HTTP 401 før GraphQL |

`PAYMENT_FAILED` findes i `ErrorCode`, men kastes aldrig – et afvist kort er et resultat, ikke en fejl. Fælles
fejlmodel og roller: [GraphQL-fejl](../docs/architecture.md#graphql-fejl) og
[Operation × rolle](../docs/architecture.md#operation--rolle).

Kørt 17-09-2026 på kind gennem Ingress som `anna` (PASSENGER), på en booking lavet til eksemplet. Fly 3 var
`SCHEDULED` (`flights(filter: {status: SCHEDULED})`), og sæde 30D var ledigt (`availableSeats(flightId: 3)`):

```bash
G=http://localhost:8090
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna \
  $G/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)
gql() { curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"query\": $(jq -Rs . <<<"$2")}" "$G/api/$1/graphql"; echo; }

gql bookings 'mutation { createBooking(flightId: 3, seatNumber: "30D", passenger: {firstName: "Anna", lastName: "Jensen", email: "anna@example.com", passportNumber: "DK1234567"}) { bookingReference status price } }'
# {"data":{"createBooking":{"bookingReference":"QTASUL","status":"PENDING_PAYMENT","price":699.0}}}
gql payments 'mutation { pay(bookingReference: "QTASUL", amount: 699.00, cardNumber: "4242 4242 4242 4242", expiry: "12/28", cvv: "123") { id status cardLast4 failureReason } }'
# {"data":{"pay":{"id":"6","status":"COMPLETED","cardLast4":"4242","failureReason":null}}}
gql payments 'mutation { pay(bookingReference: "QTASUL", amount: 699.00, cardNumber: "4242424242424242", expiry: "12/28", cvv: "123") { id } }'
# {"errors":[{"message":"Booking QTASUL is already paid", … "extensions":{"code":"ALREADY_PAID", …}}],"data":null}
gql payments '{ paymentsByBooking(reference: "QTASUL") { id amount currency cardLast4 status createdAt } }'
# {"data":{"paymentsByBooking":[{"id":"6","amount":699.00,"currency":"DKK","cardLast4":"4242","status":"COMPLETED","createdAt":"2026-09-17T07:28:11.994Z"}]}}

# aflys bookingen -> booking.cancelled -> payment-service refunderer
gql bookings 'mutation { cancelBooking(reference: "QTASUL") { status } }'
# {"data":{"cancelBooking":{"status":"CANCELLED"}}}
sleep 3
gql payments '{ paymentsByBooking(reference: "QTASUL") { id status } }'
# {"data":{"paymentsByBooking":[{"id":"6","status":"REFUNDED"}]}}

gql payments 'mutation { pay(bookingReference: "QTASU", amount: 0, cardNumber: "4242", expiry: "2028-12", cvv: "12") { id } }'
# {"errors":[{"message":"amount: must be greater than or equal to 0.01; bookingReference: bookingReference must be
#   6 alphanumeric characters; cardNumber: cardNumber must be 13-19 digits; cvv: cvv must be 3-4 digits; expiry:
#   expiry must be in format MM/YY", … "extensions":{"code":"VALIDATION_ERROR", …}}],"data":null}
```

Mellem `pay` og `cancelBooking` var bookingen `CONFIRMED`. Samme dag gav `paymentsByBooking` uden token
`UNAUTHORIZED`, `refund` med annas token `FORBIDDEN`, og `refund` af en allerede refunderet betaling med et
`ops`-token `INVALID_STATE` ("Only COMPLETED payments can be refunded (payment … is REFUNDED)").

## Forretningsregler

- **Input** (`PayInput`, valideret med en injiceret `Validator`) → `VALIDATION_ERROR`: `bookingReference` er 6
  bogstaver/cifre; `amount` ≥ 0.01 med højst 8 cifre før og 2 efter kommaet; `cardNumber` 13–19 cifre, gerne med
  enkelte mellemrum; `expiry` `MM/YY` eller `MM/YYYY`; `cvv` 3–4 cifre. Referencen gemmes med store bogstaver.
- **Simuleret gateway** (`PaymentSimulator`, i denne rækkefølge): kortet gælder til og med sidste dag i udløbsmåneden
  (UTC) – ellers `FAILED` med `failureReason` "Card expired". Derefter: kortnummer, der ender på `0000` → `FAILED`,
  "Insufficient funds". Alt andet → `COMPLETED`. Et udløbet kort, der ender på `0000`, giver altså "Card expired".
- **Et afvist kort er ikke en GraphQL-fejl:** `pay` returnerer `Payment` med `status: FAILED` og `failureReason`, og
  booking-service aflyser bookingen på `payment.failed` (kørt på kind: `4111111111110000` → `FAILED`, bookingen blev
  `CANCELLED` med årsagen "Payment failed: Insufficient funds").
- **Kortdata:** kun de sidste 4 cifre gemmes (`card_last4`). `cardNumber` og `cvv` gemmes aldrig, og
  `PayInput.toString()` maskerer kortet, så det ikke kan havne i en log.
- **Højst én gennemført betaling pr. reference:** `ALREADY_PAID`, hvis referencen har en `COMPLETED` betaling;
  `FAILED` og `REFUNDED` blokerer ikke et nyt forsøg. Tjekket før betalingen dækker kald efter hinanden; ved
  *samtidige* kald afviser det partielle unikke indeks `ux_payment_one_completed` den anden `COMPLETED`-række,
  transaktionen rulles tilbage (intet event), og `GraphQlExceptionResolver` svarer også `ALREADY_PAID`. Før indekset
  (fundet 17-09-2026) kunne to samtidige `pay` begge blive `COMPLETED`, og den ekstra betaling blev aldrig refunderet.
- **Refundering:** kun `COMPLETED` → `REFUNDED` (ellers `INVALID_STATE`, ukendt id `NOT_FOUND`). En manuel `refund`
  aflyser ikke bookingen; booking-service opdaterer kun betalingslinjen i `booking_overview`. Automatisk refundering
  sker fra events (se nedenfor) og rammer også kun `COMPLETED`, så en betaling refunderes højst én gang.
- **Ingen bookingkontrol:** en ukendt reference betales som alle andre (booking-service logger
  `payment.completed for unknown booking`), og beløbet sammenlignes ikke med bookingens pris.

## Events

**Publicerer** (via transactional outbox; routing key = eventnavn på `airport.events`, `producer: payment-service`):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `payment.completed` | `pay` godkendt | `paymentId`, `bookingReference`, `amount`, `currency`, `cardLast4` |
| `payment.failed` | `pay` afvist af simulatoren | Som `payment.completed` + `failureReason` |
| `payment.refunded` | `refund`, `booking.cancelled` eller `booking.payment.rejected` | `paymentId`, `bookingReference`, `amount`, `currency` |

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `payment-service.booking-events` | `booking.#` | `booking.cancelled` | Refunderer alle `COMPLETED` betalinger for `bookingReference`; ingen betaling er ingen fejl (`Booking … cancelled -> 0 payment(s) refunded`) |
| `payment-service.booking-events` | `booking.#` | `booking.payment.rejected` | Saga-kompensation for en for sen betaling: refunderer `paymentId`, hvis den hører til `bookingReference` og stadig er `COMPLETED`; ellers kun en loglinje |
| `payment-service.booking-events` | `booking.#` | `booking.created`, `booking.confirmed`, `booking.checkedin` | Ignoreres, men registreres i `processed_event` (tælles som `processed`) |

Events skrives i `outbox_event` i samme transaktion som ændringen og sendes af `OutboxRelay` med publisher confirms
(at-least-once, rækkefølgen bevares); indgående events dedupliceres på `eventId` i `processed_event`, og en besked, der
fejler 3 gange, går via `airport.events.dlx` til `payment-service.dlq`. Se
[events.md](../docs/events.md#leveringsgarantier), payloads i [events.md](../docs/events.md#events-fra-payment-service)
og kontrakten i [asyncapi.yaml](../docs/asyncapi.yaml).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` | `jdbc:postgresql://localhost:5435/payment_db` | compose udstiller `payment-db` på 5435; i k8s `payment-db:5432` |
| `DB_USERNAME` / `DB_PASSWORD` | `payment` / `payment` | I k8s fra Secret `payment-service-secret` (dev-værdier) |
| `GRAPHQL_PATH` | `/api/payments/graphql` | Samme sti lokalt og bag Ingress |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender ventende events |
| `SERVER_PORT` | `8080` | HTTP-port |
| `SPRING_PROFILES_ACTIVE` | – | `prod` (k8s): GraphiQL slået fra og JSON-logs; compose sætter `dev` |
| `RABBITMQ_*`, `OIDC_ISSUER_URI`, `JWK_SET_URI`, `CORS_ALLOWED_ORIGINS` | localhost-værdier | Fælles for alle services, se [rod-README'en](../README.md#konfiguration) |

Uden variabel (ret i `application.yml`): outboxen (`batch-size` 100, `confirm-timeout-ms` 5000, `retention` 7d,
oprydning hver time), listener-retry (3 forsøg, 500 ms ×2, højst 3 s) og kønavnene. Kortreglerne er kode i
`PaymentSimulator`, ikke konfiguration. Kubernetes-værdierne står i ConfigMap `payment-service-config` i
`k8s/base/services/payment-service.yaml`.

## Kør lokalt

```bash
# hele stakken fra repo-roden; GraphiQL (dev-profil): http://localhost:8083/graphiql
docker compose up --build

# kun payment-service (compose starter payment-db og rabbitmq med); Keycloak er nødvendig for kald med token
docker compose up -d --build keycloak payment-service

# servicen fra Maven/IDE mod compose-infrastrukturen (dev-defaults: localhost:5435, :5672 og Keycloak på :8180)
docker compose up -d payment-db rabbitmq keycloak
cd payment-service && SERVER_PORT=8083 mvn spring-boot:run
```

I compose hentes tokenet som i eksemplet ovenfor, men fra
`http://localhost:8180/realms/airport/protocol/openid-connect/token` ([Login og roller](../README.md#login-og-roller)).
`pay` virker uden booking-service, men så bliver ingen booking bekræftet.

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `service/PaymentSimulatorTest` | Unit, fast `Clock` (11-09-2026), 8 tests | Gyldigt kort godkendes; `0000` → "Insufficient funds"; `08/26` → "Card expired"; et kort, der udløber i indeværende måned, gælder; `MM/YYYY` accepteres; udløb vinder over `0000`; `13/28` og `2028-12` → `VALIDATION_ERROR`; `cardLast4` ignorerer mellemrum |
| `PaymentServiceIntegrationTest` | Integration: Testcontainers (Postgres 16 + RabbitMQ 3.13), GraphQL over HTTP med test-JWT, 13 tests | `pay` → `COMPLETED` og `payment.completed` på exchangen, rækken har hverken kortnummer eller CVV; anden `pay` → `ALREADY_PAID`; 8 samtidige `pay` på samme booking → præcis én `COMPLETED` og ét event, resten `ALREADY_PAID`, og databasen afviser en ekstra `COMPLETED`-række; `0000` → `FAILED` + `payment.failed`; `booking.cancelled` leveret to gange med samme `eventId` refunderer én gang, ukendt reference og `booking.confirmed` ændrer intet; `paymentsByBooking` (også tom liste); `refund` af `FAILED` → `INVALID_STATE`, ukendt id → `NOT_FOUND`; manuel `refund` → `payment.refunded`; `booking.payment.rejected` og `booking.cancelled` i begge rækkefølger og gentaget → præcis én refundering, fremmed reference → ingen; ugyldigt input → `VALIDATION_ERROR` med feltnavne og intet gemt; outbox: `publish` uden transaktion afvises, rollback giver hverken række eller event, relayet prøver igen efter en simuleret broker-fejl |
| `SecurityIntegrationTest` | Integration: Testcontainers, `@AutoConfigureObservability`, 6 tests | `payment(id)` og readiness uden token; `pay`, `paymentsByBooking` og `refund` uden token → `UNAUTHORIZED`; PASSENGER betaler og ser historik, men `refund` → `FORBIDDEN`, OPERATIONS må; udløbet token, fremmed issuer og `not.a.jwt` → HTTP 401 `invalid_token`; `/actuator/prometheus` åben (med `outbox_pending`), `/actuator/metrics` → 401; CORS-preflight fra `http://localhost:8080` tilladt |

`TestTokens` er ingen test, men en `@TestConfiguration`, der udsteder RS256-tokens med Keycloaks claims og en
`JwtDecoder` for testnøglen, så Keycloak ikke skal køre.

```bash
cd payment-service && mvn -Pci verify
```

Kører Checkstyle (`validate`), alle **27 tests** (8 + 13 + 6) og SpotBugs + find-sec-bugs (`verify`) med reglerne i
`../config`. Docker skal køre (Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`).
Samspillet med booking-service testes udefra med de byggede images i [system-tests](../system-tests/README.md).

## Drift

- **Health:** `/actuator/health/liveness` og `/actuator/health/readiness` (`readinessState`, `db`, `rabbit`); åbne
  uden token, men Ingress'en router ikke `/actuator` (heller ikke `/graphiql`).
- **Metrics** på `/actuator/prometheus` (åben, tag `application="payment-service"`): `outbox_pending`,
  `events_published_total{type}` for de tre `payment.*`-events og `events_consumed_total{type, outcome}` for
  `booking.*` (`processed`/`duplicate`/`failed`; en envelope, der ikke kan parses, tælles som `type="unreadable"`)
  ud over Spring Boots egne. De øvrige actuator-endpoints kræver OPERATIONS. Alarmerne (`OutboxBacklog`,
  `MessagesDeadLettered` for `payment-service.dlq`, `EventHandlingFailing` m.fl.) og hvad man gør, står under
  [Observability](../docs/architecture.md#observability-metrics-logs-og-alarmer).
- **Logs** (JSON med `traceId`/`spanId` i `prod`; consumer-linjer også med `eventId`/`eventType`):
  `Payment 6 for booking QTASUL COMPLETED (card ****4242)`, `Payment … FAILED: Insufficient funds`,
  `Payment 6 for booking QTASUL REFUNDED`, `Booking QTASUL cancelled -> 1 payment(s) refunded`,
  `Payment … rejected by booking … -> refunded: true|false`, WARN `booking.payment.rejected for unknown payment …` og
  WARN `Outbox: … event(s) could not be published (attempt …), will retry`. Kortnummeret logges aldrig.
- **Kubernetes** (`k8s/base/services/payment-service.yaml`): ConfigMap, Secret, `Service` på 8080 og `Deployment` med
  1 replica; initContainer `wait-for-db` (`pg_isready` mod `payment-db`); startup- og liveness-probe på
  `/actuator/health/liveness`, readiness-probe på `/actuator/health/readiness`; `requests` 150m/256Mi, `limits`
  500m/640Mi; uid 100, read-only rodfilsystem med `/tmp` som `emptyDir`; Prometheus-annotationer. Databasen er
  `k8s/base/databases/payment-db.yaml`. Flere replicas er sikre (advisory lock på relayet, `processed_event`), se
  [Ressourcer på en laptop](../k8s/README.md#ressourcer-på-en-laptop).

## Designvalg

| Valg | Begrundelse | Uddybet i |
|------|-------------|-----------|
| Afvist kort giver `Payment` med `status: FAILED`, ikke en GraphQL-fejl | Frontenden kan vise årsagen; bookingen aflyses asynkront via `payment.failed` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed), "Fejlet betaling" |
| Scalar-argumenterne samles i `PayInput` og valideres med en injiceret `Validator` | Spec'ens signatur `pay(bookingReference, amount, …)` bevares, og fejlbeskeden har feltnavne | Samme tabel, "Betalingsvalidering" |
| `MM/YY` og `MM/YYYY`, gyldig til månedens sidste dag, "Card expired" vinder over `0000` | Enkel, forudsigelig simuleringsregel | Samme tabel, "Udløbsdato" |
| Intet booking-snapshot; `ALREADY_PAID` mod dobbeltbetaling | Spec'en kræver kun lytning på `booking.cancelled`; beløbet kommer fra frontenden | Samme tabel, "Payment kender ikke bookingen" |
| `booking.payment.rejected` refunderer én bestemt betaling, kun hvis den er `COMPLETED` | En for sen betaling skal tilbage uden et nyt `booking.cancelled`, der ville frigive sædet igen; uskadeligt ved gentagelse | [Saga](../docs/architecture.md#saga-bookingen-som-en-kæde-af-lokale-transaktioner) |
| Gentaget `pay`/`refund` afgøres af betalingens tilstand | Ingen idempotency key: `ALREADY_PAID` og `INVALID_STATE` beskriver gentagelsen | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |
| Events via transactional outbox | Betaling og event committes atomisk; intet event tabes mellem DB-commit og broker | [Messaging](../docs/architecture.md#messaging) |

## Se også

- [docs/architecture.md](../docs/architecture.md) – [Flow A – Booking og betaling](../docs/architecture.md#flow-a--booking-og-betaling),
  [Flow C – Aflysning](../docs/architecture.md#flow-c--aflysning),
  [Saga](../docs/architecture.md#saga-bookingen-som-en-kæde-af-lokale-transaktioner),
  [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller)
- [docs/events.md](../docs/events.md) og [docs/asyncapi.yaml](../docs/asyncapi.yaml) – eventkontrakten
- [README.md](../README.md#saga-demo-betalingstimeout-og-for-sen-betaling) – saga-demoen (`scripts/demo-saga.sh`) og
  [retry + dead-letter queue](../README.md#test-af-retry--dead-letter-queue)
- [system-tests/README.md](../system-tests/README.md) – booking ↔ payment med de byggede images
- [k8s/README.md](../k8s/README.md) – deployment på kind/minikube
