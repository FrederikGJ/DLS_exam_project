# Event-kontrakt (RabbitMQ)

Alle services kommunikerer asynkront via **én topic exchange** `airport.events`.
Routing key = eventnavn (fx `booking.confirmed`).

## Envelope

Alle events er JSON med samme envelope. `payload` er event-specifik (se nedenfor).

```json
{
  "eventId":    "3f1c2a8e-8d0e-4f3c-9a6b-1d2e3f4a5b6c",
  "eventType":  "booking.confirmed",
  "occurredAt": "2026-09-11T10:15:30.123Z",
  "producer":   "booking-service",
  "payload":    { }
}
```

AMQP message properties: `content-type: application/json`, `message-id = eventId`, `type = eventType`,
`delivery-mode: persistent`.

## Køer, bindings og dead-letter

Hver service har én durable kø pr. interesse og én DLQ:

| Service         | Kø                                 | Binding pattern | DLQ                    |
|-----------------|------------------------------------|-----------------|------------------------|
| flight-service  | `flight-service.booking-events`    | `booking.#`     | `flight-service.dlq`   |
| booking-service | `booking-service.payment-events`   | `payment.#`     | `booking-service.dlq`  |
| booking-service | `booking-service.flight-events`    | `flight.#`      | `booking-service.dlq`  |
| booking-service | `booking-service.baggage-events`   | `baggage.#`     | `booking-service.dlq`  |
| payment-service | `payment-service.booking-events`   | `booking.#`     | `payment-service.dlq`  |
| baggage-service | `baggage-service.booking-events`   | `booking.#`     | `baggage-service.dlq`  |
| baggage-service | `baggage-service.flight-events`    | `flight.#`      | `baggage-service.dlq`  |
| shop-service    | `shop-service.flight-events`       | `flight.#`      | `shop-service.dlq`     |
| notification-job | `notifications`                   | `booking.#`     | `notification-job.dlq` |

> Vi bruger `#` (0..n ord) og ikke `*` (præcis 1 ord), fordi fx `flight.status.changed` har tre segmenter.

`booking-service.baggage-events` fodrer udelukkende read-modellen `booking_overview` (CQRS, se
[architecture.md](architecture.md#cqrs-booking_overview-til-min-booking)); booking-service ændrer aldrig en booking
på grund af et bagage-event.

`notifications` ejes af notification-job (en run-to-completion-proces, ikke en service – se afsnittet nederst), men
booking-service erklærer den også ved opstart med præcis samme argumenter, så booking-events gemmes, fra
booking-service starter, også før jobbet har kørt første gang. RabbitMQ accepterer kun en ny erklæring af en
eksisterende kø, hvis argumenterne er identiske; et kontrakttest i hver af de to sider tjekker det.

Dead-letter: Køerne er oprettet med `x-dead-letter-exchange: airport.events.dlx` (direct exchange) og
`x-dead-letter-routing-key: <service-navn>`. Spring AMQP retry (stateless, 3 forsøg med backoff) afviser
beskeden uden requeue efter 3. fejl, hvorefter RabbitMQ flytter den til `<service>.dlq`.

Idempotens: hver service gemmer behandlede `eventId` i tabellen `processed_event` i samme transaktion som
den tilstandsændring eventet medfører. Et event med kendt `eventId` ignoreres (logges som "skipping").

## Leveringsgarantier

* **Atomisk med tilstanden.** Et event skrives til producentens `outbox_event`-tabel i samme
  databasetransaktion som den ændring det beskriver. Rulles transaktionen tilbage, findes eventet ikke.
* **At-least-once.** `OutboxRelay` sender rækkerne til RabbitMQ og markerer dem først som sendt, når brokeren
  har bekræftet (publisher confirms). Er brokeren nede, bliver rækkerne liggende og sendes når den er tilbage.
* **Duplikater er normale.** Crasher relayet mellem bekræftelse og markering, sendes eventet igen med
  *samme* `eventId`. Consumers skal derfor være idempotente – `eventId` er dedup-nøglen, og
  `processed_event` er implementeringen i alle services.
* **Rækkefølge pr. producent bevares.** Kun ét relay er aktivt pr. service (Postgres advisory lock), rækker
  sendes i `id`-orden, og en fejlet batch gentages som helhed. Det betyder fx at `booking.confirmed` aldrig
  overhaler `booking.created` fra samme service. Rækkefølge *på tværs* af producenter garanteres ikke.
* **Observerbarhed.** Ubekræftede events kan ses i `outbox_event` (`published_at IS NULL`, `attempts`,
  `last_error`) og som gauge `outbox.pending` på `/actuator/metrics/outbox.pending`.

## Rækkefølge og kommutativitet

Rækkefølgen pr. producent bevares, men det er ikke nok: events fra *to* producenter (fx `booking.checkedin` fra
booking-service og `flight.cancelled` fra flight-service) kan komme i vilkårlig orden, og en genlevering (retry efter en
fejl, en besked flyttet tilbage fra en DLQ, en ny pod der overtager en kø) kan komme *efter* nyere events. Derfor er
hver handler skrevet, så slutresultatet ikke afhænger af rækkefølgen (**kommutativ**) – ligesom det ikke afhænger af,
hvor mange gange et event leveres (**idempotent**, `processed_event`). Handlerne bruger fire teknikker:

| Teknik | Hvor | Regel | Hvorfor netop her |
|--------|------|-------|-------------------|
| **Tilstand, der kun går fremad** | baggage-service: `booking_snapshot.status` (`BookingSnapshot.apply`) | `PENDING_PAYMENT` < `CONFIRMED` < `CHECKED_IN` < `CANCELLED`; den status, der er længst fremme, vinder | En booking går aldrig tilbage i sin livscyklus. Reglen kræver derfor intet ur, så events fra booking-service og flight-service kan blandes frit |
| **Last-writer-wins på `occurredAt`** | flight-service: `seat.is_available` (`availability_changed_at`). booking-service: `booking.flight_status` og `booking.gate` (`flight_status_changed_at`, `gate_changed_at`) og betalings-/bagagelinjerne i read-modellen `booking_overview` | Et event, der er ældre end det, som sidst satte værdien, ignoreres. Uafgjort afgøres ens i begge rækkefølger: sæde optaget vinder over frit, ellers den alfabetisk største værdi | Værdien kan gå frem og tilbage (et sæde bliver optaget, frit og optaget af en ny booking; en gate skifter A → B → A), så kun tidspunktet kan afgøre det. Alle events om samme værdi kommer fra én producent, så tidsstemplerne er fra samme ur. Status og gate har hver sit tidsstempel, fordi `flight.gate.changed` kun bærer gaten: en ældre statusændring med den gamle gate må ikke rulle en nyere gateændring tilbage |
| **Absorberende sluttilstand** | booking-service: `flight_status = CANCELLED`. baggage-service: `booking_snapshot.status = CANCELLED` | Når `CANCELLED` er nået, ændrer intet senere event værdien – uanset tidsstempel | flight-service ændrer aldrig et aflyst fly igen, og en aflyst booking genopstår aldrig. Det gør reglen robust over for skæve ure mellem producenter (`flight.cancelled` sammenlignes aldrig med `booking.*`-tider) |
| **Tilstandsmaskine** | booking-service: `payment.completed` / `payment.failed` | Virker kun på en booking i `PENDING_PAYMENT`; ellers logges eventet og ignoreres | Fandtes før DP-31 |

**Detaljer, der gør reglerne rigtige i praksis.**

* *Præcision:* tidsstempler sammenlignes med mikrosekunder – det, PostgreSQL gemmer i `TIMESTAMPTZ` – så en række, der
  er læst op igen, sammenligner præcis som den, der blev skrevet.
* *Manglende `occurredAt`* (et brud på kontrakten) tolkes som "ældst muligt": eventet må udfylde en ukendt værdi, men
  aldrig overskrive en kendt.
* *Et forældet event er ikke en fejl:* det registreres i `processed_event`, logges på INFO ("Ignoring stale event …",
  "Ignoring late …") og kvitteres – det må ikke ende i en DLQ.
* *Kommutativ er ikke det samme som trådsikker.* booking-service og baggage-service lytter på flere køer, og med flere
  pods behandles to events om samme række samtidig. Handlerne låser derfor rækken, før de fletter (`SELECT … FOR
  UPDATE` på `seat`, `booking_snapshot` og `booking_overview`), og `booking` og `baggage` har optimistisk låsning
  (`@Version`), fordi både events og brugernes mutationer skriver i dem: en transaktion, der har læst en gammel version,
  fejler og prøves igen (listener-retry) eller giver `CONFLICT` (GraphQL/REST), i stedet for at overskrive den anden.
* *`flight.cancelled` før bookingen er kendt:* ankommer `flight.cancelled` til baggage-service, før der findes et
  snapshot af en booking på flyet, påvirker det ikke det senere snapshot. Konvergensen sikres af `booking.cancelled`,
  som booking-service altid udsender for hver booking på et aflyst fly.

**Test.** `EventOrderIntegrationTest` i flight-service, baggage-service og booking-service kalder handleren med de samme
events i *alle* rækkefølger mod en rigtig PostgreSQL og tjekker, at slutresultatet er ens: sædet (6 + 2 rækkefølger),
booking-snapshottet (24 + 6 + 120 rækkefølger, den sidste med `flight.cancelled` midt i et check-in) og bookingens
flystatus og gate inkl. read-modellen (6 + 24 rækkefølger, hvor en aflysning udsender præcis ét `booking.cancelled`).
Samme testklasse i booking-service og baggage-service viser, at `@Version` får den transaktion, der har en gammel kopi af
rækken, til at fejle, mens den ændring, der blev committet først, står tilbage. Reglerne er desuden unit-testet i `SeatTest`, `BookingSnapshotTest`, `BookingStateTest` og `OverviewLinesTest`.

---

## Events fra flight-service

### `flight.created`
```json
{
  "flightId": 11, "flightNumber": "SK9999", "airlineCode": "SK",
  "origin": "CPH", "destination": "AAL",
  "scheduledDeparture": "2026-09-14T09:00:00Z", "scheduledArrival": "2026-09-14T09:45:00Z",
  "gate": "A1", "status": "SCHEDULED"
}
```

### `flight.status.changed`
```json
{
  "flightId": 7, "flightNumber": "DY1050",
  "oldStatus": "SCHEDULED", "newStatus": "DELAYED",
  "scheduledDeparture": "2026-09-11T17:00:00Z", "gate": "B15"
}
```
Lyttere: booking-service (opdaterer snapshot `flightStatus` – og `gate`, hvis feltet er sat – på alle bookinger på flyet).

### `flight.gate.changed`
```json
{
  "flightId": 7, "flightNumber": "DY1050",
  "oldGate": "B15", "newGate": "B17",
  "scheduledDeparture": "2026-09-11T17:00:00Z"
}
```
Lyttere: booking-service (opdaterer snapshot `gate`), shop-service (logger).

### `flight.cancelled`
Publiceres **ud over** `flight.status.changed` når status sættes til `CANCELLED`.
```json
{
  "flightId": 7, "flightNumber": "DY1050",
  "scheduledDeparture": "2026-09-11T17:00:00Z",
  "reason": "Flight cancelled by airline"
}
```
Lyttere: booking-service (alle bookinger på flyet → CANCELLED), baggage-service (bagage → status REGISTERED,
`last_location` = RETURN_DESK, og `booking_snapshot` for flyet → CANCELLED).

---

## Events fra booking-service

Fælles booking-payload (bruges af alle fire booking-events):

### `booking.created`
Lyttere: baggage-service gemmer også dette som snapshot med status `PENDING_PAYMENT`, så en kendt men ubetalt
booking giver `INVALID_STATE` (i stedet for `NOT_FOUND`) ved bagageregistrering. payment-service ignorerer det.
notification-job sender mailen "Din booking … er modtaget - afventer betaling".
```json
{
  "bookingId": 1, "bookingReference": "K7Q2ZP",
  "flightId": 1, "flightNumber": "SK1501", "departureTime": "2026-09-11T14:00:00Z",
  "seatNumber": "12C", "price": 899.00, "currency": "DKK",
  "status": "PENDING_PAYMENT",
  "passenger": { "firstName": "Anna", "lastName": "Jensen", "email": "anna@example.com" }
}
```

### `booking.confirmed`
Samme payload som `booking.created` med `"status": "CONFIRMED"`.
Lyttere: flight-service (sæde `is_available=false`), baggage-service (opretter/opdaterer `booking_snapshot`),
notification-job (mail "Din booking … er bekræftet").

### `booking.cancelled`
Samme payload med `"status": "CANCELLED"` og et ekstra felt `"reason"`, fx `"Flight cancelled"`,
`"Cancelled by passenger"`, `"Payment failed: Insufficient funds"` eller `"Payment not received within 15 minutes"`
(betalingstimeout, se *Saga* i architecture.md). Årsagen gemmes også på bookingen (`cancellationReason`).
Lyttere: flight-service (sæde frigives), payment-service (automatisk refund hvis COMPLETED payment findes),
baggage-service (snapshot → CANCELLED), notification-job (mail om aflysningen med `reason`).

### `booking.checkedin`
Samme payload med `"status": "CHECKED_IN"`.
Lyttere: baggage-service (snapshot → CHECKED_IN), notification-job (mail om check-in).

### `booking.payment.rejected`
Saga-kompensation: `payment.completed` kom til en booking, der allerede var aflyst (betalingstimeout, passagerens
aflysning eller aflyst fly). Bookingen forbliver `CANCELLED`, og betalingen skal tilbage.
```json
{
  "bookingId": 1, "bookingReference": "K7Q2ZP", "status": "CANCELLED",
  "paymentId": 42, "amount": 899.00, "currency": "DKK",
  "reason": "Payment arrived after the booking was cancelled (Payment not received within 15 minutes)"
}
```
Lyttere: payment-service (refunderer betalingen `paymentId`, hvis den stadig er `COMPLETED` → `payment.refunded`).
notification-job springer det over (ukendt booking-event). Det er bevidst ikke et nyt `booking.cancelled`, som ville få
flight-service til at frigive et sæde, en anden passager måske allerede har booket – se *Saga* i architecture.md.

---

## Events fra payment-service

### `payment.completed`
```json
{
  "paymentId": 1, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK", "cardLast4": "4242"
}
```
Lyttere: booking-service (booking → CONFIRMED, publicerer `booking.confirmed`; er bookingen allerede aflyst, forbliver
den `CANCELLED`, og `booking.payment.rejected` publiceres i stedet; betalingen skrives i read-modellen
`booking_overview`).

### `payment.failed`
```json
{
  "paymentId": 2, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK", "cardLast4": "0000",
  "failureReason": "Insufficient funds"
}
```
Lyttere: booking-service (booking → CANCELLED, publicerer `booking.cancelled`; betalingen med `failureReason` skrives i
`booking_overview`).

### `payment.refunded`
```json
{
  "paymentId": 1, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK"
}
```
Lyttere: booking-service (samme betalingslinje i `booking_overview` → `REFUNDED`, så *Min booking* viser refunderingen).

---

## Events fra baggage-service

### `baggage.registered`
```json
{
  "tagNumber": "BAG-7F3K9Q2M", "bookingReference": "K7Q2ZP",
  "passengerName": "Anna Jensen", "flightNumber": "SK1501",
  "weightKg": 23.0, "type": "CHECKED", "status": "REGISTERED", "lastLocation": "CHECK_IN"
}
```
`lastLocation` blev tilføjet i september 2026, så read-modellen i booking-service kan vise lokationen fra starten. Et nyt
felt er bagudkompatibelt (consumerne læser felt for felt), så eventet beholder sit navn – se *API-versionering* i
architecture.md.
Lyttere: booking-service (ny bagagelinje i `booking_overview`).

### `baggage.status.changed`
```json
{
  "tagNumber": "BAG-7F3K9Q2M", "bookingReference": "K7Q2ZP", "flightNumber": "SK1501",
  "oldStatus": "REGISTERED", "newStatus": "LOADED", "location": "Belt 4"
}
```
Lyttere: booking-service (status og lokation på bagagelinjen i `booking_overview`).

---

## shop-service
Publicerer ingen events. Lytter på `flight.#` og logger `flight.gate.changed` (eventId + gate).

## notification-job (consumer, ingen service)
Publicerer ingen events. Tømmer køen `notifications` (`booking.#`) og skriver én dansk mail pr. event i sin log
(`booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.checkedin`); ukendte booking-events (fx en
fremtidig `booking.x.v2`) kvitteres og springes over, og en besked, der ikke kan parses, afvises uden requeue og
havner i `notification-job.dlq`. Jobbet har ingen database og derfor ingen `processed_event`: leveringen er
at-least-once, og et duplikat giver en mail mere – `eventId` er den idempotensnøgle, en rigtig mailudbyder ville
deduplikere på. Jobbet kører kun, når der er beskeder: i Kubernetes som KEDA `ScaledJob` (se
[architecture.md](architecture.md#serverless-notification-job-som-keda-scaledjob)), i compose med
`docker compose --profile jobs up notification-job`.
