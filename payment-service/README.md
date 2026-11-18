# payment-service

Betalinger for bookinger: en simuleret betalingsgateway, der godkender eller afviser et kort, gemmer resultatet og
melder det til resten af systemet med events – plus refunderinger, manuelt af OPERATIONS eller automatisk, når en
booking aflyses. Alle operationer kræver login: en PASSENGER må betale og se betalinger for en reference, kun
OPERATIONS må refundere og slå en betaling op på id. Servicen kender bevidst ikke bookingerne: den bekræfter eller
aflyser dem ikke (det gør booking-service på `payment.*`-events), og den tjekker hverken, at referencen findes, at
beløbet er bookingens pris, eller at passageren ejer bookingen.

| | |
|---|---|
| Ansvar | Betalinger og refunderinger (simuleret gateway) |
| Port | compose `8083` · Kubernetes via Ingress `/api/payments` |
| API | GraphQL `/api/payments/graphql` (Keycloak-token på alle operationer) · REST kun actuator (`/actuator/health/*`, `/actuator/info`, `/actuator/metrics`) |
| Database | `payment_db` (PostgreSQL 16, Flyway `V1`–`V2`) |
| Events ud | `payment.completed`, `payment.failed`, `payment.refunded` |
| Events ind | `booking.cancelled` (kø `payment-service.booking-events`) |
| Image | `airport/payment-service:local` |

## Ansvar og data

- **`payment`** (`V1__init.sql`) er kilde til sandhed for betalinger: én række pr. forsøg med `booking_reference`
  (`VARCHAR(6)`, indekseret), `amount` (`NUMERIC(10,2)`, `CHECK > 0`), `currency` (altid `DKK`), `card_last4`,
  `status` (`CHECK` på de fire værdier), `failure_reason`, `created_at` og `updated_at`. Ingen anden service gemmer
  betalinger; booking-service ændrer kun bookingens status på `payment.*`-events.
- **`processed_event`** (`V1`) er idempotens for forbrugte events; **`outbox_event`** (`V2__outbox.sql`, med et
  partielt indeks på usendte rækker) er den transactional outbox, events sendes fra.
- **Ingen kopier af andres data:** intet booking-snapshot. `bookingReference` er en fri streng på 6 tegn, og beløbet
  kommer fra klienten (frontenden sender bookingens `price`).
- **Gør bevidst ikke:** bekræfter eller aflyser bookinger (booking-service), taler med en rigtig gateway eller gemmer
  fuldt kortnummer og CVV.
- `PENDING` findes i enum, schema og `CHECK`-constraint, men sættes aldrig: gatewayen er synkron, så en betaling
  gemmes direkte som `COMPLETED` eller `FAILED`.

## API

Alle fire GraphQL-operationer kræver et Keycloak-token (`Authorization: Bearer …`); der er ingen offentlige queries,
fordi betalinger hører til en booking ([Hvad er offentligt?](../docs/architecture.md#hvad-er-offentligt)).

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `payment(id)` | query | OPERATIONS | Én betaling; `null` for et ukendt id. Kun OPERATIONS, fordi id'erne er fortløbende og kan gennemløbes |
| `paymentsByBooking(reference)` | query | PASSENGER / OPERATIONS | Alle betalinger for referencen, ældste først (tom liste, hvis ingen). Referencen trimmes og gøres til store bogstaver. Intet ejerskabstjek: enhver PASSENGER kan slå enhver reference op |
| `pay(bookingReference, amount, cardNumber, expiry, cvv)` | mutation | PASSENGER / OPERATIONS | Validerer, kører `PaymentSimulator`, gemmer `COMPLETED`/`FAILED` og lægger `payment.completed`/`payment.failed` i outboxen |
| `refund(paymentId)` | mutation | OPERATIONS | `COMPLETED` → `REFUNDED` + `payment.refunded`; bookingen røres ikke |
| `/actuator/health/liveness`, `/actuator/health/readiness` | REST `GET` | Alle | Probes, se [Drift](#drift) |
| `/actuator/info` | REST `GET` | Alle | Spring Boot actuator |
| `/actuator/metrics` | REST `GET` | OPERATIONS | Spring Boot actuator; outbox-backloggen på `/actuator/metrics/outbox.pending` |
| `/graphiql` | UI | Alle | GraphiQL (slået fra i `prod`); kald derfra kræver stadig et token under *Headers* |

| Kode (`errors[].extensions.code`) | Hvornår |
|-----------------------------------|---------|
| `VALIDATION_ERROR` | `pay` med argumenter, der bryder `PayInput` (alle brud i én besked, sorteret og med feltnavne); `paymentsByBooking` med tom reference |
| `ALREADY_PAID` | `pay` på en reference, der allerede har en `COMPLETED` betaling |
| `INVALID_STATE` | `refund` af en betaling, der ikke er `COMPLETED` |
| `NOT_FOUND` | `refund` af et ukendt id |
| `UNAUTHORIZED` / `FORBIDDEN` | Operation uden token / PASSENGER på `refund` eller `payment(id)` (HTTP 200 med fejlen i `errors[]`). Et udløbet, fremmed eller ugyldigt token giver derimod HTTP 401 med `WWW-Authenticate: Bearer error="invalid_token"`, før GraphQL kører |

`PAYMENT_FAILED` findes i `ErrorCode`, men kastes aldrig – et afvist kort er et resultat, ikke en fejl. `CONFLICT`
(databasebrud) og `INTERNAL_ERROR` (uventet fejl, logges som ERROR) er de fælles koder. Fælles fejlmodel og roller:
[GraphQL-fejl](../docs/architecture.md#graphql-fejl), [Operation × rolle](../docs/architecture.md#operation--rolle) og
[Fejl og frontendens reaktion](../docs/architecture.md#fejl-og-frontendens-reaktion).

Eksempel mod compose-stakken med testbrugerne `anna` (PASSENGER) og `ops` (OPERATIONS); tokens lever 5 minutter.
payment-service tjekker ikke, at referencen findes, så eksemplet kan køres alene: `DEMO01` kan aldrig være en rigtig
bookingreference (booking-service bruger hverken `O`, `0` eller `1`), og booking-service logger blot
`payment.completed for unknown booking DEMO01`. Skal en booking blive `CONFIRMED`, skal referencen i stedet komme fra en
`PENDING_PAYMENT`-booking lavet med `createBooking` i booking-service. Id'et tages fra svaret på `pay`, fordi andre
betalinger i databasen bestemmer det:

```bash
P=http://localhost:8083/api/payments/graphql
KC=http://localhost:8180/realms/airport/protocol/openid-connect/token
ANNA=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=anna -d password=anna $KC | jq -r .access_token)
OPS=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops $KC | jq -r .access_token)
gql() { curl -s -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
  -d "{\"query\": $(jq -Rs . <<<"$2")}" "$P"; echo; }

PAY=$(gql "$ANNA" 'mutation { pay(bookingReference: "DEMO01", amount: 899.00, cardNumber: "4242 4242 4242 4242", expiry: "12/30", cvv: "123") { id status cardLast4 failureReason } }')
echo "$PAY"
# {"data":{"pay":{"id":"<id>","status":"COMPLETED","cardLast4":"4242","failureReason":null}}}
ID=$(jq -r .data.pay.id <<<"$PAY")
gql "$ANNA" 'mutation { pay(bookingReference: "demo01", amount: 899.00, cardNumber: "4242424242424242", expiry: "12/30", cvv: "123") { id } }'
# {"errors":[{"message":"Booking DEMO01 is already paid", … "extensions":{"code":"ALREADY_PAID", …}}],"data":null}
gql "$ANNA" '{ paymentsByBooking(reference: "demo01") { id amount currency cardLast4 status } }'
# {"data":{"paymentsByBooking":[…,{"id":"<id>","amount":899.00,"currency":"DKK","cardLast4":"4242","status":"COMPLETED"}]}}

gql "$ANNA" "mutation { refund(paymentId: $ID) { id status } }"
# {"errors":[{"message":"Your role does not allow this operation", … "extensions":{"code":"FORBIDDEN", …}}],"data":null}
gql "$OPS" "mutation { refund(paymentId: $ID) { id status } }"
# {"data":{"refund":{"id":"<id>","status":"REFUNDED"}}}
gql "$OPS" "mutation { refund(paymentId: $ID) { id status } }"
# {"errors":[{"message":"Only COMPLETED payments can be refunded (payment <id> is REFUNDED)", …
#   "extensions":{"code":"INVALID_STATE", …}}],"data":null}

# uden token
curl -s -H 'Content-Type: application/json' -d '{"query": "{ paymentsByBooking(reference: \"DEMO01\") { id } }"}' "$P"; echo
# {"errors":[{"message":"Authentication required: send a Bearer token", … "extensions":{"code":"UNAUTHORIZED", …}}],
#   "data":null}

gql "$ANNA" 'mutation { pay(bookingReference: "DEMO0", amount: 0, cardNumber: "4242", expiry: "2030-12", cvv: "12") { id } }'
# {"errors":[{"message":"amount: must be greater than or equal to 0.01; bookingReference: bookingReference must be
#   6 alphanumeric characters; cardNumber: cardNumber must be 13-19 digits; cvv: cvv must be 3-4 digits; expiry:
#   expiry must be in format MM/YY", … "extensions":{"code":"VALIDATION_ERROR", …}}],"data":null}
```

Det andet `pay` viser, at referencen gøres til store bogstaver (`demo01` → `DEMO01`). Efter `refund` er referencen
fri igen, så blokken kan køres flere gange; `paymentsByBooking` viser så også de tidligere, refunderede forsøg først.
Med kind går kaldene gennem Ingress'en: `P=http://localhost:8090/api/payments/graphql` og tokens fra
`http://localhost:8090/auth/realms/airport/protocol/openid-connect/token` ([Keycloak (login)](../k8s/README.md#keycloak-login)).

## Forretningsregler

- **Adgang** (`@PreAuthorize` på `PaymentController`): uden token `UNAUTHORIZED`; PASSENGER må `pay` og
  `paymentsByBooking`, men `refund` og `payment(id)` giver `FORBIDDEN` (kun OPERATIONS). Rollerne kommer fra tokenets
  `realm_access.roles` (`KeycloakRoleConverter`). Der er intet ejerskabstjek: payment-service kender ikke passagerens
  e-mail, så bookingreferencen fungerer som adgangsnøgle ([Ejerskab](../docs/architecture.md#ejerskab-egen-booking)).
- **Input** (`PayInput`, valideret med en injiceret `Validator`) → `VALIDATION_ERROR`: `bookingReference` er præcis 6
  bogstaver/cifre; `amount` ≥ 0.01 med højst 8 cifre før og 2 efter kommaet; `cardNumber` 13–19 cifre, gerne med
  enkelte mellemrum; `expiry` `MM/YY` eller `MM/YYYY`; `cvv` 3–4 cifre. Referencen gemmes med store bogstaver.
- **Simuleret gateway** (`PaymentSimulator`, i denne rækkefølge): kortet gælder til og med sidste dag i udløbsmåneden
  (UTC, `Clock` fra `PaymentConfig`; et tocifret år læses som 20YY) – ellers `FAILED` med `failureReason`
  "Card expired". Derefter: kortnummer, der ender på `0000` (mellemrum ignoreres) → `FAILED`, "Insufficient funds".
  Alt andet → `COMPLETED`. Et udløbet kort, der ender på `0000`, giver altså "Card expired".
- **Et afvist kort er ikke en GraphQL-fejl:** `pay` gemmer rækken og returnerer `Payment` med `status: FAILED` og
  `failureReason` sammen med `payment.failed`; booking-service aflyser en `PENDING_PAYMENT`-booking med årsagen
  "Payment failed: Insufficient funds" (eller "… Card expired").
- **Kortdata:** kun de sidste 4 cifre gemmes (`card_last4`). `cardNumber` og `cvv` gemmes og logges aldrig, og
  `PayInput.toString()` maskerer kortet (`card=****`), så det ikke kan havne i en log.
- **Højst én gennemført betaling pr. reference:** `ALREADY_PAID`, hvis referencen allerede har en `COMPLETED`
  betaling; opslaget sker i samme transaktion, før den nye række gemmes. `FAILED` og `REFUNDED` blokerer ikke et nyt
  forsøg.
- **Refundering:** kun `COMPLETED` → `REFUNDED` (ellers `INVALID_STATE`, ukendt id `NOT_FOUND`). En manuel `refund`
  aflyser ikke bookingen. Den automatiske refundering på `booking.cancelled` rammer også kun `COMPLETED`, så en
  betaling, der allerede er refunderet, røres ikke igen.
- **Ingen bookingkontrol:** en ukendt reference betales som alle andre (booking-service logger
  `payment.completed for unknown booking …`), beløbet sammenlignes ikke med bookingens pris, og en betaling for en
  allerede aflyst booking forbliver `COMPLETED` (booking-service logger `payment.completed received for CANCELLED
  booking …`).

## Events

**Publicerer** (via transactional outbox; routing key = eventnavn på `airport.events`, `producer: payment-service`):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|
| `payment.completed` | `pay` godkendt | `paymentId`, `bookingReference`, `amount`, `currency`, `cardLast4` |
| `payment.failed` | `pay` afvist af simulatoren | Som `payment.completed` + `failureReason` |
| `payment.refunded` | `refund` eller `booking.cancelled` (ét event pr. refunderet betaling) | `paymentId`, `bookingReference`, `amount`, `currency` |

booking-service lytter på `payment.#`: `payment.completed` bekræfter en `PENDING_PAYMENT`-booking, `payment.failed`
aflyser den, og `payment.refunded` modtages, men ignoreres (ingen service reagerer på det).

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `payment-service.booking-events` | `booking.#` | `booking.cancelled` | Refunderer alle `COMPLETED` betalinger for `bookingReference` (`payment.refunded` pr. betaling); ingen betaling er ingen fejl (`Booking … cancelled -> 0 payment(s) refunded`) |
| `payment-service.booking-events` | `booking.#` | `booking.created`, `booking.confirmed`, `booking.checkedin` | Ignoreres, men registreres i `processed_event` |

Events skrives i `outbox_event` i samme transaktion som ændringen og sendes af `OutboxRelay` med publisher confirms
(at-least-once, rækkefølgen bevares); indgående events dedupliceres på `eventId` i `processed_event`, og en besked, der
fejler 3 gange (også en envelope, der ikke kan parses), går via `airport.events.dlx` til `payment-service.dlq`. Se
[events.md](../docs/events.md#leveringsgarantier), payloads i [events.md](../docs/events.md#events-fra-payment-service)
og køerne i [events.md](../docs/events.md#køer-bindings-og-dead-letter).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `DB_URL` | `jdbc:postgresql://localhost:5435/payment_db` | compose udstiller `payment-db` på 5435; i compose og k8s `payment-db:5432` |
| `DB_USERNAME` / `DB_PASSWORD` | `payment` / `payment` | I k8s fra Secret `payment-service-secret` (dev-værdier) |
| `GRAPHQL_PATH` | `/api/payments/graphql` | Samme sti lokalt og bag Ingress |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Hvor ofte `OutboxRelay` sender ventende events |
| `SERVER_PORT` | `8080` | HTTP-port |
| `SPRING_PROFILES_ACTIVE` | – | `prod` (k8s): GraphiQL slået fra og JSON-logs; compose sætter `dev` |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/airport` | Den `iss`, tokens skal have. compose: samme værdi; kind: `http://localhost:8090/auth/realms/airport` |
| `JWK_SET_URI` | `http://localhost:8180/realms/airport/protocol/openid-connect/certs` | Hvor signeringsnøglerne hentes. compose: `http://keycloak:8080/realms/airport/protocol/openid-connect/certs`; kind: `http://keycloak:8080/auth/realms/airport/protocol/openid-connect/certs` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Kommasepareret (`app.cors.allowed-origins`); gælder alle stier via `SecurityConfig`, og preflight kræver intet token |
| `RABBITMQ_*` | localhost-værdier | Fælles for alle services, se [rod-README'en](../README.md#konfiguration) |

Med begge OIDC-værdier sat bruger Spring aldrig OIDC discovery: nøglerne hentes fra `JWK_SET_URI` ved første token, og
`iss` sammenlignes med `OIDC_ISSUER_URI` som ren streng ([issuer og JWKS](../docs/architecture.md#konfiguration-issuer-og-jwks)).
Servicen starter derfor også, når Keycloak ikke kører; kun kald med token kræver den.

Uden variabel (ret i `application.yml`): outboxen (`batch-size` 100, `confirm-timeout-ms` 5000, `retention` 7d,
oprydning hver time), listener-retry (3 forsøg, 500 ms ×2, højst 3 s) og exchange- og kønavnene (`app.messaging.*`).
Kortreglerne er kode i `PaymentSimulator`, og rollerne står i `@PreAuthorize`, ikke i konfiguration.
Kubernetes-værdierne står i ConfigMap `payment-service-config` i `k8s/services/payment-service.yaml`.

## Kør lokalt

```bash
# hele stakken fra repo-roden; GraphiQL (dev-profil): http://localhost:8083/graphiql?path=/api/payments/graphql
docker compose up --build

# kun payment-service (compose starter payment-db og rabbitmq med); keycloak er ikke en depends_on, men skal
# køre for at hente tokens
docker compose up -d --build keycloak payment-service

# servicen fra Maven/IDE mod compose-infrastrukturen (dev-defaults: localhost:5435, :5672 og Keycloak på :8180)
docker compose up -d payment-db rabbitmq keycloak
cd payment-service && SERVER_PORT=8083 mvn spring-boot:run
```

Tokens hentes som i eksemplet ovenfor fra `http://localhost:8180/realms/airport/protocol/openid-connect/token`
([Login og roller](../README.md#login-og-roller)). `pay` virker uden booking-service, men så bliver ingen booking
bekræftet. Se også [Kør en enkelt service uden Docker](../README.md#kør-en-enkelt-service-uden-docker-udvikling).

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `service/PaymentSimulatorTest` | Unit, fast `Clock` (11-09-2026), 8 tests | Gyldigt kort godkendes; `0000` → "Insufficient funds"; `08/26` → "Card expired"; et kort, der udløber i indeværende måned, gælder; `MM/YYYY` accepteres; udløb vinder over `0000`; `13/28` og `2028-12` → `VALIDATION_ERROR`; `cardLast4` ignorerer mellemrum |
| `PaymentServiceIntegrationTest` | Integration: Testcontainers (Postgres 16 + RabbitMQ 3.13), GraphQL over HTTP gennem security-filterkæden (`HttpGraphQlTester`) med test-JWT (anna til `pay`/`paymentsByBooking`, ops til `refund`), 11 tests | `pay` → `COMPLETED` og `payment.completed` på exchangen (envelope med `eventId`, `producer`, `paymentId`), rækken har hverken kortnummer eller CVV; anden `pay` → `ALREADY_PAID`; `0000` → `FAILED` + `payment.failed`; `booking.cancelled` leveret to gange med samme `eventId` refunderer én gang og giver ét `payment.refunded`, ukendt reference og `booking.confirmed` ændrer intet; `paymentsByBooking` (også tom liste); `refund` af `FAILED` → `INVALID_STATE`, ukendt id → `NOT_FOUND`; manuel `refund` → `payment.refunded`; ugyldigt input → `VALIDATION_ERROR` med feltnavne og intet gemt; outbox: `publish` uden transaktion afvises, rollback giver hverken række eller event, relayet prøver igen efter en simuleret broker-fejl (`attempts`, `last_error`, `outbox.pending` 0 bagefter) |
| `SecurityIntegrationTest` | Integration: Testcontainers, HTTP med test-JWT, 5 tests | Readiness uden token; `pay`, `paymentsByBooking`, `refund` og `payment(id)` uden token → `UNAUTHORIZED`; PASSENGER betaler og ser historikken, men `payment(id)` og `refund` → `FORBIDDEN`, OPERATIONS må begge; udløbet token, fremmed issuer og `not.a.jwt` → HTTP 401 med `invalid_token`; CORS-preflight fra `http://localhost:8080` tilladt |

De første 8 integrationstests kører i fast rækkefølge (`@Order`) og bygger videre på hinandens betalinger.
`TestTokens` er ingen test, men en `@TestConfiguration`, der minter RS256-tokens med Keycloaks claims og leverer en
`JwtDecoder` for testnøglen, så Keycloak ikke skal køre ([Test](../docs/architecture.md#test)).

```bash
cd payment-service && mvn -B -ntp -Pci verify   # som CI: tests + Checkstyle + SpotBugs
```

Det er samme kommando, som CI's `backend`-job kører for payment-service ([CI](../README.md#ci-github-actions));
Surefire-rapporterne uploades som artifact `reports-payment-service`. Kører alle **24 tests** (8 + 11 + 5). Docker
skal køre (Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`). Samspillet med
booking-service testes end-to-end af [smoke-testen](../README.md#automatisk-smoke-test-af-flow-ad) mod en kørende stak.

## Drift

- **Health:** `/actuator/health/liveness` og `/actuator/health/readiness` (`readinessState`, `db`, `rabbit`, med
  detaljer), åbne uden token. compose bruger readiness som healthcheck; Ingress'en router hverken `/actuator` eller
  `/graphiql`. Keycloak indgår ikke i readiness.
- **Outbox-backlog:** gauge `outbox.pending` på `/actuator/metrics/outbox.pending` (usendte rækker; 0 i normal drift)
  kræver et OPERATIONS-token (uden token HTTP 401, med annas HTTP 403):
  `curl -s -H "Authorization: Bearer $OPS" localhost:8083/actuator/metrics/outbox.pending | jq '.measurements[0].value'`.
  `outbox_event` viser `attempts` og `last_error` for rækker, der venter. Se
  [Test af transactional outbox](../README.md#test-af-transactional-outbox-broker-nede) og
  [retry + dead-letter queue](../README.md#test-af-retry--dead-letter-queue).
- **Logs** (JSON i `prod`; consumer-linjer har `eventId`/`eventType` i MDC):
  `Payment 1 for booking DEMO01 COMPLETED (card ****4242)`, `Payment … FAILED: Insufficient funds`,
  `Payment 1 for booking DEMO01 REFUNDED`, `Booking … cancelled -> 1 payment(s) refunded`,
  `Skipping already processed event booking.cancelled eventId=…`, `Queued event … in outbox`/`Published event …` og
  WARN `Outbox: … event(s) could not be published (attempt …), will retry` (første forsøg og hvert 20.). Kortnummeret
  logges aldrig.
- **Kubernetes** (`k8s/services/payment-service.yaml`): ConfigMap (med `OIDC_ISSUER_URI`/`JWK_SET_URI` for kind),
  Secret, `Service` (ClusterIP, 8080) og `Deployment` med 2 replicas; startup- og liveness-probe på `/actuator/health/liveness`, readiness-probe på
  `/actuator/health/readiness`; `requests` 250m/512Mi, `limits` 1000m/1Gi;
  `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`. Databasen er `k8s/databases/payment-db.yaml` (StatefulSet med PVC
  på 1Gi).
- **Flere replicas** er sikre: kun én pod ad gangen sender fra outboxen (Postgres advisory lock
  `pg_try_advisory_xact_lock`), så rækkefølgen bevares; begge pods læser fra samme kø, og `processed_event` fjerner
  dubletter. Se [k8s/README.md](../k8s/README.md).

## Designvalg

| Valg | Begrundelse | Uddybet i |
|------|-------------|-----------|
| Afvist kort giver `Payment` med `status: FAILED`, ikke en GraphQL-fejl | Frontenden kan vise årsagen; bookingen aflyses asynkront via `payment.failed` | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed), "Fejlet betaling" |
| Scalar-argumenterne samles i `PayInput` og valideres med en injiceret `Validator` | Spec'ens signatur `pay(bookingReference, amount, …)` bevares, og fejlbeskeden har feltnavne | Samme tabel, "Betalingsvalidering" |
| `MM/YY` og `MM/YYYY`, gyldig til månedens sidste dag, "Card expired" vinder over `0000` | Enkel, forudsigelig simuleringsregel | Samme tabel, "Udløbsdato" |
| Intet booking-snapshot; `ALREADY_PAID` mod dobbeltbetaling | Spec'en kræver kun lytning på `booking.cancelled`; beløbet kommer fra frontenden | Samme tabel, "Payment kender ikke bookingen" |
| `booking.cancelled` refunderer alle `COMPLETED` betalinger for referencen | Pengene går tilbage, uanset hvorfor bookingen blev aflyst, uden at booking-service kalder payment-service | [Flow C – Aflysning](../docs/architecture.md#flow-c--aflysning) |
| Events via transactional outbox | Betaling og event committes atomisk; intet event tabes mellem DB-commit og broker | [Messaging](../docs/architecture.md#messaging) |
| `pay`/`paymentsByBooking` for PASSENGER og OPERATIONS uden ejerskabstjek; `refund` og `payment(id)` kun OPERATIONS | Payment kender kun bookingreferencen, ikke passagerens e-mail, så referencen er adgangsnøglen; fortløbende id'er kan gennemløbes, og refundering er en driftshandling | [Operation × rolle](../docs/architecture.md#operation--rolle), [Ejerskab](../docs/architecture.md#ejerskab-egen-booking) |

## Se også

- [docs/architecture.md](../docs/architecture.md) –
  [Flow A – Booking og betaling](../docs/architecture.md#flow-a--booking-og-betaling),
  [Flow C – Aflysning](../docs/architecture.md#flow-c--aflysning),
  [Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller) og
  [Teknologi pr. service](../docs/architecture.md#teknologi-pr-service) (pakkestrukturen)
- [docs/events.md](../docs/events.md) – eventkontrakten, bl.a. [`booking.cancelled`](../docs/events.md#bookingcancelled)
- [README.md](../README.md#login-og-roller) – testbrugere og tokens, [Flow A og C fra
  frontenden](../README.md#prøv-flows-fra-frontenden) og [smoke-testen](../README.md#automatisk-smoke-test-af-flow-ad)
- [k8s/README.md](../k8s/README.md) – deployment på minikube/kind og [Keycloak (login)](../k8s/README.md#keycloak-login)
