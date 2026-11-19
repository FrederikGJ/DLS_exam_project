// Demo for the serverless notification function (ticket 12): creates and pays N bookings (default 5) through the
// Ingress. Each payment gives a booking.confirmed event -> one message on the "notifications" queue -> KEDA starts
// one notification-job Job per message.
//
//   k6 run scripts/notifications-demo.js
//   k6 run -e COUNT=10 -e BASE_URL=http://localhost:8090 scripts/notifications-demo.js
//
// Watch the Jobs in another terminal:  kubectl -n airport get jobs -l app.kubernetes.io/name=notification-job -w
// See k8s/README.md, section "Serverless: notifikationer med KEDA".
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COUNT = Number(__ENV.COUNT || 5);

export const options = {
  scenarios: {
    bookings: { executor: 'shared-iterations', vus: 1, iterations: COUNT, maxDuration: '2m' },
  },
  thresholds: { checks: ['rate==1.0'] },
};

const PARAMS = { headers: { 'Content-Type': 'application/json' } };

function gql(service, query, variables) {
  const res = http.post(`${BASE_URL}/api/${service}/graphql`, JSON.stringify({ query, variables }), PARAMS);
  const body = res.json();
  if (res.status !== 200 || body.errors) {
    fail(`${service}: HTTP ${res.status} ${JSON.stringify(body.errors || body)}`);
  }
  return body.data;
}

export function setup() {
  const flights = gql('flights', '{ flights(filter: { status: SCHEDULED }) { id flightNumber } }').flights;
  if (flights.length === 0) fail('no SCHEDULED flights');
  return { flightId: flights[0].id, flightNumber: flights[0].flightNumber };
}

export default function (data) {
  const seats = gql('flights', 'query($id: ID!) { availableSeats(flightId: $id) { seatNumber } }', { id: data.flightId })
    .availableSeats;
  // pick from the back, a different seat per iteration (the seat is only marked taken after booking.confirmed)
  const seat = seats[seats.length - 1 - __ITER].seatNumber;
  const n = `${Date.now()}${__ITER}`;

  const booking = gql('bookings',
    'mutation($f: ID!, $s: String!, $p: PassengerInput!) { createBooking(flightId: $f, seatNumber: $s, passenger: $p) { bookingReference price } }',
    { f: data.flightId, s: seat, p: { firstName: 'Demo', lastName: `Passenger ${__ITER + 1}`, email: `demo${n}@example.com`, passportNumber: 'P1234567' } },
  ).createBooking;

  const payment = gql('payments',
    'mutation($r: String!, $a: BigDecimal!) { pay(bookingReference: $r, amount: $a, cardNumber: "4242 4242 4242 4242", expiry: "12/30", cvv: "123") { status } }',
    { r: booking.bookingReference, a: booking.price },
  ).pay;

  check(payment, { 'payment COMPLETED': (p) => p.status === 'COMPLETED' });
  console.log(`booking ${booking.bookingReference} seat ${seat} on ${data.flightNumber}: payment ${payment.status}`);
}
