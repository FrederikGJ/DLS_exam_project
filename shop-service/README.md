# shop-service

Butikker og indendørs navigation i lufthavnen: butikslisten med åbningstider, gangnettet mellem indgange, security,
gates og butikker, korteste rute med Dijkstra og *Spørg om vej*, hvor en lokal sprogmodel (Ollama) tolker et
spørgsmål i fritekst. Servicen kender hverken fly eller bookinger, publicerer ingen events og har ingen mutationer på
gangnettet. Sprogmodellen er valgfri og vælger aldrig selv en rute.

| | |
|---|---|
| Ansvar | Butikker, gangnet og rutevejledning (også med AI) |
| Port | compose `8085` · Kubernetes via Ingress `/api/shops` |
| API | GraphQL `/api/shops/graphql` |
| Database | `shop_db` (PostgreSQL 16, Flyway `V1`–`V6`) |
| Events ud | ingen |
| Events ind | `flight.gate.changed` (kø `shop-service.flight-events`, binding `flight.#`) – logges kun |
| Image | `airport/shop-service:local` (valgfrit med `airport/ollama:local`, se `../ollama/Dockerfile`) |

## Ansvar og data

| Tabel | Indhold | Migration |
|-------|---------|-----------|
| `shop` | Butikkerne, og servicen er kilde til sandhed for dem. `node_id` (valgfri) placerer butikken i gangnettet, `deleted_at` er tombstonen, og `idempotency_key` er `UNIQUE` | `V1`, `V4`, `V5` |
| `nav_node` | Knudepunkter med `terminal`, `floor`, `x`/`y` (kort på 1000×600) og `type` (`SHOP`, `GATE`, `SECURITY`, `ENTRANCE`, `JUNCTION`, `ELEVATOR`) | `V1` |
| `nav_edge` | Gangforbindelser med `distance_m` og `accessible` (`false` = trappe). Hver kant gemmes én gang og bruges i begge retninger | `V1` |
| `processed_event` | `eventId` på forbrugte events (idempotens) | `V1` |
| `outbox_event` | Transactional outbox inkl. `traceparent`. Tabellen og `OutboxRelay` kører, men ingen forretningskode publicerer | `V3`, `V6` |

- **Seed-data** (`V2__seed.sql`): terminalerne T1 og T2 er forbundet af `Gangbro T1-T2`. Der er 46 noder på etage 0
  og 1, 53 kanter (heraf 2 trapper) og 17 butikker (8 i T1 og 9 i T2).
- **Ingen snapshots.** Gates er faste noder i gangnettet, og servicen ved ikke, hvilket fly der holder ved hvilken gate.
  `flight.#` forbruges kun for at logge gateskift.
- **Bevidst ikke her:** gangnettet ændres med en ny Flyway-migration og ikke via API'et. Ruter beregnes kun af
  `Dijkstra`, aldrig af sprogmodellen.

## API

Alle queries er offentlige (ingen `@PreAuthorize`). Mutationerne kræver OPERATIONS
(`graphql/ShopController.java`):

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|
| `shops(filter: ShopFilter)` | query | Alle | Aktive butikker sorteret efter terminal og navn. Kan filtreres på `terminal` (uden forskel på store og små bogstaver), `category` og `openNow` |
| `shop(id)` | query | Alle | Én butik eller `null` (også når den er slettet) |
| `searchShops(text)` | query | Alle | Søger i navn, kategori, beskrivelse og zone (`ShopRepository.search`). `text` må ikke være blank |
| `navNodes(terminal, floor)` | query | Alle | Noder sorteret efter terminal, etage og navn. `NavNode.shops` giver butikkerne på noden |
| `navEdges(terminal)` | query | Alle | Kanter med begge noder, så en klient kan tegne kortet |
| `route(fromNodeId, toNodeId, accessibleOnly = false)` | query | Alle | Korteste rute med trin, `totalDistanceM`, `estimatedMinutes` og `shopsAlongRoute` |
| `askRoute(question, fromNodeId, accessibleOnly = false)` | query | Alle | Tolker et spørgsmål og svarer med `AiRouteAnswer`: tolkning, butik, destination fra spørgsmålet, rute via butikken, `aiUsed`, `fallbackReason` og `model` |
| `createShop(input, idempotencyKey)` | mutation | OPERATIONS | Opretter en butik. Gentages kaldet med samme nøgle (højst 64 tegn), kommer samme butik tilbage |
| `updateShop(id, input)` | mutation | OPERATIONS | Overskriver hele butikken. Et udeladt `nodeId` fjerner butikkens node |
| `deleteShop(id)` | mutation | OPERATIONS | Tombstone: sætter `deleted_at`, og butikken forsvinder fra alle queries |

Fejlkoder i `errors[].extensions.code` (`GraphQlExceptionResolver`):

| Kode | Hvornår |
|------|---------|
| `NOT_FOUND` | Ukendt node (`route`, `askRoute`, `input.nodeId`) eller butik (`updateShop`, `deleteShop`) |
| `VALIDATION_ERROR` | Bean Validation fejler (feltnavn står i beskeden) |
| `ROUTE_NOT_FOUND` | Der er ingen vej, fx med `accessibleOnly` |
| `CONFLICT` | En `idempotencyKey`, hvis butik er slettet, eller to samtidige kald, der rammer `UNIQUE` |
| `UNAUTHORIZED` / `FORBIDDEN` | Mutation uden token / med en anden rolle end OPERATIONS |

Et ugyldigt eller udløbet token afvises med HTTP 401 før GraphQL, også på offentlige queries. Fælles fejlmodel og
roller står i [architecture.md](../docs/architecture.md#graphql-fejl) og
[Sikkerhed](../docs/architecture.md#sikkerhed-login-og-roller).

**Kørte eksempler** (kind med `k8s/overlays/demo`, gennem Ingress, 17-09-2026). Id'erne kommer fra seed-rækkefølgen:

```bash
GQL=http://localhost:8090/api/shops/graphql

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ navNodes(terminal: \"T2\") { id name type } }"}' \
  | jq -c '.data.navNodes[] | select(.name == "Security T2" or .name == "Gate B12")'
# {"id":"31","name":"Gate B12","type":"GATE"}
# {"id":"24","name":"Security T2","type":"SECURITY"}

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ route(fromNodeId: 24, toNodeId: 31, accessibleOnly: true) { totalDistanceM estimatedMinutes steps { instruction } shopsAlongRoute { name } } }"}' \
  | jq -c '.data.route | {totalDistanceM, estimatedMinutes, steps: [.steps[].instruction], shopsAlongRoute: [.shopsAlongRoute[].name]}'
# {"totalDistanceM":350,"estimatedMinutes":5,"steps":["Start ved Security T2","Gå 70 m til Junction T2 Central",
#  "Gå 60 m til Duty Free T2","Gå 60 m til Junction T2 North","Gå 90 m til Junction Pier B",
#  "Du er fremme ved Gate B12"],"shopsAlongRoute":["Duty Free Copenhagen T2"]}

curl -s $GQL -H 'Content-Type: application/json' \
  -d '{"query":"{ askRoute(question: \"Hvor finder jeg en kop kaffe på vej til gate B12?\", fromNodeId: 24) { interpretation aiUsed fallbackReason model shop { name } toNode { name } route { totalDistanceM estimatedMinutes } } }"}' \
  | jq -c .data.askRoute
# {"interpretation":"Sprogmodellen forstod \"coffee\": Starbucks på vej til Gate B12","aiUsed":true,
#  "fallbackReason":null,"model":"qwen2.5:1.5b","shop":{"name":"Starbucks"},"toNode":{"name":"Gate B12"},
#  "route":{"totalDistanceM":470,"estimatedMinutes":6}}                                  (1,6 s)
```

Der er ingen trapper mellem Security T2 og Gate B12, så `accessibleOnly` ændrer ikke ruten. Fra
`Junction T1 Central` (4) til `SAS Lounge` (19) gør det en forskel: uden `accessibleOnly` er ruten 205 m med "Tag
trappen til etage 1", og med `accessibleOnly: true` er den 225 m via `Elevator T1` med "Tag elevatoren til etage 1".
Da samme `askRoute` blev stillet som første spørgsmål efter over 5 minutter uden kald, kom svaret efter 15 s med
`"aiUsed":false`, `"interpretation":"Nøgleordssøgning på \"kaffe\": Starbucks på vej til Gate B12"` og
`"fallbackReason":"Ollama svarede ikke: Ollama is not reachable at http://ollama:11434 (HttpTimeoutException:
Request cancelled)"` (se *Drift*).

Mutationer, først med et OPERATIONS-token fra Keycloak og derefter uden token. Begge fejler med vilje, så ingen data
ændres:

```bash
TOKEN=$(curl -s -d client_id=airport-frontend -d grant_type=password -d username=ops -d password=ops \
  http://localhost:8090/auth/realms/airport/protocol/openid-connect/token | jq -r .access_token)
curl -s $GQL -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"query":"mutation { createShop(input: { name: \"Kiosk\", category: RETAIL, terminal: \"T2\", zone: \"Pier B\", floor: 0, openingHours: \"8-20\" }) { id } }"}' \
  | jq -c '.errors[0] | {message, code: .extensions.code}'
# {"message":"openingHours: openingHours must be HH:MM-HH:MM or 24/7","code":"VALIDATION_ERROR"}
curl -s $GQL -H 'Content-Type: application/json' -d '{"query":"mutation { deleteShop(id: 1) }"}' \
  | jq -c '.errors[0] | {message, code: .extensions.code}'
# {"message":"Authentication required: send a Bearer token","code":"UNAUTHORIZED"}
```

## Forretningsregler

- **`ShopInput`:** `name` 1–100 tegn, `terminal` 1–5, `zone` 1–50, `floor` −2…10 og `description` højst 2000 tegn.
  `openingHours` skal være `HH:MM-HH:MM` eller `24/7`. Brud giver `VALIDATION_ERROR`. `terminal` gemmes med store
  bogstaver, og tekstfelterne trimmes.
- **Åbningstider** (`OpeningHours`, evalueres i `APP_TIMEZONE`): åbningsminuttet tæller med, lukkeminuttet gør ikke.
  `22:00-04:00` går over midnat, og `00:00-00:00` betyder hele døgnet. En tid, der ikke kan parses (fx `25:00-26:00`),
  giver `openNow = false`.
- **Idempotent `createShop`:** samme nøgle giver den butik, første kald oprettede. Er den butik slettet, er nøglen
  stadig optaget → `CONFLICT`. En blank nøgle tæller som ingen nøgle.
- **Tombstone:** efter `deleteShop` er butikken væk fra `shop`, `shops`, `searchShops`, `NavNode.shops`,
  `shopsAlongRoute` og `askRoute`. Et nyt `updateShop`/`deleteShop` giver `NOT_FOUND`.
- **Rute** (`RouteService`): Dijkstra over alle kanter i begge retninger. `accessibleOnly` springer kanter med
  `accessible = false` over, og findes der ingen vej, giver det `ROUTE_NOT_FOUND`. Instruktionerne er "Start ved …",
  "Gå N m til …", "Tag elevatoren/trappen til etage N" (ved etageskift afhængigt af kanten) og "Du er fremme ved …".
  `estimatedMinutes` er afstand ÷ 80 m/min rundet op (mindst 1, når afstanden er over 0). Er start og mål samme
  node, giver det ét trin, "Du er allerede ved …", med 0 m.
- **`askRoute`:** `question` må ikke være blank og højst være 500 tegn (`VALIDATION_ERROR`). Spørgsmålet samles til
  én linje, og `fromNodeId` skal findes (`NOT_FOUND`). Modellen svarer i JSON-kontrakten
  `{english, need, shop, fits}`, hvor `shop` er låst til butiksnavnene med `enum`. `KeywordMatcher` beslutter:
  - +3 for et ord i navnet, ellers +1 for et ord i beskrivelsen
  - +2 for et kategori-ord
  - +1 til modellens forslag
  - +1 for butikker i destinationens terminal (ellers passagerens), når de allerede har point

  Står flere butikker lige, vinder den nærmeste med Dijkstra. En destination (`GATE`, `SECURITY`, `ENTRANCE`,
  `ELEVATOR`) findes i spørgsmålet, og for gates er koden alene nok ("B12"). Ruten går så via butikken med
  `routeVia`. Er AI slået fra, eller svarer Ollama ikke (fejl, timeout, ugyldig JSON), scorer fallbacken spørgsmålet
  alene med `aiUsed=false` og `fallbackReason`. Finder scoringen ingen kandidat (fx `fits=false` og ingen kendte
  ord), er `shop` og `route` `null`. Kan ingen kandidat nås, giver det `ROUTE_NOT_FOUND`. Alle detaljer står i
  [AI: "Spørg om vej"](../docs/architecture.md#ai-spørg-om-vej-lokal-sprogmodel).

## Events

**Publicerer** (via transactional outbox): ingen. `EventPublisher`, `outbox_event` og `OutboxRelay` er med, så
servicen følger samme skabelon som de andre og kan få fx `shop.deleted` uden ny infrastruktur (se
[Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot)).

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|
| `shop-service.flight-events` | `flight.#` på `airport.events` | `flight.gate.changed` | `FlightEventHandler` logger "Gate change for flight {flightNumber} : {oldGate} -> {newGate}". Andre `flight.*` ignoreres, men registreres i `processed_event` |

Levering: `processed_event` fjerner duplikater, og efter 3 forsøg (500 ms, ×2, højst 3 s) går beskeden via
`airport.events.dlx` til `shop-service.dlq`. Se [events.md](../docs/events.md#leveringsgarantier) og
[asyncapi.yaml](../docs/asyncapi.yaml).

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|
| `AI_ENABLED` | `true` | `false` = Ollama kaldes aldrig, og `askRoute` bruger fallbacken. k8s-basen sætter `"false"`, komponenten `ollama` sætter `"true"` |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama-server (`POST /api/chat`). I compose og k8s er det `http://ollama:11434` |
| `OLLAMA_MODEL` | `qwen2.5:1.5b` | Modelnavn, som skal findes i Ollama-imaget |
| `AI_TIMEOUT_MS` | `15000` | Read-timeout pr. chat-kald. Connect-timeout er fast 2 s (`AiConfig`) |
| `AI_WARM_UP` | `true` | `OllamaWarmUp` indlæser modellen efter opstart (6 forsøg, 10 s imellem) |
| `APP_TIMEZONE` | `Europe/Copenhagen` | Tidszone for `openNow` |
| `OUTBOX_POLL_INTERVAL_MS` | `500` | Pollinterval for `OutboxRelay` |
| `GRAPHQL_PATH` / `SERVER_PORT` | `/api/shops/graphql` / `8080` | Endpoint og port |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5437/shop_db` / `shop` / `shop` | Servicens database |
| `RABBITMQ_*`, `OIDC_ISSUER_URI`, `JWK_SET_URI`, `CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE` | se rod-README | Fælles, se [Konfiguration](../README.md#konfiguration). `prod` slår GraphiQL fra og giver JSON-logs |

Faste værdier (ingen variabel): køerne, outbox-batch 100, confirm-timeout 5000 ms og oprydning af sendte rækker efter
7 dage.

## Kør lokalt

```bash
docker compose up --build                  # http://localhost:8085/api/shops/graphql, GraphiQL :8085/graphiql
docker compose --profile ai up --build     # samme + Ollama på 11434 (askRoute med aiUsed=true)

# alene: Postgres (5437) og RabbitMQ fra compose; keycloak kun til mutationer, ollama kun til AI
docker compose up -d shop-db rabbitmq keycloak && docker compose --profile ai up -d ollama
cd shop-service && mvn spring-boot:run     # http://localhost:8080/api/shops/graphql, GraphiQL /graphiql
```

Compose starter ikke Ollama som afhængighed. Uden den er servicen sund, og `askRoute` svarer med fallbacken. I
Kubernetes er adressen `http://localhost:8090/api/shops/graphql` gennem Ingress (`prod`-profil, ingen GraphiQL).
Modellen kommer med `k8s/overlays/demo` (se [Demo-overlay](../k8s/README.md#demo-overlay-ai-og-serverless-keda)).

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|
| `DijkstraTest` | unit (6) | Flere hop slår en lang direkte kant, kanter virker begge veje, `accessibleOnly` tager elevatoren frem for trappen, en node uden forbindelse giver ingen rute, samme node giver 0 m, og afstand ≤ 0 afvises |
| `OpeningHoursTest` | unit (2 `@Test` + 13 parametriserede tilfælde = 15) | Almindelig tid (grænser: åbning med, lukning ikke med), tid over midnat, `24/7`/`00:00-00:00` og ugyldigt input giver lukket |
| `KeywordMatcherTest` | unit (4) | Stopord og tegnsætning fjernes, kategori-ord matcher helt eller på stamme, destination findes ud fra navn eller gate-kode, og `need` og forslag scores sammen med spørgsmålet |
| `AiConciergeServiceTest` | unit, mocket `OllamaClient` (17) | Scoring og rute via butikken, prompt og skema indeholder alle butikker, forslaget afgør uafgjort og taber til et klart match, `fits=false`, ukendt navn, kodeblok og `"none"`, fejl/ugyldig JSON/AI slået fra → fallback, ingen butikker, `accessibleOnly` og ukendt startnode |
| `AiConciergeIntegrationTest` | Testcontainers + `MockRestServiceServer` for `/api/chat` (8) | Request-body (model, `temperature 0`, skema, prompt), rute Security T2 → Starbucks → Gate B12 (470 m, 8 trin), spørgsmål uden kendte ord, elevator ved `accessibleOnly`, HTTP 500/connection refused/ugyldigt svar → fallback, "ingen butik" og validering af spørgsmålet |
| `ShopServiceIntegrationTest` | Testcontainers (14) | Flyway-seed, rute Security T2 → Gate B12 (350 m), trappe (205 m) mod elevator (225 m), rute mellem terminaler, `NOT_FOUND`, søgning og filtre, CRUD, tombstone, idempotency key, `VALIDATION_ERROR`, `flight.gate.changed` forbrugt én gang og outbox (rollback, retry, publish uden transaktion) |
| `SecurityIntegrationTest` | Testcontainers (7) | Offentlige queries og readiness, `UNAUTHORIZED`/`FORBIDDEN` på mutationer, OPERATIONS opretter og sletter, udløbet eller fremmed token → HTTP 401, `/actuator/prometheus` er åben og `/actuator/metrics` er ikke, CORS-preflight |

`TestTokens` er en hjælpeklasse, der laver JWT'er med samme claims som Keycloak.

```bash
cd shop-service && mvn -Pci verify         # tests + Checkstyle + SpotBugs, som CI-jobbet backend · shop-service
```

Kræver Docker (Testcontainers starter `postgres:16-alpine` og `rabbitmq:3.13-management-alpine`). Ollama er stubbet,
og `app.ai.warm-up=false` i AI-integrationstesten. Der er 71 tests i alt.

## Drift

- **Probes:** startup og liveness på `/actuator/health/liveness`, readiness på `/actuator/health/readiness`
  (`readinessState`, `db`, `rabbit`). Ollama er bevidst ikke med i readiness.
- **Kubernetes** (`k8s/base/services/shop-service.yaml`): initContainer `wait-for-db`, requests `150m`/`256Mi` og
  limits `500m`/`640Mi`. Podden kører som uid 100 med read-only rodfilsystem og `/tmp` som `emptyDir`. Imaget har et
  CDS-arkiv.
- **HPA** (`shop-service-hpa.yaml`): 1–3 pods ved 70 % CPU af `requests`. Kræver metrics-server, ellers viser HPA'en
  `<unknown>`. Belast med `scripts/load-shops.sh`. Se [Skalerbarhed](../docs/architecture.md#skalerbarhed) og
  [Autoscaling (HPA)](../k8s/README.md#autoscaling-hpa).
- **Ollama** (`k8s/components/ollama`): patcher `shop-service-config` med `AI_ENABLED=true`. En allerede kørende pod
  skal genstartes med `kubectl -n airport rollout restart deployment/shop-service`. Ollama har `OLLAMA_KEEP_ALIVE=5m`,
  og modellen smides derefter ud af hukommelsen. På kind 17-09-2026 tog første spørgsmål efter den pause 16,5 s i
  Ollama (prompt på 795 tokens: 11,7 s). Det er over `AI_TIMEOUT_MS` på 15 s, så svaret blev fallbacken. Næste
  spørgsmål tog 1,6 s. Read-timeouts står også som "not reachable" i `fallbackReason`.
- **Metrics** på `/actuator/prometheus` (offentlig, ikke routet af Ingress): `events_consumed_total{type,outcome}`,
  `outbox_pending` (altid 0 her) og `http_server_requests` som histogram. Relevante alarmer er `ServiceDown`,
  `MessagesDeadLettered` (`shop-service.dlq`), `EventHandlingFailing` og `HighServerErrorRate`. Se
  [Observability](../docs/architecture.md#observability-metrics-logs-og-alarmer).
- **Loglinjer:** `Ollama warm-up: qwen2.5:1.5b is loaded (… ms, attempt 1)` (eller `… gave up after 6 attempts`),
  `askRoute: Ollama gave no answer after … ms, using keyword fallback: …` (WARN),
  `askRoute: model read "…" as need "…", suggested … (fits=…)`, `Idempotent replay: shop …`,
  `Deleted shop … - tombstone kept` og `Gate change for flight …`.

## Designvalg

| Valg | Begrundelse | Uddybet i |
|------|-------------|-----------|
| `nav_edge` gemmes én gang og bruges begge veje. `Dijkstra` er en ren klasse uden Spring | Halvt så mange rækker, og algoritmen kan unit-testes isoleret | [Designvalg](../docs/architecture.md#designvalg-hvor-specen-gav-frihed), [Flow D](../docs/architecture.md#flow-d--navigation) |
| Læsning er offentlig, og mutationerne kræver OPERATIONS med `@PreAuthorize` på ét endpoint | Passagerer navigerer uden login | [Sikkerhed](../docs/architecture.md#operation--rolle) |
| Tombstone (`deleted_at` + `@SQLRestriction`) i stedet for `DELETE` | Kan fortrydes, bevarer historik, og intet repository skal huske et filter | [Tombstone og snapshot](../docs/architecture.md#tombstone-og-snapshot) |
| `idempotencyKey` på `createShop`, også gyldig efter sletning | Et dobbeltklik eller et gentaget request giver kun én butik | [Idempotens](../docs/architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |
| Lokal, valgfri model: modellen forstår, koden beslutter, og nøgleordssøgning er fallback | Ingen nøgler, og ingen tekst går til tredjepart. Modellen kan ikke route til noget, der ikke findes | [AI: "Spørg om vej"](../docs/architecture.md#ai-spørg-om-vej-lokal-sprogmodel), [Flow E](../docs/architecture.md#flow-e--spørg-om-vej-ai) |
| Den eneste service med HPA | Offentlige, CPU-bundne læsekald uden tilstand i podden, og relayet er sikret med advisory lock | [Skalerbarhed](../docs/architecture.md#skalerbarhed) |

## Se også

- [docs/architecture.md](../docs/architecture.md): [Flow D](../docs/architecture.md#flow-d--navigation),
  [Flow E](../docs/architecture.md#flow-e--spørg-om-vej-ai),
  [AI: "Spørg om vej"](../docs/architecture.md#ai-spørg-om-vej-lokal-sprogmodel),
  [Messaging](../docs/architecture.md#messaging)
- [docs/events.md](../docs/events.md#shop-service) og [docs/asyncapi.yaml](../docs/asyncapi.yaml)
- [k8s/README.md](../k8s/README.md): [Demo-overlay](../k8s/README.md#demo-overlay-ai-og-serverless-keda),
  [Autoscaling (HPA)](../k8s/README.md#autoscaling-hpa)
- [Rod-README: AI-sprogmodellen](../README.md#ai-sprogmodellen-bag-spørg-om-vej), `../ollama/Dockerfile`,
  `../scripts/load-shops.sh`
