# baggage-service

Bagage bundet til en booking: registrering, bagagetag, statusskift gennem lufthavnen og events til resten af
systemet. Servicen er den eneste i systemet med **både** et GraphQL-API og et versioneret REST-API, så begge
API-stilarter er repræsenteret oven på den samme forretningslogik.

| | |
|---|---|
| Port (compose) | 8084 |
| GraphQL | `/api/baggage/graphql` (`GRAPHQL_PATH`) |
| REST | `/api/baggage/v1` |
| OpenAPI | `/v3/api-docs`, `/v3/api-docs.yaml`, Swagger UI på `/swagger-ui.html` |
| Database | `baggage_db` (Flyway) |
| Events ud | `baggage.registered`, `baggage.status.changed` |
| Events ind | `booking.created`, `booking.confirmed`, `booking.cancelled`, `flight.cancelled` |

## Forretningsregler

- Bagage kan kun registreres på en booking, servicen kender fra et `booking.*`-event, og som er `CONFIRMED`
  eller `CHECKED_IN` (ellers `INVALID_STATE`/409).
- Højst 3 `CHECKED`-stykker pr. booking (`BAGGAGE_LIMIT_EXCEEDED`/422); `CABIN` og `SPECIAL` er ikke begrænset.
- 0 < vægt ≤ 32 kg pr. stykke (`VALIDATION_ERROR`/422).
- Tag'et har formen `BAG-XXXXXXXX` og genereres af servicen.
- Aflyses flyet, sendes alle stykker der ikke er `ARRIVED`/`LOST` tilbage til `RETURN_DESK`.

## REST-API v1

| Metode  | Sti                                            | Rolle                | Svar               |
|---------|------------------------------------------------|----------------------|--------------------|
| `POST`  | `/api/baggage/v1/baggage`                      | PASSENGER/OPERATIONS | `201` + `Location` |
| `GET`   | `/api/baggage/v1/baggage/{tagNumber}`          | offentlig            | `200` / `404`      |
| `GET`   | `/api/baggage/v1/bookings/{reference}/baggage` | PASSENGER/OPERATIONS | `200` (liste)      |
| `PATCH` | `/api/baggage/v1/baggage/{tagNumber}/status`   | OPERATIONS           | `200`              |

Rollerne håndhæves på URL-niveau i `config/SecurityConfig` og svarer præcis til `@PreAuthorize`-reglerne på
GraphQL-operationerne. `Location` er en relativ URI med vilje: bag Ingress'en er det scheme/host/port, podden
ser, ikke det klienten brugte.

Fejl er RFC 9457 problem details (`application/problem+json`) med et ekstra felt `code`, der bruger samme
vokabular som GraphQL's `errors[].extensions.code` – se tabellen i [rod-README'en](../README.md#apier-graphql-og-rest).
Mappingen står ét sted: `rest/RestExceptionHandler` (og `config/ProblemAuthHandlers` for 401/403, som opstår i
sikkerhedsfilteret før controlleren).

## Versionering

Versionen er en del af stien (`/api/baggage/v1`). Et brud (felt fjernet, betydning ændret, ny påkrævet
parameter) bliver til `/api/baggage/v2` **ved siden af** v1, så eksisterende klienter kan blive på v1 indtil de
er flyttet; tilføjelser (nyt valgfrit felt, ny endpoint) sker i v1. De tre API-typer i systemet versioneres
forskelligt af hensyn til deres klienter – se afsnittet "API-versionering" i
[docs/architecture.md](../docs/architecture.md).

## Kør og test

```bash
# hele stakken
docker compose up --build baggage-service

# kun denne service (kræver postgres + rabbitmq fra compose)
mvn spring-boot:run

# tests (Testcontainers starter Postgres + RabbitMQ)
mvn -Pci verify
```

`mvn -Pci verify` kører unit-tests, integrationstests mod rigtige containere, REST-tests gennem hele
sikkerhedskæden (`BaggageRestIntegrationTest`) samt Checkstyle og SpotBugs.
