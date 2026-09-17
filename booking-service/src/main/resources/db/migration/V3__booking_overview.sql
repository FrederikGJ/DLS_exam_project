-- CQRS read model (dev plan DP-28, see docs/architecture.md "CQRS: booking_overview").
-- One row per booking holding everything "Min booking" shows, so the page is answered by one primary-key lookup:
-- the booking and passenger (copied from the write model `booking` + `passenger` in the command's own transaction)
-- plus the booking's payments and baggage (projected from payment.* and baggage.* events of the owning services).
-- Only BookingOverviewProjector writes here; nothing in the write model reads it. No foreign keys on purpose: the
-- read model is derived data and could live in another database without code changes.
CREATE TABLE booking_overview (
    booking_id            BIGINT        PRIMARY KEY,               -- = booking.id
    booking_reference     VARCHAR(6)    NOT NULL UNIQUE,           -- lookup key of bookingOverview and of events
    status                VARCHAR(20)   NOT NULL,
    passenger_id          BIGINT        NOT NULL,
    passenger_email       VARCHAR(255)  NOT NULL,                  -- lower case, lookup key of myBookings
    passenger_first_name  VARCHAR(100)  NOT NULL,
    passenger_last_name   VARCHAR(100)  NOT NULL,
    passport_number       VARCHAR(20)   NOT NULL,
    date_of_birth         DATE,
    flight_id             BIGINT        NOT NULL,
    flight_number         VARCHAR(10)   NOT NULL,
    departure_time        TIMESTAMPTZ   NOT NULL,
    gate                  VARCHAR(10),
    flight_status         VARCHAR(20),
    seat_number           VARCHAR(5)    NOT NULL,
    price                 NUMERIC(10,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL,
    -- JSON arrays because the read side never filters on them, it only shows them: one row = the whole screen
    payments              JSONB         NOT NULL DEFAULT '[]',     -- [{paymentId, status, amount, ...}]
    baggage               JSONB         NOT NULL DEFAULT '[]',     -- [{tagNumber, type, weightKg, status, ...}]
    created_at            TIMESTAMPTZ   NOT NULL,                  -- booking.created_at
    updated_at            TIMESTAMPTZ   NOT NULL,                  -- booking.updated_at
    projected_at          TIMESTAMPTZ   NOT NULL DEFAULT now()     -- last time any projection changed the row
);
-- myBookings: a passenger's bookings, newest first
CREATE INDEX idx_booking_overview_passenger_email ON booking_overview (passenger_email, created_at DESC);
-- a passenger who books again with changed details updates the copy on all their rows
CREATE INDEX idx_booking_overview_passenger ON booking_overview (passenger_id);

-- Existing bookings get a row. Their payments and baggage live in payment-service and baggage-service and cannot be
-- read from here; they show up as soon as the next payment.* / baggage.* event for the booking arrives.
INSERT INTO booking_overview (booking_id, booking_reference, status, passenger_id, passenger_email,
                              passenger_first_name, passenger_last_name, passport_number, date_of_birth,
                              flight_id, flight_number, departure_time, gate, flight_status, seat_number,
                              price, currency, created_at, updated_at)
SELECT b.id, b.booking_reference, b.status, p.id, lower(p.email),
       p.first_name, p.last_name, p.passport_number, p.date_of_birth,
       b.flight_id, b.flight_number, b.departure_time, b.gate, b.flight_status, b.seat_number,
       b.price, b.currency, b.created_at, b.updated_at
FROM booking b
JOIN passenger p ON p.id = b.passenger_id;
