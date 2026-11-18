# shop-service

Butikker og indendørs navigation i lufthavnen: butikslisten med åbningstider, gangnettet mellem indgange, security,
gates og butikker, og korteste rute mellem to punkter med Dijkstra. Alt kan læses uden login, mens oprettelse,
ændring og sletning af butikker kræver rollen OPERATIONS. Servicen kender hverken fly eller bookinger, publicerer
ingen events og har ingen mutationer på gangnettet.

| | |
|---|---|
| Ansvar | Butikker, gangnet og rutevejledning |
| Port | compose `8085` · Kubernetes via Ingress `/api/shops` |
| API | GraphQL `/api/shops/graphql` (queries offentlige, mutationer OPERATIONS) · REST kun Actuator |
| Database | `shop_db` (PostgreSQL 16, Flyway `V1`–`V5`) |
| Events ud | ingen |
| Events ind | `flight.gate.changed` (kø `shop-service.flight-events`, binding `flight.#`) – logges kun |
| Image | `airport/shop-service:local` |

## Ansvar og data

| Tabel | Indhold | Migration |
|-------|---------|-----------|
| `shop` | Butikkerne, og servicen er kilde til sandhed for dem. `node_id` (valgfri, `ON DELETE SET NULL`) placerer butikken i gangnettet, og `category` er låst med en `CHECK`. `deleted_at` er tombstonen ved sletning, og `idempotency_key` (`UNIQUE`) er nøglen fra `createShop` | `V1`, `V4`, `V5` |
| `nav_node` | Knudepunkter med `terminal`, `floor`, `x`/`y` (kort på 1000×600) og `type` (`SHOP`, `GATE`, `SECURITY`, `ENTRANCE`, `JUNCTION`, `ELEVATOR`) | `V1` |
| `nav_edge` | Gangforbindelser med `distance_m` (> 0) og `accessible` (`false` = trappe). Hver kant gemmes én gang (`UNIQUE (from_node_id, to_node_id)`, ingen kant til sig selv) og bruges i begge retninger | `V1` |
| `processed_event` | `eventId` på forbrugte events (idempotens) | `V1` |
| `outbox_event` | Transactional outbox. Tabellen og `OutboxRelay` kører, men ingen forretningskode publicerer | `V3` |

- **Seed-data** (`V2__seed.sql`): terminalerne T1 og T2 er forbundet af `Gangbro T1-T2`. Der er 46 noder på etage 0
  og 1, 53 kanter (heraf 2 trapper: `Junction T1 South`/`Junction T2 South` → `Etage 1 T1`/`Etage 1 T2`), 10 gates
  (A3, A12, A14, A20, B7, B12, B15, C5, C10, C21) og 17 butikker (8 i T1 og 9 i T2), som alle har en node.
- **Ingen snapshots.** Gates er faste noder i gangnettet, og servicen ved ikke, hvilket fly der holder ved hvilken gate.
  `flight.#` forbruges kun for at logge gateskift.
- **Bevidst ikke her:** gangnettet (noder og kanter) ændres med en ny Flyway-migration og ikke via API'et. Brugere og
  roller ligger i Keycloak; servicen validerer kun tokenet.

## API

Alle queries er offentlige (ingen `@PreAuthorize`). Mutationerne kræver OPERATIONS
(`@PreAuthorize("hasRole('OPERATIONS')")` i `graphql/ShopController.java`):

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `shops(filter: ShopFilter)` | query | Alle | Butikker sorteret efter terminal og navn. Kan filtreres på `terminal` (uden forskel på store og små bogstaver), `category` og `openNow` |
| `shop(id)` | query | Alle | Én butik eller `null` |
| `searchShops(text)` | query | Alle | Søger i navn, kategori, beskrivelse og zone (`ShopRepository.search`), sorteret efter navn. `text` må ikke være blank |
| `navNodes(terminal, floor)` | query | Alle | Noder sorteret efter terminal, etage og navn. `NavNode.shops` giver butikkerne på noden |
| `navEdges(terminal)` | query | Alle | Kanter med begge noder, så en klient kan tegne kortet. Med `terminal` kommer kanter, hvor mindst én ende ligger i terminalen |
| `route(fromNodeId, toNodeId, accessibleOnly = false)` | query | Alle | Korteste rute med trin, `totalDistanceM`, `estimatedMinutes` og `shopsAlongRoute` |
| `createShop(input, idempotencyKey)` | mutation | OPERATIONS | Opretter en butik og returnerer den; samme `idempotencyKey` igen giver den samme butik (ingen dublet) |
| `updateShop(id, input)` | mutation | OPERATIONS | Overskriver hele butikken. Et udeladt `nodeId` fjerner butikkens node |
| `deleteShop(id)` | mutation | OPERATIONS | Soft delete: sætter `deleted_at` (tombstone) og returnerer `true`; rækken bliver i `shop` |

Frontendens side *Butikker* ([`shops.js`](../frontend/js/pages/shops.js)) kræver ikke login og bruger kun queries:
`navNodes`, `navEdges` og `shops` ved indlæsning, `searchShops` eller `shops(filter)` til søgning og `route` til
*Find rute*.

Fejlkoder i `errors[].extensions.code` (`GraphQlExceptionResolver`):

| Kode | Hvornår |
|------|---------|
| `NOT_FOUND` | Ukendt node (`route`, `input.nodeId`) eller butik (`updateShop`, `deleteShop`). `shop(id)` giver `null` i stedet |
| `VALIDATION_ERROR` | Bean Validation fejler på `ShopInput`, `searchShops(text)` eller `createShop(idempotencyKey)` over 64 tegn (feltnavnet står i beskeden) |
| `ROUTE_NOT_FOUND` | Der er ingen vej mellem noderne, fx kun via trapper med `accessibleOnly`. Sker ikke med seed-data, hvor alle noder kan nås via elevatorerne |
| `CONFLICT` | `createShop` med en `idempotencyKey`, hvis butik siden er slettet (`ShopService`), eller en databasebegrænsning brydes (`DataIntegrityViolationException`), fx når to samtidige `createShop` bruger samme nøgle |
| `UNAUTHORIZED` / `FORBIDDEN` | Mutation uden token / med en anden rolle end OPERATIONS. Svaret er HTTP 200 med GraphQL-fejlen |
| `INTERNAL_ERROR` | Uventet fejl, logges som `Unhandled error in data fetcher …` |

Et ugyldigt, udløbet eller fremmed token afvises med HTTP 401 og `WWW-Authenticate: Bearer error="invalid_token"`, før
GraphQL kører, også på offentlige queries. Fælles fejlmodel og roller står i
[GraphQL-fejl](../docs/architecture.md#graphql-fejl), [Operation × rolle](../docs/architecture.md#operation--rolle) og
[Fejl og frontendens reaktion](../docs/architecture.md#fejl-og-frontendens-reaktion).

**Eksempler** mod compose-stakken. Det forventede output står som kommentar og følger af seed-data. Node-id'erne kommer
fra seed-rækkefølgen og gælder for en frisk database (`docker compose down -v` nulstiller). Queries kræver intet token:

```bash
GQL=http://localhost:8085/api/shops/graphql

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ navNodes(terminal: \"T2\") { id name type } }"}' \
  | jq -c '.data.navNodes[] | select(.name == "Security T2" or .name == "Gate B12")'
# {"id":"31","name":"Gate B12","type":"GATE"}
# {"id":"24","name":"Security T2","type":"SECURITY"}

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ route(fromNodeId: 24, toNodeId: 31) { totalDistanceM estimatedMinutes steps { instruction } shopsAlongRoute { name } } }"}' \
  | jq -c '.data.route | {totalDistanceM, estimatedMinutes, steps: [.steps[].instruction], shopsAlongRoute: [.shopsAlongRoute[].name]}'
# {"totalDistanceM":350,"estimatedMinutes":5,"steps":["Start ved Security T2","Gå 70 m til Junction T2 Central",
#  "Gå 60 m til Duty Free T2","Gå 60 m til Junction T2 North","Gå 90 m til Junction Pier B",
#  "Du er fremme ved Gate B12"],"shopsAlongRoute":["Duty Free Copenhagen T2"]}

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ searchShops(text: \"kaffe\") { name terminal } }"}' | jq -c '.data.searchShops'
# [{"name":"7-Eleven","terminal":"T1"},{"name":"Joe & The Juice","terminal":"T1"},
#  {"name":"Lagkagehuset","terminal":"T2"},{"name":"Starbucks","terminal":"T2"}]

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ route(fromNodeId: 24, toNodeId: 999) { totalDistanceM } }"}' \
  | jq -c '.errors[0] | {message, code: .extensions.code}'
# {"message":"NavNode not found: 999","code":"NOT_FOUND"}
```

Ruten går via `Duty Free T2` (60 + 60 m), fordi den direkte kant `Junction T2 Central` → `Junction T2 North` er 130 m.
Der er ingen trapper på vejen, så `accessibleOnly: true` giver samme rute. Fra `Junction T1 Central` (4) til
`SAS Lounge` (19) gør det en forskel: uden `accessibleOnly` er ruten 205 m med "Tag trappen til etage 1", og med
`accessibleOnly: true` er den 225 m via `Elevator T1` med "Tag elevatoren til etage 1".

Mutationer med et OPERATIONS-token fra Keycloak (`ops`/`ops`). Butikken oprettes og slettes igen, så seed-data er
uændret bagefter:

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8180/realms/airport/protocol/openid-connect/token | jq -r .access_token)

curl -s $GQL -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createShop(input: { name: \"Pop-up Kiosk\", category: RETAIL, terminal: \"t2\", zone: \"Pier B\", floor: 0, openingHours: \"08:00-20:00\", nodeId: 28 }) { id terminal node { name } } }"}' \
  | tee /dev/stderr | jq -r .data.createShop.id > /tmp/shop-id
# {"data":{"createShop":{"id":"18","terminal":"T2","node":{"name":"Junction Pier B"}}}}   (id 18 på en frisk database)

curl -s $GQL -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "{\"query\":\"mutation { deleteShop(id: $(cat /tmp/shop-id)) }\"}"
# {"data":{"deleteShop":true}}

curl -s $GQL -H 'Content-Type: application/json' -d '{"query":"mutation { deleteShop(id: 1) }"}' \
  | jq -c '.errors[0] | {message, code: .extensions.code}'
# {"message":"Authentication required: send a Bearer token","code":"UNAUTHORIZED"}
# Samme kald med et token for anna/anna (PASSENGER):
# {"message":"Your role does not allow this operation","code":"FORBIDDEN"}

curl -s -o /dev/null -w '%{http_code}\n' $GQL -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer not.a.jwt' -d '{"query":"{ shops { id } }"}'
# 401
```

## Forretningsregler

- **Adgang:** queries kræver intet token. `createShop`, `updateShop` og `deleteShop` kræver realm-rollen OPERATIONS
  i tokenet; uden token giver det `UNAUTHORIZED` og med en anden rolle `FORBIDDEN`, før mutationen kører.
- **`ShopInput`:** `name` 1–100 tegn, `category` påkrævet, `terminal` 1–5, `zone` 1–50, `floor` −2…10 og
  `description` højst 2000 tegn. `openingHours` skal være `HH:MM-HH:MM` eller `24/7`. Brud giver `VALIDATION_ERROR`.
  Mønstret tjekker kun formatet, så fx `25:00-26:00` gemmes, men giver altid `openNow = false`.
- **Normalisering:** `terminal` gemmes med store bogstaver, tekstfelterne trimmes, og en blank `description` gemmes
  som `null`. Et `nodeId`, der ikke findes, giver `NOT_FOUND`.
- **`updateShop`** overskriver alle felter, så udeladte valgfrie felter (`description`, `nodeId`) bliver `null`.
- **`deleteShop`** er en soft delete: rækken får et tidsstempel i `deleted_at` (tombstone, `V4__shop_tombstone.sql`) og
  bliver i databasen. `@SQLRestriction("deleted_at IS NULL")` på `Shop` skjuler den for alle queries, så `shop(id)`
  giver `null`, butikken er væk fra `shops`, `searchShops`, `NavNode.shops` og `shopsAlongRoute`, og et nyt
  `updateShop`/`deleteShop` giver `NOT_FOUND`. Se [Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot-immutable-data).
- **`createShop` med `idempotencyKey`** (valgfri, højst 64 tegn ellers `VALIDATION_ERROR`, `UNIQUE`-kolonne,
  `V5__shop_idempotency.sql`): et gentaget kald med samme nøgle returnerer den butik, første kald oprettede. Er den
  butik siden slettet, er nøglen stadig optaget → `CONFLICT`. To samtidige kald med samme nøgle afgøres af
  `UNIQUE`-constrainten, og taberen får `CONFLICT`. Uden nøgle (eller med en blank) opretter hvert kald en ny butik.
  Se [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages).
- **Søgning:** `searchShops` trimmer teksten og matcher med `LIKE` uden forskel på store og små bogstaver.
  `shops(filter)` filtrerer `terminal` og `category` i databasen og `openNow` bagefter ud fra klokken i `APP_TIMEZONE`.
- **Åbningstider** (`OpeningHours`): åbningsminuttet tæller med, lukkeminuttet gør ikke. `22:00-04:00` går over
  midnat, `00:00-00:00` og `24/7` betyder hele døgnet, og en tid, der ikke kan parses, giver `openNow = false`.
  `openNow` gemmes ikke, men beregnes ved hver læsning.
- **Rute** (`RouteService` + `Dijkstra`): ukendt `fromNodeId` eller `toNodeId` giver `NOT_FOUND`. Dijkstra går over
  alle kanter i begge retninger, `accessibleOnly` springer kanter med `accessible = false` over, og findes der ingen
  vej, giver det `ROUTE_NOT_FOUND`. Instruktionerne er "Start ved …", "Gå N m til …", "Tag elevatoren/trappen til
  etage N" (ved etageskift, afhængigt af kanten) og til sidst "Du er fremme ved …". Hvert trins `distance` er kantens
  længde (0 for første trin).
- **Tid og butikker undervejs:** `estimatedMinutes` er afstand ÷ 80 m/min rundet op (mindst 1). `shopsAlongRoute` er
  butikkerne på rutens noder i rutens rækkefølge. Er start og mål samme node, giver det ét trin, "Du er allerede ved
  …", med 0 m, 0 minutter og butikkerne på noden.

## Events

**Publicerer** (via transactional outbox): ingen. `EventPublisher`, `outbox_event` og `OutboxRelay` er med, så
servicen følger samme skabelon som de andre. Relayet poller hvert 500 ms, men ingen forretningskode kalder `publish`.

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `shop-service.flight-events` | `flight.#` på `airport.events` | `flight.gate.changed` | `FlightEventHandler` logger `Gate change for flight {flightNumber} : {oldGate} -> {newGate} (eventId …)`. Andre `flight.*` ignoreres, men registreres i `processed_event` |

Levering: `processed_event` fjerner duplikater, og efter 3 forsøg (500 ms, ×2, højst 3 s) går beskeden via
`airport.events.dlx` (routing key `shop-service`) til `shop-service.dlq`. Se
[events.md](../docs/events.md#leveringsgarantier).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `APP_TIMEZONE` | `Europe/Copenhagen` | Tidszone for `openNow` |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Pollinterval for `OutboxRelay` |
| `GRAPHQL_PATH` / `SERVER_PORT` | `/api/shops/graphql` / `8080` | Endpoint og port |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5437/shop_db` / `shop` / `shop` | Servicens database |
| `OIDC_ISSUER_URI` | `http://localhost:8180/realms/airport` | Den `iss`, et token skal have. Compose bruger samme værdi, kind `http://localhost:8090/auth/realms/airport` |
| `JWK_SET_URI` | `http://localhost:8180/realms/airport/protocol/openid-connect/certs` | Hvor Keycloaks signeringsnøgler hentes. Compose `http://keycloak:8080/realms/airport/protocol/openid-connect/certs`, kind `http://keycloak:8080/auth/realms/airport/protocol/openid-connect/certs` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:8080` | Kommasepareret. Læses som `app.cors.allowed-origins` af `SecurityConfig` og gælder alle stier; preflight kræver intet token. Compose `http://localhost:8080,http://127.0.0.1:8080`, Kubernetes `http://airport.local,http://localhost,http://localhost:8090` |
| `RABBITMQ_*`, `SPRING_PROFILES_ACTIVE` | se rod-README | Fælles, se [Konfiguration](../README.md#konfiguration). `prod` slår GraphiQL fra og giver JSON-logs |

Issuer og JWKS er forklaret i [Konfiguration: issuer og JWKS](../docs/architecture.md#konfiguration-issuer-og-jwks).
Faste værdier (ingen variabel): exchange og køer, retry (3 forsøg), outbox-batch 100, confirm-timeout 5000 ms og
oprydning af sendte outbox-rækker efter 7 dage (jobbet kører hver time).

## Kør lokalt

```bash
docker compose up --build                  # hele stakken inkl. Keycloak på http://localhost:8180
docker compose up --build shop-service     # kun servicen med shop-db og rabbitmq: queries virker uden login
docker compose up -d keycloak              # nødvendig for tokens til mutationerne (ops/ops = OPERATIONS)
# GraphQL: http://localhost:8085/api/shops/graphql, GraphiQL: http://localhost:8085/graphiql?path=/api/shops/graphql

# uden Docker for servicen: Postgres (5437), RabbitMQ og Keycloak (8180) fra compose
docker compose up -d shop-db rabbitmq keycloak
cd shop-service && mvn spring-boot:run     # http://localhost:8080/api/shops/graphql, GraphiQL /graphiql

docker compose exec shop-db psql -U shop -d shop_db -c 'select count(*) from nav_edge'   # 53
```

Defaults for `OIDC_ISSUER_URI` og `JWK_SET_URI` peger på compose-Keycloak på `localhost:8180`, så
`mvn spring-boot:run` accepterer de samme tokens som compose-stakken. `mvn spring-boot:run` bruger port 8080 ligesom
compose-frontenden; skal begge køre, så start servicen med fx `SERVER_PORT=8095`. I Kubernetes er adressen
`/api/shops/graphql` gennem Ingress (`prod`-profil, ingen GraphiQL), og tokens hentes fra Keycloak på `/auth`.

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `DijkstraTest` | unit (6) | Flere hop slår en lang direkte kant, kanter virker begge veje, `accessibleOnly` tager elevatoren frem for trappen, en node, der ikke kan nås, eller en ukendt node giver ingen rute, samme node giver 0 m, og afstand ≤ 0 afvises |
| `OpeningHoursTest` | unit (2 `@Test` + 2 `@ParameterizedTest` med 13 tilfælde = 15) | Almindelig tid (grænser: åbning med, lukning ikke med), tid over midnat, `24/7`/`00:00-00:00` og ugyldigt input giver lukket |
| `ShopServiceIntegrationTest` | Testcontainers over HTTP (14) | Flyway-seed og filtre på `navNodes`/`navEdges`, rute Security T2 → Gate B12 (350 m, 5 min, Duty Free undervejs), trappe (205 m) mod elevator (225 m), rute mellem terminalerne og samme node, `NOT_FOUND`, søgning og filtre (`openNow` med 7-Eleven 24/7), opret/læs/opdatér/slet med OPERATIONS-token, `VALIDATION_ERROR`, `flight.gate.changed` forbrugt én gang, outbox (publish uden transaktion, rollback, relay-retry og `outbox.pending` = 0), tombstone (slettet butik er væk fra `shop`, `shops`, `searchShops`, `NavNode.shops` og `shopsAlongRoute`, `updateShop` giver `NOT_FOUND`, men rækken findes med `deleted_at`) og `idempotencyKey` (samme nøgle → én butik, uden nøgle en ny, nøglen fra en slettet butik → `CONFLICT`) |
| `SecurityIntegrationTest` | Testcontainers over HTTP (6) | Offentlige queries og readiness uden token, `UNAUTHORIZED` på `createShop`/`deleteShop` uden token, `FORBIDDEN` for PASSENGER, OPERATIONS opretter og sletter, udløbet/fremmed/ugyldigt token → HTTP 401 med `invalid_token`, CORS-preflight fra `http://localhost:8080` |

`TestTokens` er en hjælpeklasse (ingen tests), der laver RS256-JWT'er med samme claims som Keycloak og en
`JwtDecoder`, der stoler på testnøglen. Testene kører derfor gennem den rigtige filterkæde uden en kørende Keycloak.

```bash
cd shop-service && mvn -B -ntp -Pci verify  # samme kommando som CI-jobbet backend · shop-service
```

Kræver Docker (Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`). Der er 41 tests i
alt. CI ([ci.yml](../.github/workflows/ci.yml)) kører `mvn -B -ntp -Pci verify` (tests + Checkstyle + SpotBugs) og uploader
rapporterne som artifact `reports-shop-service`. Mod en kørende stak tester [`scripts/e2e-smoke.sh`](../scripts/e2e-smoke.sh) også
Flow D (rute Security T2 → Gate B12 og `searchShops`).

## Drift

- **Health:** liveness på `/actuator/health/liveness`, readiness på `/actuator/health/readiness` (`readinessState`,
  `db`, `rabbit`, `show-details: always`). Health og `/actuator/info` kræver intet token. Compose kalder readiness med
  `wget` hvert 10 s (`start_period` 40 s). Keycloak er hverken i readiness eller i `depends_on`: nøglerne hentes fra
  `JWK_SET_URI`, når det første token skal valideres.
- **Kubernetes** (`k8s/services/shop-service.yaml`): ConfigMap `shop-service-config` (`SPRING_PROFILES_ACTIVE=prod`,
  `DB_URL`, `RABBITMQ_HOST`/`RABBITMQ_PORT`, `CORS_ALLOWED_ORIGINS`, `GRAPHQL_PATH`, `OIDC_ISSUER_URI`,
  `JWK_SET_URI`) og Secret `shop-service-secret` (DB- og RabbitMQ-credentials, dev-værdier). Deployment med
  2 replicas, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`, requests `250m`/`512Mi` og limits `1000m`/`1Gi`.
- **Image** (`Dockerfile`): multi-stage, Maven-build på `maven:3.9-eclipse-temurin-21` og runtime på
  `eclipse-temurin:21-jre-alpine`, hvor processen kører som brugeren `app`.
- **Probes:** startup på liveness-endpointet (hvert 5 s, op til 30 fejl), liveness efter 40 s (hvert 10 s, 6 fejl) og
  readiness efter 20 s (hvert 10 s, 12 fejl). ClusterIP-servicen lytter på 8080, og Ingress'en router `/api/shops`
  hertil. `/graphiql` og `/actuator` er ikke eksponeret gennem Ingress.
- **Flere replicas:** queries holder ingen tilstand i podden, og tokens valideres lokalt i hver pod (stateless, ingen
  sessioner). De to pods er konkurrerende consumers på `shop-service.flight-events`, så hvert event behandles af én
  pod, og `processed_event` fanger genleveringer. `OutboxRelay` kører i begge pods, men en Postgres advisory lock
  (`pg_try_advisory_xact_lock`) gør kun én aktiv ad gangen.
- **Metrics:** `/actuator/metrics` kræver et OPERATIONS-token, fx
  `curl -s -H "Authorization: Bearer $TOKEN" localhost:8085/actuator/metrics/outbox.pending`. Gaugen `outbox.pending`
  (usendte outbox-rækker) er altid 0 her.
- **Loglinjer:** `Created shop … (…)`, `Idempotent replay: shop … (…) was already created with this key`,
  `Updated shop … (…)`, `Deleted shop … (…) - tombstone kept`,
  `Received event flight.gate.changed eventId=…`, `Gate change for flight … : … -> … (eventId …)`,
  `Skipping already processed event …` og `Unhandled error in data fetcher …` (ERROR). Consumeren lægger `eventId`
  og `eventType` i MDC. Med `prod` er loggen JSON (logstash).

## Designvalg

| Valg | Begrundelse | Uddybet i |
|------|-------------|-----------|
| `nav_edge` gemmes én gang og bruges begge veje. `Dijkstra` er en ren klasse uden Spring | Halvt så mange rækker, og algoritmen kan unit-testes isoleret | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed), [Flow D](../docs/architecture.md#flow-d--navigation) |
| `accessibleOnly` udelader kanter med `accessible = false` (trapper), og etageskift beskrives ud fra kanten | Samme graf giver både den korteste og den tilgængelige rute | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Danske ruteinstruktioner og gangtid ud fra 80 m/min | Tekstbaseret rute, som spec'en kræver | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Åbningstider som tekst (`HH:MM-HH:MM`, også over midnat, eller `24/7`), evalueret i `APP_TIMEZONE` | Enkelt format i seed-data | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Ekstra query `navEdges(terminal)` | Frontenden tegner gangnettet på SVG-kortet, og trapper stiples | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed) |
| Læsning er offentlig, og mutationerne kræver OPERATIONS med `@PreAuthorize` pr. operation. GraphQL-endpointet er åbent på HTTP-niveau | Passagerer finder vej uden login, og samme endpoint bærer både offentlige queries og beskyttede mutationer | [Hvad er offentligt?](../docs/architecture.md#hvad-er-offentligt), [Operation × rolle](../docs/architecture.md#operation--rolle) |
| `deleteShop` er en soft delete (`deleted_at` + `@SQLRestriction`) i stedet for en fysisk `DELETE` | Kan fortrydes med en `UPDATE`, historikken bevares, og et id i en log peger aldrig ud i ingenting | [Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot-immutable-data) |
| `createShop` tager en valgfri `idempotencyKey` fra klienten | Et gentaget kald giver ikke en dublet, og klienter uden nøgle virker som før | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |
| Lytter på `flight.#`, men logger kun gateskift | Ingen tilstand i servicen afhænger af fly, og gates er faste noder | [events.md](../docs/events.md#shop-service) |
| Outbox-infrastrukturen er med, selv om servicen ikke publicerer | Samme skabelon som de fire andre services | [Teknologi pr. service](../docs/architecture.md#teknologi-pr-service), [Messaging](../docs/architecture.md#messaging) |

## Se også

- [docs/architecture.md](../docs/architecture.md): [Flow D – Navigation](../docs/architecture.md#flow-d--navigation),
  [Sikkerhed (login og roller)](../docs/architecture.md#sikkerhed-login-og-roller),
  [Messaging](../docs/architecture.md#messaging), [GraphQL-fejl](../docs/architecture.md#graphql-fejl)
- [docs/events.md](../docs/events.md#shop-service):
  [`flight.gate.changed`](../docs/events.md#flightgatechanged),
  [Køer, bindings og dead-letter](../docs/events.md#køer-bindings-og-dead-letter)
- [k8s/README.md](../k8s/README.md): [Keycloak (login)](../k8s/README.md#keycloak-login),
  [Minikube](../k8s/README.md#minikube), [Alternativ: kind](../k8s/README.md#alternativ-kind)
- [Rod-README](../README.md): [Login og roller](../README.md#login-og-roller),
  [Konfiguration](../README.md#konfiguration), [Tests](../README.md#tests),
  [CI (GitHub Actions)](../README.md#ci-github-actions)
- [frontend/js/pages/shops.js](../frontend/js/pages/shops.js) (kort, rutevalg og søgning) og
  [scripts/e2e-smoke.sh](../scripts/e2e-smoke.sh)
