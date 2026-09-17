# Kravmatrix: krav → implementering → bevis

Hvert krav fra eksamensopgavens kravliste (48 punkter i syv afsnit) med *hvor* det er løst og *hvordan det er
eftervist*. Beviserne er af fire slags, og der står altid mindst én:

- **Test** – en testklasse, der kører i CI (`mvn -Pci verify` i hver modul, `system-tests/`, `scripts/e2e-smoke.sh`).
- **CI** – GitHub Actions ([.github/workflows/ci.yml](../.github/workflows/ci.yml)); seneste kørsel på `dev_max`
  (commit `4d4a634`, 17-09-2026) var grøn i alle 10 jobs på 4,8 min. Kørslen for `b855b12`, den første med jobbet
  `contracts` (AsyncAPI), var grøn i 10 af 11 jobs: `security` blev rød på en falsk positiv fra gitleaks, som nu er
  undtaget med begrundelse i [.gitleaks.toml](../.gitleaks.toml) (grøn lokalt, afventer næste kørsel).
- **Verificeret** – kørt og målt mod compose eller kind; tal og dato står i det linkede afsnit.
- **Dok.** – hvor valget er beskrevet og begrundet.

Status 17-09-2026: alle 48 krav er implementeret. De få forbehold står i kolonnen *Bevis* (fx at pipelinen endnu kun
er kørt på `dev_max` og ikke på `main`).

Testtal: flight-service 32, booking-service 51, payment-service 27, baggage-service 68, shop-service 71,
notification-job 36, system-tests 5 – i alt 290 tests.

## 1. Systemets omfang

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 1.1 | Distribueret arkitektur | 5 services + notification-job + frontend + RabbitMQ + 5 × PostgreSQL + Keycloak; `docker-compose.yml`, `k8s/` | Dok. [architecture.md – Overblik](architecture.md#overblik) · Verificeret: `scripts/e2e-smoke.sh` gennem alle services på compose og kind |
| 1.2 | Mindst 5 backend-microservices med forretningslogik | `flight-service` (priser, sæder, status), `booking-service` (bookinger, saga, CQRS), `payment-service` (gateway, refundering), `baggage-service` (regler, tags), `shop-service` (Dijkstra, åbningstider, AI) | Test: unit tests af kernelogikken pr. service (se 6.2) · Dok. service-READMEs (7.1) |
| 1.3 | Frontend-klient | `frontend/` – vanilla JS SPA bag nginx: afgange, book, betaling, *Min booking*, bagage, butikker | CI: `frontend · eslint` · Verificeret: login, *Min booking*, betalingsfrist og Grafana-dashboard kørt i headless Chromium (Playwright) mod compose og kind |
| 1.4 | Frit valg af teknologi | Java 21 / Spring Boot 3.5, PostgreSQL 16, RabbitMQ 3.13, Keycloak 26, Kubernetes/Kustomize | Dok. [README – Tech stack](../README.md#tech-stack) |
| 1.5 | Skalerbarhed (database, applikation, API-design) | Stateless services, én database pr. service med indekser, advisory lock på outbox-relayet, idempotente consumers, HPA på shop-service, KEDA på notification-job, read model `booking_overview` | Verificeret: HPA 1 → 3 pods under load og ned igen på kind ([k8s/README – Autoscaling](../k8s/README.md#autoscaling-hpa)) · Dok. [architecture.md – Skalerbarhed](architecture.md#skalerbarhed) |
| 1.6 | Portabilitet (containerisering) | Multi-stage Dockerfile pr. modul (non-root, CDS-arkiv), samme images i compose, kind og CI | CI: `system` bygger og kører alle images; `security` scanner dem med Trivy · Dok. [k8s/README – CDS](../k8s/README.md#class-data-sharing-cds-hurtigere-boot-uden-flere-ressourcer) |
| 1.7 | Sikkerhed: authentication og authorization | Keycloak (OIDC, realm `airport`), JWT-validering i alle 5 services (`config/SecurityConfig`), roller PASSENGER/OPERATIONS med `@PreAuthorize` pr. operation | Test: `SecurityIntegrationTest` i alle 5 services · CI: `security` (gitleaks + Trivy) · Dok. [architecture.md – Sikkerhed](architecture.md#sikkerhed-login-og-roller) |
| 1.8 | Interoperabilitet (veldesignede API'er) | GraphQL med fælles fejlkoder (`extensions.code`), REST v1 med RFC 9457 problem details og samme koder, OpenAPI, AsyncAPI, W3C `traceparent` | Test: `BaggageRestIntegrationTest` · Dok. [README – API'er](../README.md#apier-graphql-og-rest), [openapi/baggage-v1.yaml](openapi/baggage-v1.yaml), [asyncapi.yaml](asyncapi.yaml) |
| 1.9 | AI-integration i et rigtigt workflow | `askRoute` i shop-service: lokal sprogmodel (Ollama, `qwen2.5:1.5b`) tolker et fritekstspørgsmål, koden vælger butik og beregner ruten; nøgleordssøgning som fallback. Brugt på frontendens *Butikker*-side | Test: `AiConciergeServiceTest`, `AiConciergeIntegrationTest`, `KeywordMatcherTest` · Verificeret: 33 spørgsmål mod den rigtige model, `aiUsed=true` på kind · Dok. [architecture.md – AI](architecture.md#ai-spørg-om-vej-lokal-sprogmodel) |

## 2. Kommunikation og integration

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 2.1 | Primært asynkron kommunikation mellem services | Alle effekter mellem services går som events over `airport.events` via transactional outbox; ét begrundet synkront kald (booking → flight: pris og ledigt sæde) | Test: integrationstests med Testcontainers RabbitMQ i alle services, `CooperationTest` · Dok. [events.md](events.md), [architecture.md – Messaging](architecture.md#messaging) |
| 2.2 | Backend udstiller både REST og GraphQL | GraphQL i alle 5 services; versioneret REST-API `/api/baggage/v1` i baggage-service oven på samme forretningslogik | Test: `BaggageRestIntegrationTest`, `BaggageServiceIntegrationTest` · Dok. [baggage-service/README.md](../baggage-service/README.md) |
| 2.3 | API-interoperabilitet i rigtige workflows | Bagage-siden kalder REST (registrering, status, liste) og GraphQL (`bookingSnapshot`) hos samme service; *Min booking* læser booking-services read model, der samler data fra tre services | Verificeret: browserens netværksfane viser `/v1/`- og `/graphql`-kald i samme flow · Dok. [README – Prøv flows](../README.md#prøv-flows-fra-frontenden) |
| 2.4 | Frontend bruger både REST og GraphQL og taler med flere services | `frontend/js/api.js`: `gql()` til alle 5 services og `rest()` til baggage-service v1 med samme fejlmodel | CI: `frontend · eslint` · Verificeret: Flow A–E i browseren på compose og kind |
| 2.5 | Token-baseret autentifikation (JWT) med en tredjeparts identity provider via OAuth/OIDC | Keycloak 26 som OIDC-provider; frontenden logger ind med Authorization Code + PKCE (`keycloak-js`); services validerer JWT mod Keycloaks JWKS; GitHub som identity brokering kan slås til | Test: `SecurityIntegrationTest` (udløbet, fremmed og ugyldigt token → 401) · Verificeret: login/refresh/logout i browseren på compose og kind · Dok. [architecture.md – Login- og kaldsflow](architecture.md#login--og-kaldsflow) |
| 2.6 | En AI-service brugt via API i mindst én services forretningslogik | shop-service kalder Ollamas `/api/chat` med JSON-schema-format (`ai/OllamaClient`) med timeout, opvarmning og fallback | Test: `AiConciergeIntegrationTest` (MockRestServiceServer) · Verificeret: compose `--profile ai` og kind `overlays/demo` · Dok. [shop-service/README.md](../shop-service/README.md) |

## 3. Systemarkitektur og designmønstre

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 3.1 | Services kan deployes uafhængigt | Egen `pom.xml`, Dockerfile, database og Kubernetes-Deployment pr. service; ingen delt kodemodul (`EventEnvelope` kopieret) | CI: `backend`-matrixen bygger hvert modul for sig |
| 3.2 | Hver service ejer sine data | 5 databaser; kopier af andres data kun som snapshots/read models opdateret fra events | Dok. [architecture.md – Tombstone og snapshot](architecture.md#tombstone-og-snapshot) |
| 3.3 | CQRS hvor det giver mening – begrundet | Read model `booking_overview` i booking-service til *Min booking*; bevidst fravalgt i de øvrige services | Test: `BookingOverviewIntegrationTest`, `OverviewLinesTest` · Verificeret: opdatering 289 ms (median) efter betaling · Dok. [architecture.md – CQRS](architecture.md#cqrs-booking_overview-til-min-booking) |
| 3.4 | Immutable data: tombstone og/eller snapshot | Tombstone `deleted_at` + `@SQLRestriction` på butikker; bookinger/betalinger/bagage slettes aldrig (statusser); snapshots i booking- og baggage-service | Test: `ShopServiceIntegrationTest.deletedShopLeavesATombstoneThatNoQuerySees` · Dok. [architecture.md – Tombstone og snapshot](architecture.md#tombstone-og-snapshot) |
| 3.5 | Idempotente operationer | Idempotency key på `registerBaggage` (GraphQL + REST-header) og `createShop`; naturlige nøgler og tilstandsregler for resten; `processed_event` for alle consumers; unikt indeks mod samtidige betalinger | Test: 8 samtidige kald med samme nøgle (baggage), 8 samtidige `pay` (payment), dubletter af events i alle integrationstests · Dok. [architecture.md – Idempotens](architecture.md#idempotens-hvad-sker-der-når-en-mutation-gentages) |
| 3.6 | Kommutative message handlers | Livscyklus-rang (booking-snapshot), last-writer-wins på `occurredAt` (sæder, flystatus/gate, read model), endelige tilstande; rækkelåse og `@Version` | Test: `EventOrderIntegrationTest` i flight-, baggage- og booking-service (alle permutationer) · Dok. [events.md – Rækkefølge og kommutativitet](events.md#rækkefølge-og-kommutativitet) |
| 3.7 | Saga i stedet for distribuerede transaktioner | Choreograferet booking-saga med kompensationer: aflysning ved afvist betaling og aflyst fly, betalingstimeout (`PaymentTimeoutJob`), refundering af for sen betaling (`booking.payment.rejected`) | Test: `SagaCompensationIntegrationTest`, `PaymentServiceIntegrationTest`, `CooperationTest` · Verificeret: `scripts/demo-saga.sh` · Dok. [architecture.md – Saga](architecture.md#saga-bookingen-som-en-kæde-af-lokale-transaktioner) |

## 4. Cloud-native og drift

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 4.1 | Logging og monitoring til et distribueret system | JSON-logs med trace-id gennem HTTP, outbox og RabbitMQ; Prometheus-metrics (`events_*`, `outbox_pending`, HTTP, JVM, RabbitMQ-køer); Prometheus + Loki + Alloy + Grafana med dashboard og 5 alarmer | Test: `ObservabilityIntegrationTest`, `/actuator/prometheus` i alle `SecurityIntegrationTest` · Verificeret: én betaling fundet med sin trace-id i 4 services' logs; DLQ-alarm fyret på kind · Dok. [architecture.md – Observability](architecture.md#observability-metrics-logs-og-alarmer) |
| 4.2 | Containeriserede services | Alle moduler og frontenden har Dockerfile; tredjepartskomponenter som officielle images | CI: `system` (`docker compose build` + `up --wait`), Trivy image-scan |
| 4.3 | Klar deployment-arkitektur | Kustomize base + components + overlays; deploymentdiagram og oversigt over overlays/components | Dok. [architecture.md – Deployment i Kubernetes](architecture.md#deployment-i-kubernetes), [k8s/README.md](../k8s/README.md) · CI: `manifests` (kubeconform) |
| 4.4 | Serverless-funktion (fx KEDA ScaledJob) | `notification-job` som KEDA `ScaledJob` på længden af køen `notifications` (0–3 jobs) | Test: `NotificationJobIntegrationTest` · Verificeret: `scripts/demo-keda.sh` – kø tom efter 6 s, jobs slettet efter 41 s · Dok. [architecture.md – Serverless](architecture.md#serverless-notification-job-som-keda-scaledjob) |
| 4.5 | Autoscaling i Kubernetes | HorizontalPodAutoscaler på shop-service (1–3 pods ved 70 % CPU) med metrics-server | Verificeret: `scripts/load-shops.sh` → 3 pods og tilbage til 1 · Dok. [k8s/README – Autoscaling](../k8s/README.md#autoscaling-hpa) |
| 4.6 | Udviklingsmiljø med docker-compose | `docker-compose.yml` med healthchecks, profiler `ai`, `jobs`, `observability` | CI: `system`-jobbet starter stakken og kører smoke-testen · Dok. [README – Kør lokalt](../README.md#kør-lokalt-med-docker) |
| 4.7 | Produktion simuleret i lokalt Kubernetes | kind-cluster (`k8s/kind-config.yaml`) med Ingress, `prod`-profil, ressourcer, probes, hærdede pods; minikube-kommandoer også beskrevet | Verificeret: demo-overlay med 18 pods Ready og smoke grøn på kind 17-09-2026 · Dok. [k8s/README.md](../k8s/README.md) |
| 4.8 | Monorepo i Git | Ét GitHub-repo med alle services, frontend, manifests, docs og CI | – |
| 4.9 | CI/CD-pipeline med automatiske tests | GitHub Actions på hver push og pull request mod `main`: `backend` (6 moduler), `frontend`, `manifests`, `contracts`, `security`, `system` | CI: grøn kørsel på `dev_max` (4,8 min). Forbehold: ingen kørsel på `main` endnu; CD (automatisk deploy) er bevidst udeladt – der er intet delt miljø at deploye til, og `system`-jobbet kører i stedet de byggede images end-to-end |
| 4.10 | Pipelinen kører unit-, integrations- og systemtests | `mvn -Pci verify` (unit + Testcontainers-integration), `system`: `e2e-smoke.sh` + `system-tests/` | CI: alle jobs grønne i kørslen ovenfor; system-tests-steppet 38 s |
| 4.11 | Statisk analyse i pipelinen | Checkstyle + SpotBugs/find-sec-bugs (Maven-profil `ci`, regler i `config/`), ESLint, gitleaks, Trivy (kode, manifests, images), kubeconform, AsyncAPI CLI | CI: `backend`, `frontend`, `security`, `manifests`, `contracts` (grønne) |
| 4.12 | Fejlende tests får pipelinen til at fejle | `mvn verify` fejler på en rød test eller en Checkstyle/SpotBugs-fejl; hvert job fejler på exit code ≠ 0 | CI: kørslen for `e9ec9ca` blev rød, da baggage-service ikke kompilerede, og kørslen for `b855b12`, da gitleaks fandt et (falsk positivt) fund. Forbehold: en rød *test* er endnu ikke set i CI (TASKS 06.07) |

## 5. Versionering

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 5.1 | Kildekode i Git | GitHub-repoet `FrederikGJ/DLS_exam_project` | – |
| 5.2 | Database-versionering | Flyway-migrationer i hver service (`db/migration/V1__init.sql` …), `ddl-auto=validate` | Test: alle integrationstests kører migrationerne mod rigtig PostgreSQL · Verificeret: nye migrationer anvendt på eksisterende compose-data |
| 5.3 | API-versionering for REST | Version i stien (`/api/baggage/v1`); strategi for GraphQL (`@deprecated`) og events (suffiks på eventnavnet) | Dok. [architecture.md – API-versionering](architecture.md#api-versionering) |

## 6. Test

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 6.1 | Tests af både enkelte services og deres samarbejde | Integrationstests pr. service + `system-tests/` (booking ↔ payment med de rigtige images) + `e2e-smoke.sh` (alle services) | CI: `backend` og `system` |
| 6.2 | Unit tests af kerneforretningslogik i hver service | `PricingServiceTest`, `SeatGeneratorTest`, `SeatTest`; `BookingStateTest`, `BookingReferenceGeneratorTest`, `OverviewLinesTest`, `PaymentTimeoutJobTest`; `PaymentSimulatorTest`; `BaggageRulesTest`, `TagGeneratorTest`, `BookingSnapshotTest`; `DijkstraTest`, `OpeningHoursTest`, `KeywordMatcherTest`, `AiConciergeServiceTest`; `MailRendererTest`, `JobConfigTest` | CI: `backend` |
| 6.3 | Integrationstests af API, messaging og database | `*ServiceIntegrationTest` pr. service: GraphQL/REST over HTTP gennem sikkerhedskæden, Testcontainers PostgreSQL + RabbitMQ, outbox- og DLQ-garantier | CI: `backend` · Dok. test-afsnittet i hver service-README |
| 6.4 | Mindst én systemtest af samarbejde mellem ≥ 2 services i containere med fokus på messaging | `system-tests/CooperationTest`: booking- og payment-service som rigtige images, RabbitMQ og PostgreSQL i containere, WireMock som flight-service og JWKS | CI: `system` (5 tests, 38 s) · Dok. [system-tests/README.md](../system-tests/README.md) |
| 6.5 | Sikkerhedstests (authentication/authorization) | `SecurityIntegrationTest` i alle 5 services: uden token, forkert rolle, rigtig rolle, udløbet/fremmed/ugyldigt token, CORS, actuator | CI: `backend` · Verificeret: `e2e-smoke.sh` tjekker `UNAUTHORIZED`/`FORBIDDEN` gennem Keycloak |
| 6.6 | Statisk analyse | Se 4.11 | CI |
| 6.7 | Alle tests kører automatisk i CI/CD | Se 4.9–4.10 | CI |

## 7. Teknisk dokumentation

| # | Krav | Implementering | Bevis |
|---|------|----------------|-------|
| 7.1 | README for monorepoet og for hver microservice | [README.md](../README.md), [k8s/README.md](../k8s/README.md), [system-tests/README.md](../system-tests/README.md) og en README pr. modul efter [service-readme-template.md](service-readme-template.md): [flight](../flight-service/README.md), [booking](../booking-service/README.md), [payment](../payment-service/README.md), [baggage](../baggage-service/README.md), [shop](../shop-service/README.md), [notification-job](../notification-job/README.md) | Dok. – eksemplerne i service-READMEs er kørt mod kind 17-09-2026 |
| 7.2 | Swagger/OpenAPI for alle REST-API'er | springdoc i baggage-service: `/v3/api-docs`, Swagger UI, eksporteret til [openapi/baggage-v1.yaml](openapi/baggage-v1.yaml) | Verificeret: den eksporterede fil matcher det kørende endpoint · Dok. [baggage-service/README.md](../baggage-service/README.md) |
| 7.3 | GraphQL-skema og endpoint-dokumentation | `schema.graphqls` med beskrivelser i hver service, GraphiQL, operationstabeller med roller i hver service-README | Dok. [README – Komponenter](../README.md#komponenter) |
| 7.4 | AsyncAPI (eller tilsvarende) for events | [asyncapi.yaml](asyncapi.yaml) (AsyncAPI 3.1): alle 14 eventtyper, 9 forbruger-køer, 6 dead-letter queues, send/receive pr. service; prosaudgave i [events.md](events.md) | CI-job `contracts` (AsyncAPI CLI uden warnings + `scripts/check-asyncapi.sh`), grønt i CI fra `b855b12` · Verificeret: 24 rigtige events fra kind valideret mod skemaerne uden fejl |
