-- Seed data: two terminals (T1, T2), a connected navigation graph (45 nodes) and 17 shops.
-- Coordinates are on a 1000x600 canvas: T1 uses x 40..440, T2 uses x 560..960.
-- Floor 1 nodes share the x/y area of the elevator so a client can draw floors separately.
-- Edges are stored ONCE; the routing code treats every edge as bidirectional.

-- ---------------------------------------------------------------- Terminal 1, floor 0
INSERT INTO nav_node (name, terminal, floor, x, y, type) VALUES
  ('Entrance T1',          'T1', 0,  60, 300, 'ENTRANCE'),
  ('Check-in T1',          'T1', 0, 130, 300, 'JUNCTION'),
  ('Security T1',          'T1', 0, 200, 300, 'SECURITY'),
  ('Junction T1 Central',  'T1', 0, 270, 300, 'JUNCTION'),
  ('Junction T1 North',    'T1', 0, 270, 180, 'JUNCTION'),
  ('Junction T1 South',    'T1', 0, 270, 420, 'JUNCTION'),
  ('Gate A3',              'T1', 0, 340, 110, 'GATE'),
  ('Gate A12',             'T1', 0, 420, 180, 'GATE'),
  ('Gate A14',             'T1', 0, 420, 420, 'GATE'),
  ('Gate A20',             'T1', 0, 340, 500, 'GATE'),
  ('Elevator T1',          'T1', 0, 200, 380, 'ELEVATOR'),
  ('Duty Free T1',         'T1', 0, 340, 240, 'SHOP'),
  ('Joe & The Juice T1',   'T1', 0, 340, 300, 'SHOP'),
  ('Lego Store T1',        'T1', 0, 340, 360, 'SHOP'),
  ('7-Eleven T1',          'T1', 0, 130, 240, 'SHOP'),
  ('Apotek T1',            'T1', 0, 130, 360, 'SHOP'),
  ('Baggage Wrap T1',      'T1', 0,  60, 240, 'SHOP');

-- Terminal 1, floor 1
INSERT INTO nav_node (name, terminal, floor, x, y, type) VALUES
  ('Etage 1 T1',           'T1', 1, 200, 380, 'JUNCTION'),
  ('SAS Lounge',           'T1', 1, 270, 380, 'SHOP'),
  ('Restaurant Himmel',    'T1', 1, 130, 380, 'SHOP');

-- ---------------------------------------------------------------- Walkway between terminals
INSERT INTO nav_node (name, terminal, floor, x, y, type) VALUES
  ('Gangbro T1-T2',        'T1', 0, 500, 300, 'JUNCTION');

-- ---------------------------------------------------------------- Terminal 2, floor 0
INSERT INTO nav_node (name, terminal, floor, x, y, type) VALUES
  ('Entrance T2',          'T2', 0, 940, 300, 'ENTRANCE'),
  ('Check-in T2',          'T2', 0, 870, 300, 'JUNCTION'),
  ('Security T2',          'T2', 0, 800, 300, 'SECURITY'),
  ('Junction T2 Central',  'T2', 0, 730, 300, 'JUNCTION'),
  ('Junction T2 North',    'T2', 0, 730, 180, 'JUNCTION'),
  ('Junction T2 South',    'T2', 0, 730, 420, 'JUNCTION'),
  ('Junction Pier B',      'T2', 0, 640, 180, 'JUNCTION'),
  ('Junction Pier C',      'T2', 0, 640, 420, 'JUNCTION'),
  ('Gate B7',              'T2', 0, 660, 110, 'GATE'),
  ('Gate B12',             'T2', 0, 580, 140, 'GATE'),
  ('Gate B15',             'T2', 0, 580, 220, 'GATE'),
  ('Gate C5',              'T2', 0, 660, 500, 'GATE'),
  ('Gate C10',             'T2', 0, 580, 460, 'GATE'),
  ('Gate C21',             'T2', 0, 580, 380, 'GATE'),
  ('Elevator T2',          'T2', 0, 800, 380, 'ELEVATOR'),
  ('Duty Free T2',         'T2', 0, 730, 240, 'SHOP'),
  ('Starbucks T2',         'T2', 0, 730, 360, 'SHOP'),
  ('Hugo Boss T2',         'T2', 0, 660, 240, 'SHOP'),
  ('Lagkagehuset T2',      'T2', 0, 660, 360, 'SHOP'),
  ('Valutaveksling T2',    'T2', 0, 870, 240, 'SHOP'),
  ('WHSmith T2',           'T2', 0, 870, 360, 'SHOP'),
  ('Pier C Café',          'T2', 0, 690, 470, 'SHOP');

-- Terminal 2, floor 1
INSERT INTO nav_node (name, terminal, floor, x, y, type) VALUES
  ('Etage 1 T2',           'T2', 1, 800, 380, 'JUNCTION'),
  ('Aspire Lounge',        'T2', 1, 730, 380, 'SHOP'),
  ('Restaurant Nordic Table', 'T2', 1, 870, 380, 'SHOP');

-- ---------------------------------------------------------------- Edges
-- (from, to, distance_m, accessible). Stairs are accessible = FALSE.
INSERT INTO nav_edge (from_node_id, to_node_id, distance_m, accessible)
SELECT f.id, t.id, v.dist, v.acc
FROM (VALUES
  -- T1 landside / central spine
  ('Entrance T1',         'Check-in T1',          70, TRUE),
  ('Entrance T1',         'Baggage Wrap T1',      40, TRUE),
  ('Check-in T1',         '7-Eleven T1',          50, TRUE),
  ('Check-in T1',         'Apotek T1',            50, TRUE),
  ('Check-in T1',         'Security T1',          70, TRUE),
  ('Security T1',         'Junction T1 Central',  70, TRUE),
  ('Security T1',         'Elevator T1',          80, TRUE),
  -- T1 airside
  ('Junction T1 Central', 'Junction T1 North',   130, TRUE),   -- via Duty Free is 120 m
  ('Junction T1 Central', 'Duty Free T1',         60, TRUE),
  ('Duty Free T1',        'Junction T1 North',    60, TRUE),
  ('Junction T1 Central', 'Joe & The Juice T1',   60, TRUE),
  ('Duty Free T1',        'Joe & The Juice T1',   40, TRUE),
  ('Junction T1 Central', 'Lego Store T1',        70, TRUE),
  ('Junction T1 Central', 'Junction T1 South',   120, TRUE),
  ('Junction T1 North',   'Gate A3',             100, TRUE),
  ('Junction T1 North',   'Gate A12',            150, TRUE),
  ('Duty Free T1',        'Gate A12',             90, TRUE),
  ('Junction T1 South',   'Gate A14',            150, TRUE),
  ('Junction T1 South',   'Gate A20',            100, TRUE),
  -- T1 floor 1: elevator (accessible) vs stairs (not accessible)
  ('Elevator T1',         'Etage 1 T1',           15, TRUE),
  ('Junction T1 South',   'Etage 1 T1',           25, FALSE),
  ('Etage 1 T1',          'SAS Lounge',           60, TRUE),
  ('Etage 1 T1',          'Restaurant Himmel',    60, TRUE),
  -- Walkway between the terminals
  ('Junction T1 Central', 'Gangbro T1-T2',       150, TRUE),
  ('Gangbro T1-T2',       'Junction T2 Central', 150, TRUE),
  -- T2 landside / central spine
  ('Entrance T2',         'Check-in T2',          70, TRUE),
  ('Check-in T2',         'Valutaveksling T2',    60, TRUE),
  ('Check-in T2',         'WHSmith T2',           60, TRUE),
  ('Check-in T2',         'Security T2',          70, TRUE),
  ('Security T2',         'Junction T2 Central',  70, TRUE),
  ('Security T2',         'Elevator T2',          80, TRUE),
  -- T2 airside
  ('Junction T2 Central', 'Junction T2 North',   130, TRUE),   -- via Duty Free is 120 m
  ('Junction T2 Central', 'Duty Free T2',         60, TRUE),
  ('Duty Free T2',        'Junction T2 North',    60, TRUE),
  ('Junction T2 Central', 'Starbucks T2',         60, TRUE),
  ('Junction T2 Central', 'Junction T2 South',   120, TRUE),
  ('Junction T2 North',   'Hugo Boss T2',         90, TRUE),
  ('Duty Free T2',        'Hugo Boss T2',         70, TRUE),
  ('Junction T2 North',   'Junction Pier B',      90, TRUE),
  ('Junction T2 North',   'Gate B7',             100, TRUE),
  ('Junction Pier B',     'Gate B12',             70, TRUE),
  ('Junction Pier B',     'Gate B15',             70, TRUE),
  ('Junction T2 South',   'Lagkagehuset T2',      90, TRUE),
  ('Starbucks T2',        'Lagkagehuset T2',      70, TRUE),
  ('Junction T2 South',   'Junction Pier C',      90, TRUE),
  ('Junction T2 South',   'Gate C5',             100, TRUE),
  ('Junction Pier C',     'Gate C10',             70, TRUE),
  ('Junction Pier C',     'Gate C21',             70, TRUE),
  ('Junction Pier C',     'Pier C Café',          60, TRUE),
  -- T2 floor 1: elevator (accessible) vs stairs (not accessible)
  ('Elevator T2',         'Etage 1 T2',           15, TRUE),
  ('Junction T2 South',   'Etage 1 T2',           25, FALSE),
  ('Etage 1 T2',          'Aspire Lounge',        70, TRUE),
  ('Etage 1 T2',          'Restaurant Nordic Table', 70, TRUE)
) AS v(from_name, to_name, dist, acc)
JOIN nav_node f ON f.name = v.from_name
JOIN nav_node t ON t.name = v.to_name;

-- ---------------------------------------------------------------- Shops
INSERT INTO shop (name, category, terminal, zone, floor, opening_hours, description, node_id)
SELECT v.name, v.category, v.terminal, v.zone, v.floor, v.hours, v.descr, n.id
FROM (VALUES
  ('Duty Free Copenhagen T1', 'DUTY_FREE', 'T1', 'Airside Central', 0, '05:00-23:30', 'Tax free parfume, spiritus, slik og skandinavisk design.',          'Duty Free T1'),
  ('Joe & The Juice',         'FOOD',      'T1', 'Airside Central', 0, '05:30-22:00', 'Juice, kaffe og sandwiches.',                                      'Joe & The Juice T1'),
  ('LEGO Store',              'RETAIL',    'T1', 'Airside Central', 0, '07:00-21:00', 'Officiel LEGO butik med eksklusive lufthavnssæt.',                'Lego Store T1'),
  ('7-Eleven',                'FOOD',      'T1', 'Landside',        0, '24/7',        'Kiosk med kaffe, snacks og rejseartikler. Åben døgnet rundt.',      '7-Eleven T1'),
  ('Apoteket',                'SERVICE',   'T1', 'Landside',        0, '08:00-20:00', 'Apotek med håndkøbsmedicin og rejsemedicin.',                        'Apotek T1'),
  ('Baggage Wrap & Storage',  'SERVICE',   'T1', 'Landside',        0, '06:00-22:00', 'Bagageindpakning og opbevaring.',                                  'Baggage Wrap T1'),
  ('SAS Lounge',              'LOUNGE',    'T1', 'Etage 1',         1, '05:00-22:00', 'Lounge for SAS Plus/Business og Star Alliance Gold.',               'SAS Lounge'),
  ('Restaurant Himmel',       'FOOD',      'T1', 'Etage 1',         1, '11:00-22:00', 'Nordisk restaurant med udsigt over forpladsen.',                    'Restaurant Himmel'),
  ('Duty Free Copenhagen T2', 'DUTY_FREE', 'T2', 'Airside Central', 0, '05:00-23:30', 'Tax free butik med parfume, kosmetik, vin og chokolade.',           'Duty Free T2'),
  ('Starbucks',               'FOOD',      'T2', 'Airside Central', 0, '05:00-22:30', 'Kaffe, te og bagværk.',                                            'Starbucks T2'),
  ('Hugo Boss',               'RETAIL',    'T2', 'Pier B',          0, '07:00-21:00', 'Herre- og dametøj, accessories.',                                  'Hugo Boss T2'),
  ('Lagkagehuset',            'FOOD',      'T2', 'Pier C',          0, '05:30-21:00', 'Dansk bageri: kanelsnegle, rugbrød og kaffe.',                     'Lagkagehuset T2'),
  ('Forex Valutaveksling',    'SERVICE',   'T2', 'Landside',        0, '06:00-22:00', 'Valutaveksling og udbetaling af kontanter.',                        'Valutaveksling T2'),
  ('WHSmith',                 'RETAIL',    'T2', 'Landside',        0, '06:00-22:00', 'Bøger, magasiner, elektronik og rejsetilbehør.',                    'WHSmith T2'),
  ('Pier C Café',             'FOOD',      'T2', 'Pier C',          0, '06:00-20:00', 'Lille café ved C-gaterne med smørrebrød og øl.',                    'Pier C Café'),
  ('Aspire Lounge',           'LOUNGE',    'T2', 'Etage 1',         1, '05:00-21:00', 'Pay-in lounge med buffet, drinks og hvilestole.',                  'Aspire Lounge'),
  ('Nordic Table',            'FOOD',      'T2', 'Etage 1',         1, '11:00-23:00', 'Restaurant med nordisk menu og bar. Sen aftenåbning.',               'Restaurant Nordic Table')
) AS v(name, category, terminal, zone, floor, hours, descr, node_name)
JOIN nav_node n ON n.name = v.node_name;
