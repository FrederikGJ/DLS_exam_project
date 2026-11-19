CREATE TABLE booking_overview (
    booking_reference     VARCHAR(6)  PRIMARY KEY,
    booking               JSONB       NOT NULL,
    payments              JSONB       NOT NULL DEFAULT '{}',
    baggage               JSONB       NOT NULL DEFAULT '{}',
    latest_payment_status VARCHAR(20),
    baggage_count         INTEGER     NOT NULL DEFAULT 0 CHECK (baggage_count >= 0)
);

INSERT INTO booking_overview (booking_reference, booking)
SELECT b.booking_reference, (to_jsonb(b) - 'passenger_id') || jsonb_build_object('passenger', to_jsonb(p))
FROM booking b
JOIN passenger p ON p.id = b.passenger_id;
