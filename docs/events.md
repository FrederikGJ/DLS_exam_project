# Event-kontrakt (RabbitMQ)

Alle services kommunikerer asynkront via **én topic exchange** `airport.events`.
Routing key = eventnavn (fx `booking.confirmed`).

Den maskinlæsbare udgave af kontrakten er [`asyncapi.yaml`](asyncapi.yaml) (AsyncAPI 3.1), som valideres i CI
med AsyncAPI CLI og `scripts/check-asyncapi.sh`.

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
| booking-service | `booking-service.baggage-events`   | `baggage.#`     | `booking-service.dlq`  |
| booking-service | `booking-service.flight-events`    | `flight.#`      | `booking-service.dlq`  |
| payment-service | `payment-service.booking-events`   | `booking.#`     | `payment-service.dlq`  |
| baggage-service | `baggage-service.booking-events`   | `booking.#`     | `baggage-service.dlq`  |
| baggage-service | `baggage-service.flight-events`    | `flight.#`      | `baggage-service.dlq`  |
| shop-service    | `shop-service.flight-events`       | `flight.#`      | `shop-service.dlq`     |
| notification-job | `notifications`                   | `booking.confirmed`, `booking.cancelled`, `payment.refunded` | `notification-job.dlq` |

`notifications` (ticket 12) adskiller sig fra de andre køer: consumeren er ikke en kørende service, men en
**serverless funktion** (KEDA ScaledJob, se [k8s/README.md](../k8s/README.md#serverless-notifikationer-med-keda)).
KEDA læser kølængden og starter ét Kubernetes Job pr. besked; jobbet tager én besked (`basicGet`), "sender" en mail
(skrives i pod-loggen), ack'er og afslutter. Der bindes kun på de tre konkrete routing keys (ikke `booking.#`), så der
ikke startes jobs for events, der ikke giver en mail. Køen oprettes af et engangs-Job (`notification-setup`), fordi
ingen ellers erklærer den, når der kører 0 jobs. En besked, der ikke kan blive til en mail, afvises uden requeue og
ender i `notification-job.dlq`; jobbet afslutter med exit 1 og står som Failed. Levering er at-least-once uden
`processed_event` (funktionen er bevidst stateless), så en mail kan i sjældne tilfælde sendes to gange.
`payment.refunded` har ingen e-mail i payloaden, så refund-mailen adresseres til bookingreferencen.
AsyncAPI-beskrivelsen af køen tilføjes, når `docs/asyncapi.yaml` (ticket 16) er merget.

> Vi bruger `#` (0..n ord) og ikke `*` (præcis 1 ord), fordi fx `flight.status.changed` har tre segmenter.

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

## Ordningsgaranti og kommutative handlers

**Hvad RabbitMQ garanterer:** rækkefølgen er kun bevaret pr. kø, når der er *én* consumer og ingen redelivery.
Det holder ikke hos os:

* Hver service kører 2-5 pods (HPA), og alle pods lytter på samme kø (competing consumers). To events om samme
  booking kan derfor behandles samtidig i to pods, og den senere kan blive færdig først.
* En besked, der fejler, prøves igen (3 forsøg med backoff). Imens kan nyere beskeder på køen blive behandlet.
* Events om samme booking kommer fra forskellige producenter (`payment.*` fra payment-service, `booking.*` fra
  booking-service). Rækkefølgen på tværs af producenter er aldrig garanteret.

**Derfor er alle handlers, der ændrer tilstand, kommutative:** samme mængde events giver samme sluttilstand,
uanset rækkefølge. Duplikater håndteres som før af `processed_event`.

Hvert event har allerede envelope-feltet `occurredAt` (sat af producenten, når den ændring, eventet beskriver,
committes). Det bruges som ordningsnøgle; payloads er uændrede. Et event uden `occurredAt` behandles som "nu".

| Service         | Tilstand                                      | Regel                                                                                                                                                              |
|-----------------|-----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| baggage-service | `booking_snapshot.status` (`event_occurred_at`) | `CANCELLED` er terminal: et cancel vinder altid, og intet ændrer status tilbage. Ellers vinder det nyeste event; ældre events ignoreres.                                |
| flight-service  | `seat.is_available` (`held_by_booking`, `availability_event_at`) | `booking.cancelled` frigiver kun sædet, hvis det er den booking, der holder det. En aflyst booking kan aldrig tage sædet igen. Et `booking.confirmed`, der er ældre end sidst anvendte event, ignoreres. |
| booking-service | flight-snapshot `flight_status` / `gate` (`flight_status_event_at`, `gate_event_at`) | Ét tidsstempel pr. felt: et gammelt `flight.status.changed` må stadig sætte status, selv om et nyere `flight.gate.changed` er anvendt. Flight-status `CANCELLED` er terminal. |
| booking-service | `booking.status` ved `payment.*`              | `payment.failed` på en CONFIRMED booking kan kun skyldes omvendt levering (payment-service afviser nye betalinger, når én er COMPLETED). Bookingen annulleres, og `booking.cancelled` får payment-service til at refundere: samme slutresultat som i rækkefølge. |

Eksempel (flight-service, sæde 6D): A bekræftes (t1) og annulleres (t2), derefter bekræftes B (t3). Alle seks
rækkefølger ender med sædet optaget af B, fordi A's cancel ignoreres, når B holder sædet, og A's confirm er ældre
end B's.

Kendte grænser:

* Rækkefølgen bygger på producenternes ur. Pods på samme node (kind) deler ur; i et rigtigt cluster holder NTP
  urene inden for få millisekunder, hvilket er langt mindre end tiden mellem to ændringer af samme booking.
* To events med præcis samme `occurredAt` behandles i den rækkefølge, de ankommer (mikrosekund-opløsning, så det
  sker reelt ikke for to forskellige ændringer).
* Nye kolonner er `NULL` for eksisterende rækker, hvilket betyder "intet event anvendt endnu": første event vinder.

Tests: `*CommutativityTest` (unit, alle permutationer) og `Commutative*IntegrationTest` (gennem RabbitMQ i omvendt
rækkefølge) i booking-, flight- og baggage-service.

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
Lyttere: flight-service (sæde `is_available=false`), baggage-service (opretter/opdaterer `booking_snapshot`), notification-job (bekræftelsesmail).

### `booking.cancelled`
Samme payload med `"status": "CANCELLED"` og et ekstra felt `"reason"`, fx `"Flight cancelled"`
eller `"Cancelled by passenger"` eller `"Payment failed: <failureReason>"` (fx `"Payment failed: Insufficient funds"`).
Lyttere: flight-service (sæde frigives), payment-service (automatisk refund hvis COMPLETED payment findes),
baggage-service (snapshot → CANCELLED), notification-job (annulleringsmail).

### `booking.checkedin`
Samme payload med `"status": "CHECKED_IN"`.
Lyttere: baggage-service (snapshot → CHECKED_IN).

---

## Events fra payment-service

### `payment.completed`
```json
{
  "paymentId": 1, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK", "cardLast4": "4242"
}
```
Lyttere: booking-service (booking → CONFIRMED, publicerer `booking.confirmed`, opdaterer `booking_overview`).

### `payment.failed`
```json
{
  "paymentId": 2, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK", "cardLast4": "0000",
  "failureReason": "Insufficient funds"
}
```
Lyttere: booking-service (booking → CANCELLED, publicerer `booking.cancelled`, opdaterer `booking_overview`).

### `payment.refunded`
```json
{
  "paymentId": 1, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK"
}
```
Lyttere: booking-service (opdaterer betalingsstatus i `booking_overview`), notification-job (refund-mail).

---

## Events fra baggage-service

### `baggage.registered`
```json
{
  "tagNumber": "BAG-7F3K9Q2M", "bookingReference": "K7Q2ZP",
  "passengerName": "Anna Jensen", "flightNumber": "SK1501",
  "weightKg": 23.0, "type": "CHECKED", "status": "REGISTERED"
}
```

Lyttere: booking-service (tilføjer bagagetag i `booking_overview`).

### `baggage.status.changed`
```json
{
  "tagNumber": "BAG-7F3K9Q2M", "bookingReference": "K7Q2ZP", "flightNumber": "SK1501",
  "oldStatus": "REGISTERED", "newStatus": "LOADED", "location": "Belt 4"
}
```
Lyttere: booking-service (opdaterer bagagelinjens status og lokation i `booking_overview`).

---

## shop-service
Publicerer ingen events. Lytter på `flight.#` og logger `flight.gate.changed` (eventId + gate).
