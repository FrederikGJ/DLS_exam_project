-- Seed data: 3 airlines, 5 aircraft, 10 departures from CPH with generated seats.
-- Departure times are relative to the first start-up so that flights are always upcoming.

INSERT INTO airline (iata_code, name, country) VALUES
  ('SK', 'Scandinavian Airlines', 'Denmark'),
  ('DY', 'Norwegian Air Shuttle', 'Norway'),
  ('LH', 'Lufthansa', 'Germany');

INSERT INTO aircraft (registration, model, total_seats, airline_id) VALUES
  ('OY-KAM', 'Airbus A320neo',     180, (SELECT id FROM airline WHERE iata_code = 'SK')),
  ('SE-ROX', 'Airbus A321LR',      150, (SELECT id FROM airline WHERE iata_code = 'SK')),
  ('LN-NGB', 'Boeing 737-800',     186, (SELECT id FROM airline WHERE iata_code = 'DY')),
  ('D-AIUA', 'Airbus A320-200',    168, (SELECT id FROM airline WHERE iata_code = 'LH')),
  ('D-AECA', 'Embraer E190',       100, (SELECT id FROM airline WHERE iata_code = 'LH'));

-- helper: base = next full hour
WITH base AS (SELECT date_trunc('hour', now()) + interval '1 hour' AS t)
INSERT INTO flight (flight_number, airline_id, aircraft_id, origin, destination,
                    scheduled_departure, scheduled_arrival, gate, status, base_price)
SELECT v.flight_number,
       (SELECT id FROM airline  WHERE iata_code = v.airline),
       (SELECT id FROM aircraft WHERE registration = v.reg),
       'CPH', v.destination,
       base.t + v.dep, base.t + v.dep + v.dur,
       v.gate, v.status, v.price
FROM base, (VALUES
  ('SK1501', 'SK', 'OY-KAM', 'LHR', interval '2 hours',            interval '1 hour 55 minutes', 'A12', 'SCHEDULED',  899.00),
  ('SK1409', 'SK', 'SE-ROX', 'ARN', interval '3 hours 30 minutes', interval '1 hour 10 minutes', 'A3',  'SCHEDULED',  649.00),
  ('SK0459', 'SK', 'OY-KAM', 'OSL', interval '5 hours',            interval '1 hour 10 minutes', 'A14', 'SCHEDULED',  699.00),
  ('SK0925', 'SK', 'SE-ROX', 'JFK', interval '1 day 6 hours',      interval '8 hours 40 minutes','C21', 'SCHEDULED', 3999.00),
  ('DY3450', 'DY', 'LN-NGB', 'BCN', interval '4 hours 15 minutes', interval '3 hours 5 minutes', 'B12', 'SCHEDULED',  549.00),
  ('DY1104', 'DY', 'LN-NGB', 'CDG', interval '1 day 2 hours',      interval '2 hours',           'B7',  'SCHEDULED',  599.00),
  ('DY1050', 'DY', 'LN-NGB', 'HEL', interval '6 hours',            interval '1 hour 30 minutes', 'B15', 'DELAYED',    579.00),
  ('LH0831', 'LH', 'D-AIUA', 'FRA', interval '1 hour',             interval '1 hour 25 minutes', 'C5',  'BOARDING',   749.00),
  ('LH2431', 'LH', 'D-AECA', 'MUC', interval '7 hours',            interval '1 hour 40 minutes', 'C10', 'SCHEDULED',  799.00),
  ('LH0833', 'LH', 'D-AIUA', 'FRA', interval '2 days 1 hour',      interval '1 hour 25 minutes', 'C5',  'SCHEDULED',  729.00)
) AS v(flight_number, airline, reg, destination, dep, dur, gate, status, price);

-- Seats: rows of 6 (A-F). Row 1-2 are BUSINESS, the rest ECONOMY.
INSERT INTO seat (flight_id, seat_number, seat_class, is_available)
SELECT f.id,
       r.row_no::text || l.letter,
       CASE WHEN r.row_no <= 2 THEN 'BUSINESS' ELSE 'ECONOMY' END,
       TRUE
FROM flight f
JOIN aircraft a ON a.id = f.aircraft_id
CROSS JOIN LATERAL generate_series(1, CEIL(a.total_seats / 6.0)::int) AS r(row_no)
CROSS JOIN (VALUES ('A', 1), ('B', 2), ('C', 3), ('D', 4), ('E', 5), ('F', 6)) AS l(letter, idx)
WHERE (r.row_no - 1) * 6 + l.idx <= a.total_seats
ORDER BY f.id, r.row_no, l.idx;
