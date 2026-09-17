# Skabelon: README for en service

Hver deploybar enhed (`flight-service`, `booking-service`, `payment-service`, `baggage-service`, `shop-service` og
`notification-job`) har en `README.md` i sin egen mappe, bygget over denne skabelon (dev plan DP-36). Formålet er, at
én person kan overtage en service uden at læse hele repoet: hvad den ejer, hvilke kontrakter den har udadtil (API og
events), hvordan den konfigureres, køres og testes, og hvor designet er begrundet.

**Regler for en service-README**

- Samme overskrifter i samme rækkefølge som nedenfor. Et afsnit, der ikke giver mening (fx *API* for et job uden
  API), bliver stående med én linje, der siger hvorfor – så læseren ved, at det ikke er glemt.
- README'en beskriver **servicen**; tværgående emner (sikkerhedsmodel, outbox, saga, observability) forklares ét sted,
  i [architecture.md](architecture.md) og [events.md](events.md), og der linkes dertil i stedet for at gentage dem.
- Alt, hvad der står, skal kunne efterprøves i koden: operationer fra `schema.graphqls`, roller fra `@PreAuthorize`,
  variabler fra `application.yml`, tests fra `src/test`. Eksempler (curl, GraphQL) er kørt, før de skrives ind.
- Dansk prosa; kodeidentifikatorer, eventnavne, fejlkoder og variabler står præcis som i koden.
- Links skrives relativt fra servicens mappe (`../docs/architecture.md#…`); i skabelonen nedenfor er de forkortet.

---

```markdown
# <service-navn>

<To til fire sætninger: hvad servicen er ansvarlig for i lufthavnssystemet, og hvad den bevidst ikke gør.>

| | |
|---|---|
| Ansvar | <ét udtryk, fx "Bookinger og passagerer"> |
| Port | compose `808x` · Kubernetes via Ingress `/api/<x>` |
| API | GraphQL `/api/<x>/graphql` <(+ REST `/api/<x>/v1`)> |
| Database | `<x>_db` (PostgreSQL 16, Flyway `V1`–`Vn`) |
| Events ud | `<event>`, … |
| Events ind | `<event>` (kø `<kø>`), … |
| Image | `airport/<x>:local` |

## Ansvar og data

- Hvilke tabeller servicen ejer, og hvad der er kilde til sandhed her.
- Kopier af andre services' data (snapshots/read models): hvad, fra hvilke events, og hvorfor.
- Hvad servicen bevidst ikke gør (og hvem der gør det).

## API

Tabel over alle operationer med rolle – præcis som `@PreAuthorize` på controllerne:

| Operation | Type | Rolle | Hvad den gør |
|-----------|------|-------|--------------|

Servicespecifikke fejlkoder (`errors[].extensions.code`) og et kørt eksempel (curl med token, hvis operationen
kræver login). Fælles fejlmodel og roller: link til architecture.md.

## Forretningsregler

Punktliste over de regler, servicen håndhæver, med den fejlkode en overtrædelse giver.

## Events

**Publicerer** (via transactional outbox):

| Event | Hvornår | Vigtigste felter |
|-------|---------|------------------|

**Forbruger:**

| Kø | Binding | Events der bruges | Effekt |
|----|---------|-------------------|--------|

Én linje om leveringsgarantier (outbox, `processed_event`, retry → `<service>.dlq`, rækkefølge) med link til
[events.md](events.md) og [asyncapi.yaml](asyncapi.yaml) – ikke en gentagelse.

## Konfiguration

| Variabel | Default (`application.yml`) | Betydning |
|----------|-----------------------------|-----------|

Kun servicens egne og dem, der betyder noget for den; de fælles (DB, RabbitMQ, OIDC, CORS) kan samles i én række med
link til rod-README'ens konfigurationstabel.

## Kør lokalt

Kommandoer: med compose, alene med `mvn spring-boot:run` (hvad skal køre ved siden af), GraphiQL-adresse.

## Test

| Testklasse | Type | Hvad den viser |
|------------|------|----------------|

Kommandoen (`mvn -Pci verify`), hvad der kræves (Docker til Testcontainers), og antal tests.

## Drift

Health/readiness, servicens egne metrics og alarmer, vigtige loglinjer, Kubernetes-manifest og ressourcer. Link til
observability-afsnittet.

## Designvalg

Kort tabel: valg → begrundelse → link til det sted i architecture.md, hvor det er forklaret udførligt.

## Se også

Links til de dokumenter, der er relevante for netop denne service.
```
