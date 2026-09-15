# System-test: samarbejde mellem booking- og payment-service (system-tests/)

Én test, `CooperationTest`, der starter de **rigtige Docker-images** af `booking-service` og `payment-service`
sammen med RabbitMQ og PostgreSQL (Testcontainers) og verificerer, at de to services samarbejder korrekt – både det
synkrone GraphQL-kald til flight-service og de asynkrone events over `airport.events` – set **udefra**: GraphQL over
HTTP ind, events på exchangen ud. Testen deler ingen kode med services'ne og kigger ikke i deres databaser eller
outbox-tabeller; den ser præcis det, frontenden og de andre services ser.

Services'ne kører med den samme konfiguration som i `docker-compose.yml`/`k8s/` (miljøvariabler, `dev`-profil,
`JAVA_TOOL_OPTIONS`, memory-limit 640 Mi), så det er de images, der deployes, der testes – ikke en test-build.

## Hvad testen dækker

| Test | Flow | Verificerer |
|------|------|-------------|
| `paidBookingIsConfirmedThroughPaymentCompletedEvent` | `createBooking` (PASSENGER) → `PENDING_PAYMENT` → `pay` med gyldigt kort → `COMPLETED` → booking bliver `CONFIRMED` | Flynummer/sæde/pris kommer fra det synkrone kald til (den stubbede) flight-service; `booking.created`, `payment.completed` og `booking.confirmed` udgives med korrekt `producer`, `eventId` og payload; betaling nr. 2 afvises med `ALREADY_PAID` |
| `declinedCardFailsPaymentAndCancelsBooking` | kortnummer der ender på `0000` → `FAILED` ("Insufficient funds") → booking bliver `CANCELLED` | `payment.failed` → booking-service annullerer med årsagen fra eventet (`booking.cancelled`, reason `Payment failed: Insufficient funds`); ingen refund, da intet er betalt |
| `cancellingConfirmedBookingRefundsThePayment` | betalt og bekræftet booking → `cancelBooking` (PASSENGER) | `booking.cancelled` → payment-service refunderer og udgiver `payment.refunded` med samme `paymentId` og beløb; `paymentsByBooking` viser `REFUNDED`; bookingen forbliver `CANCELLED` |
| `mutationsNeedATokenAndRefundNeedsOperations` | uden token, med PASSENGER-token, med OPERATIONS-token | `UNAUTHORIZED` uden token, `FORBIDDEN` for PASSENGER på `refund`, OPERATIONS kommer forbi autorisationen (`NOT_FOUND` på ukendt betaling), `bookingByReference` er offentlig |
| `unknownFlightIsRejectedByTheSynchronousFlightServiceCall` | `createBooking` på fly 99 | flight-service-stubben svarer `flight: null` → booking-service svarer `NOT_FOUND`, intet gemmes |

Hele klassen **springes over** (ikke fejler) hvis `airport/booking-service:local` og `airport/payment-service:local`
ikke findes lokalt (`assumeTrue` i `@BeforeAll`), så `mvn verify` er grøn på en maskine uden byggede images.

## Hvad der startes

Alle containere er på ét Docker-netværk, så services'ne finder hinanden på hostnavn præcis som i compose/Kubernetes:

| Container | Image | Rolle |
|-----------|-------|-------|
| `postgres` | `postgres:16-alpine` | Én PostgreSQL med **to** databaser, `booking_db` (bruger `booking`) og `payment_db` (bruger `payment`), oprettet af `src/test/resources/init.sql` (`/docker-entrypoint-initdb.d/`). Hver service ser stadig kun sin egen database med egne credentials; Flyway opretter tabellerne. |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | Brokeren. Testen binder sin egen kø med routing key `#` på `airport.events` (ren `amqp-client`, ingen Spring AMQP) og samler alle events i hukommelsen. |
| `wiremock` | `wiremock/wiremock:3.13.2-alpine` | To stand-ins: (1) **flight-service** – svarer på den ene GraphQL-query `FlightClient` i booking-service stiller (`flight(id) { ... seat(seatNumber) { ... } }`): fly 1 = SK1501, alle sæder ledige til 1299,00 DKK, det ønskede sædenummer ekkoes tilbage; alle andre fly-id'er giver `flight: null`. (2) **Keycloaks JWKS-endpoint** (`/realms/airport/protocol/openid-connect/certs`) med den offentlige halvdel af testnøglen. |
| `booking-service` | `airport/booking-service:local` | Det rigtige image. `FLIGHT_SERVICE_URL=http://wiremock:8080/api/flights/graphql`. |
| `payment-service` | `airport/payment-service:local` | Det rigtige image. |

Begge services får `OIDC_ISSUER_URI=http://keycloak.test/realms/airport` og
`JWK_SET_URI=http://wiremock:8080/realms/airport/protocol/openid-connect/certs`. `TestKeys` genererer ét RSA-nøglepar
pr. test-JVM og udsteder RS256-tokens med samme claims som Keycloak (`iss`, `preferred_username`, `email`,
`realm_access.roles`), så services'ne validerer signatur, issuer og roller med **uændret produktionskode**
(`KeycloakRoleConverter`, `@PreAuthorize`). Kun nøglen og issuer-strengen er testens egne.

Klasser: `CooperationTest` (scenarierne), `Stack` (containere + WireMock-stubs), `TestKeys` (nøglepar, tokens, JWKS),
`GraphQlClient` (GraphQL over `java.net.http`), `EventCollector` (kø med `#`-binding, `awaitEvent`).

## Sådan køres den

Images skal være bygget først (fra repo-roden), og Docker skal køre:

```bash
docker compose build                 # bygger bl.a. airport/booking-service:local og airport/payment-service:local
cd system-tests && mvn verify        # kører testen
cd system-tests && mvn -Pci verify   # som CI-pipelinen: test + Checkstyle + SpotBugs/find-sec-bugs (../config)
```

Services'nes egen log (stdout fra de to containere) er skjult som standard; vis den med
`mvn verify -Dcontainer.log.level=INFO`. Fejler opstarten af en container, printer Testcontainers selv dens log.

`-Pci` er den samme profil som i de fem service-poms; eneste forskel er `includeTests=true` for SpotBugs, fordi
modulet ingen main-kode har (ellers ville SpotBugs ikke analysere noget).

## Køretid

Målt på en laptop med images allerede hentet: **ca. 50 s** for hele `mvn -Pci verify` (compile + Checkstyle + test
+ SpotBugs), heraf ca. 32 s i selve testklassen: infrastruktur klar efter ~8 s, begge services `readiness`-klar
~15 s senere (parallelt), de fem scenarier ~8 s tilsammen. Første kørsel henter desuden WireMock-imaget (~100 MB).
Opstartstimeout for services'ne er 3 min.

## Hvorfor WireMock-JWKS og ikke testcontainers-keycloak?

Alternativet var at starte en rigtig Keycloak med `dasniko/testcontainers-keycloak`, importere
`k8s/keycloak/realm-airport.json` og hente tokens med password-grant for `anna`/`ops`. Det blev fravalgt, fordi:

* **Opstartstid.** Keycloak bruger 20–40 s på at starte og importere realm'et – lige så meget som resten af stakken
  tilsammen. JWKS-stubben svarer med det samme.
* **Deterministiske nøgler og tokens.** Testen udsteder selv tokens med præcis de claims, den vil teste (roller,
  e-mail, issuer, udløb) uden et HTTP-kald pr. token, og kan f.eks. lave et token med en forkert rolle eller issuer
  på én linje.
* **Det, der testes, er uændret.** Services'ne validerer tokenet ad præcis samme vej som i produktion (JWKS hentet fra
  `JWK_SET_URI`, `iss` sammenlignet med `OIDC_ISSUER_URI`, roller fra `realm_access.roles`). Keycloak selv (realm-fil,
  brugere, PKCE-client) er dækket af login-testen mod compose/kind i DP-01/DP-05 og af `scripts/e2e-smoke.sh`.

Skal Keycloak alligevel med, er ændringen lokal: erstat `wiremock`-JWKS-stubben i `Stack` med en
`KeycloakContainer().withRealmImportFile(...)` på samme netværk, sæt `OIDC_ISSUER_URI`/`JWK_SET_URI` til
`http://keycloak:8080/realms/airport[...]`, og lad `TestKeys` hente tokens via `/protocol/openid-connect/token`.

## Faldgruber fundet undervejs

* **h2c-upgrade mod WireMock.** `FlightClient` i booking-service bruger Spring `RestClient` oven på JDK'ens
  `HttpClient`, som sender første request med `Upgrade: h2c` og chunked body. WireMocks Jetty lukker den forbindelse
  (`EOF reached while reading` → `UPSTREAM_UNAVAILABLE`). Den rigtige flight-service (Tomcat) er ikke ramt. WireMock
  startes derfor med `--disable-http2-plain` (se kommentaren i `Stack`).
* **Én Postgres, to databaser.** `init.sql` opretter begge databaser med hver sin ejer; Postgres 15+ giver
  databasens ejer `CREATE` på `public`, så Flyway virker uden ekstra `GRANT`.
* **Exchange-deklaration.** Testens `EventCollector` deklarerer `airport.events` som durable topic – samme
  parametre som services'nes `RabbitConfig` – så det er ligegyldigt, hvem der kommer først.
