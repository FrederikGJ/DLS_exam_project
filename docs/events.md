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
| payment-service | `payment-service.booking-events`   | `booking.#`     | `payment-service.dlq`  |
| baggage-service | `baggage-service.booking-events`   | `booking.#`     | `baggage-service.dlq`  |
| baggage-service | `baggage-service.flight-events`    | `flight.#`      | `baggage-service.dlq`  |
| shop-service    | `shop-service.flight-events`       | `flight.#`      | `shop-service.dlq`     |

> Vi bruger `#` (0..n ord) og ikke `*` (præcis 1 ord), fordi fx `flight.status.changed` har tre segmenter.

Dead-letter: Køerne er oprettet med `x-dead-letter-exchange: airport.events.dlx` (direct exchange) og
`x-dead-letter-routing-key: <service-navn>`. Spring AMQP retry (stateless, 3 forsøg med backoff) afviser
beskeden uden requeue efter 3. fejl, hvorefter RabbitMQ flytter den til `<service>.dlq`.

Idempotens: hver service gemmer behandlede `eventId` i tabellen `processed_event` i samme transaktion som
den tilstandsændring eventet medfører. Et event med kendt `eventId` ignoreres (logges som "skipping").

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
Lyttere: flight-service (sæde `is_available=false`), baggage-service (opretter/opdaterer `booking_snapshot`).

### `booking.cancelled`
Samme payload med `"status": "CANCELLED"` og et ekstra felt `"reason"`, fx `"Flight cancelled"`
eller `"Cancelled by passenger"` eller `"Payment failed"`.
Lyttere: flight-service (sæde frigives), payment-service (automatisk refund hvis COMPLETED payment findes),
baggage-service (snapshot → CANCELLED).

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
Lyttere: booking-service (booking → CONFIRMED, publicerer `booking.confirmed`).

### `payment.failed`
```json
{
  "paymentId": 2, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK", "cardLast4": "0000",
  "failureReason": "Insufficient funds"
}
```
Lyttere: booking-service (booking → CANCELLED, publicerer `booking.cancelled`).

### `payment.refunded`
```json
{
  "paymentId": 1, "bookingReference": "K7Q2ZP",
  "amount": 899.00, "currency": "DKK"
}
```
Lyttere: ingen (informativt; kan vises under "Min booking" via `paymentsByBooking`).

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

### `baggage.status.changed`
```json
{
  "tagNumber": "BAG-7F3K9Q2M", "bookingReference": "K7Q2ZP", "flightNumber": "SK1501",
  "oldStatus": "REGISTERED", "newStatus": "LOADED", "location": "Belt 4"
}
```
Lyttere: ingen krævede.

---

## shop-service
Publicerer ingen events. Lytter på `flight.#` og logger `flight.gate.changed` (eventId + gate).
