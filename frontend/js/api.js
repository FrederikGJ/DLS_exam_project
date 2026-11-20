// GraphQL and REST clients + one small API module per backend service.
// URLs come from js/config.js (window.AIRPORT_CONFIG). Every request carries the Keycloak access token when the
// user is logged in (auth.js); UNAUTHORIZED/FORBIDDEN answers from the services are turned into Danish messages,
// and UNAUTHORIZED also sends the user to the login page and back to the current route.
import { getToken, login, username } from './auth.js';

const cfg = window.AIRPORT_CONFIG || {};
const workflowTraces = new Map();

function randomHex(bytes) {
  return Array.from(crypto.getRandomValues(new Uint8Array(bytes)), value => value.toString(16).padStart(2, '0')).join('');
}

function traceForBooking(reference, traceId) {
  const key = `airport.trace.${reference.trim().toUpperCase()}`;
  try {
    if (!traceId) traceId = sessionStorage.getItem(key);
  } catch (_) { /* In-memory fallback when session storage is blocked. */ }
  traceId ||= workflowTraces.get(key) || randomHex(16);
  workflowTraces.set(key, traceId);
  try { sessionStorage.setItem(key, traceId); } catch (_) { /* The current tab can still use the trace. */ }
  return traceId;
}

function addTrace(headers, traceId) {
  if (traceId) headers.traceparent = `00-${traceId}-${randomHex(8)}-01`;
}

export class GraphQLError extends Error {
  constructor(message, code = 'UNKNOWN', errors = []) {
    super(message);
    this.name = 'GraphQLError';
    this.code = code;
    this.errors = errors;
  }
}

/**
 * A fresh idempotency key (UUID v4) for one intended creation (registerBaggage). The page keeps it while the same
 * form is retried, so a double click or a retry after a lost response never creates the thing twice.
 * crypto.randomUUID() only exists in secure contexts (https or localhost); over plain http (http://airport.local)
 * a UUID v4 is built from crypto.getRandomValues(), which works everywhere.
 */
export function newIdempotencyKey() {
  if (globalThis.crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;                     // version 4
  b[8] = (b[8] & 0x3f) | 0x80;                     // variant 10xx
  const hex = [...b].map(x => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * POST a GraphQL document. Resolves with `data`.
 * Throws GraphQLError with `.code` taken from errors[0].extensions.code.
 */
export async function gql(url, query, variables = {}, serviceName = 'servicen', traceId) {
  if (!url) throw new GraphQLError(`Ingen URL konfigureret for ${serviceName} (se js/config.js)`, 'CONFIG_ERROR');
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  addTrace(headers, traceId);
  const token = await getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let res;
  try {
    res = await fetch(url, { method: 'POST', headers, body: JSON.stringify({ query, variables }) });
  } catch (_) {
    throw new GraphQLError(`Kan ikke nå ${serviceName}`, 'NETWORK_ERROR');
  }
  let body = null;
  try { body = await res.json(); } catch (_) { /* non-JSON */ }
  if (body && Array.isArray(body.errors) && body.errors.length) {
    const first = body.errors[0];
    const code = (first.extensions && first.extensions.code) || 'UNKNOWN';
    if (code === 'UNAUTHORIZED') {
      login();   // to Keycloak and back to this route; the user repeats the action once logged in
      throw new GraphQLError('Log ind for at fortsætte', code, body.errors);
    }
    if (code === 'FORBIDDEN') {
      throw new GraphQLError(`${username() || 'Din bruger'} har ikke rettighed til denne handling`, code, body.errors);
    }
    throw new GraphQLError(first.message || 'Ukendt fejl', code, body.errors);
  }
  if (res.status === 401) {
    login();   // the service rejected the token itself (expired, wrong issuer): a fresh login fixes it
    throw new GraphQLError('Din session er udløbet - log ind igen', 'UNAUTHORIZED');
  }
  if (!res.ok) {
    throw new GraphQLError(`${serviceName} svarede HTTP ${res.status}`, 'HTTP_' + res.status);
  }
  if (!body || typeof body !== 'object' || !('data' in body)) {
    throw new GraphQLError(`${serviceName} gav et uventet svar (ikke GraphQL)`, 'BAD_RESPONSE');
  }
  return body.data || {};
}

/** Call a JSON REST endpoint and expose the same error codes as gql(). */
async function rest(method, url, body, traceId) {
  if (!url) throw new GraphQLError('Ingen URL konfigureret for baggage-service (se js/config.js)', 'CONFIG_ERROR');
  const headers = { Accept: 'application/json, application/problem+json' };
  addTrace(headers, traceId);
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = await getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(url, { method, headers, body: JSON.stringify(body) });
  } catch (_) {
    throw new GraphQLError('Kan ikke nå baggage-service', 'NETWORK_ERROR');
  }
  const payload = await res.json().catch(() => null);
  if (res.ok) {
    if (payload && typeof payload === 'object') return payload;
    throw new GraphQLError('baggage-service gav et uventet svar', 'BAD_RESPONSE');
  }

  const code = payload?.code || { 401: 'UNAUTHORIZED', 403: 'FORBIDDEN' }[res.status] || `HTTP_${res.status}`;
  if (code === 'UNAUTHORIZED') {
    login();
    throw new GraphQLError('Log ind for at fortsætte', code);
  }
  if (code === 'FORBIDDEN') {
    throw new GraphQLError(`${username() || 'Din bruger'} har ikke rettighed til denne handling`, code);
  }
  throw new GraphQLError(payload?.detail || `baggage-service svarede HTTP ${res.status}`, code);
}

// ----------------------------------------------------------------- flight-service
const FLIGHT_FIELDS = `
  id flightNumber origin destination scheduledDeparture scheduledArrival gate status
  basePrice currency availableSeatCount
  airline { id iataCode name }
  aircraft { id model registration totalSeats }
`;

export const flightApi = {
  flights: (filter = {}) => gql(cfg.FLIGHT_URL, `
    query Flights($filter: FlightFilter) { flights(filter: $filter) { ${FLIGHT_FIELDS} } }`,
    { filter }, 'flight-service').then(d => d.flights),

  flightWithSeats: (id) => gql(cfg.FLIGHT_URL, `
    query Flight($id: ID!) {
      flight(id: $id) { ${FLIGHT_FIELDS} seats { id seatNumber seatClass isAvailable price } }
    }`, { id }, 'flight-service').then(d => d.flight),

  availableSeats: (flightId) => gql(cfg.FLIGHT_URL, `
    query Seats($flightId: ID!) { availableSeats(flightId: $flightId) { id seatNumber seatClass isAvailable price } }`,
    { flightId }, 'flight-service').then(d => d.availableSeats),

  updateFlightStatus: (flightId, status) => gql(cfg.FLIGHT_URL, `
    mutation($flightId: ID!, $status: FlightStatus!) {
      updateFlightStatus(flightId: $flightId, status: $status) { ${FLIGHT_FIELDS} }
    }`, { flightId, status }, 'flight-service').then(d => d.updateFlightStatus),

  updateGate: (flightId, gate) => gql(cfg.FLIGHT_URL, `
    mutation($flightId: ID!, $gate: String!) {
      updateGate(flightId: $flightId, gate: $gate) { ${FLIGHT_FIELDS} }
    }`, { flightId, gate }, 'flight-service').then(d => d.updateGate),
};

// ---------------------------------------------------------------- booking-service
const BOOKING_FIELDS = `
  id bookingReference flightId flightNumber departureTime gate flightStatus seatNumber
  price currency status createdAt updatedAt
  passenger { id firstName lastName email passportNumber dateOfBirth }
`;

export const bookingApi = {
  async createBooking(flightId, seatNumber, passenger) {
    const traceId = randomHex(16);
    const { createBooking: booking } = await gql(cfg.BOOKING_URL, `
      mutation($flightId: ID!, $seatNumber: String!, $passenger: PassengerInput!) {
        createBooking(flightId: $flightId, seatNumber: $seatNumber, passenger: $passenger) { ${BOOKING_FIELDS} }
      }`, { flightId, seatNumber, passenger }, 'booking-service', traceId);
    if (booking?.bookingReference) traceForBooking(booking.bookingReference, traceId);
    return booking;
  },

  bookingByReference: (reference) => gql(cfg.BOOKING_URL, `
    query($reference: String!) { bookingByReference(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.bookingByReference),

  bookingOverview: (reference) => gql(cfg.BOOKING_URL, `
    query($reference: String!) {
      bookingOverview(reference: $reference) {
        booking { ${BOOKING_FIELDS} }
        latestPaymentStatus
        baggageCount
        payments { paymentId status amount currency cardLast4 failureReason createdAt statusAt }
        baggage { tagNumber status type weightKg lastLocation updatedAt }
      }
    }`, { reference }, 'booking-service').then(d => d.bookingOverview),

  bookingsByPassenger: (email) => gql(cfg.BOOKING_URL, `
    query($email: String!) { bookingsByPassenger(email: $email) { ${BOOKING_FIELDS} } }`,
    { email }, 'booking-service').then(d => d.bookingsByPassenger),

  cancelBooking: (reference) => gql(cfg.BOOKING_URL, `
    mutation($reference: String!) { cancelBooking(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.cancelBooking),

  checkIn: (reference) => gql(cfg.BOOKING_URL, `
    mutation($reference: String!) { checkIn(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.checkIn),
};

// ---------------------------------------------------------------- payment-service
const PAYMENT_FIELDS = `id bookingReference amount currency cardLast4 status failureReason createdAt`;

export const paymentApi = {
  pay: (bookingReference, amount, cardNumber, expiry, cvv) => gql(cfg.PAYMENT_URL, `
    mutation($bookingReference: String!, $amount: BigDecimal!, $cardNumber: String!, $expiry: String!, $cvv: String!) {
      pay(bookingReference: $bookingReference, amount: $amount, cardNumber: $cardNumber, expiry: $expiry, cvv: $cvv) {
        ${PAYMENT_FIELDS}
      }
    }`, { bookingReference, amount, cardNumber, expiry, cvv }, 'payment-service', traceForBooking(bookingReference)).then(d => d.pay),

  paymentsByBooking: (reference) => gql(cfg.PAYMENT_URL, `
    query($reference: String!) { paymentsByBooking(reference: $reference) { ${PAYMENT_FIELDS} } }`,
    { reference }, 'payment-service').then(d => d.paymentsByBooking),

  refund: (paymentId) => gql(cfg.PAYMENT_URL, `
    mutation($paymentId: ID!) { refund(paymentId: $paymentId) { ${PAYMENT_FIELDS} } }`,
    { paymentId }, 'payment-service').then(d => d.refund),
};

// ---------------------------------------------------------------- baggage-service
const BAGGAGE_FIELDS = `id tagNumber bookingReference passengerName flightNumber weightKg type status lastLocation updatedAt`;

// Compose and Kubernetes use the same baggage-service origin for GraphQL and REST.
const baggageRestUrl = (path) => cfg.BAGGAGE_URL
  ? new URL(`/api/baggage/v1${path}`, new URL(cfg.BAGGAGE_URL, window.location.href)).href
  : null;

export const baggageApi = {
  registerBaggage: (bookingReference, weightKg, type, idempotencyKey = null) => gql(cfg.BAGGAGE_URL, `
    mutation($bookingReference: String!, $weightKg: BigDecimal!, $type: BaggageType!, $idempotencyKey: String) {
      registerBaggage(bookingReference: $bookingReference, weightKg: $weightKg, type: $type,
                      idempotencyKey: $idempotencyKey) { ${BAGGAGE_FIELDS} }
    }`, { bookingReference, weightKg, type, idempotencyKey }, 'baggage-service', traceForBooking(bookingReference))
    .then(d => d.registerBaggage),

  updateBaggageStatus: (tagNumber, status, location) =>
    rest('PATCH', baggageRestUrl(`/baggage/${encodeURIComponent(tagNumber)}/status`),
      { status, location: location || null }),

  baggageByBooking: (reference) =>
    rest('GET', baggageRestUrl(`/bookings/${encodeURIComponent(reference)}/baggage`)),

  baggage: (tagNumber) => gql(cfg.BAGGAGE_URL, `
    query($tagNumber: String!) { baggage(tagNumber: $tagNumber) { ${BAGGAGE_FIELDS} } }`,
    { tagNumber }, 'baggage-service').then(d => d.baggage),

  bookingSnapshot: (reference) => gql(cfg.BAGGAGE_URL, `
    query($reference: String!) { bookingSnapshot(reference: $reference) { bookingReference passengerName flightNumber status } }`,
    { reference }, 'baggage-service').then(d => d.bookingSnapshot),
};

// ------------------------------------------------------------------- shop-service
const NODE_FIELDS = `id name terminal floor x y type`;
const SHOP_FIELDS = `id name category terminal zone floor openingHours description openNow node { ${NODE_FIELDS} }`;

export const shopApi = {
  shops: (filter = {}) => gql(cfg.SHOP_URL, `
    query($filter: ShopFilter) { shops(filter: $filter) { ${SHOP_FIELDS} } }`,
    { filter }, 'shop-service').then(d => d.shops),

  searchShops: (text) => gql(cfg.SHOP_URL, `
    query($text: String!) { searchShops(text: $text) { ${SHOP_FIELDS} } }`,
    { text }, 'shop-service').then(d => d.searchShops),

  navNodes: (terminal = null, floor = null) => gql(cfg.SHOP_URL, `
    query($terminal: String, $floor: Int) { navNodes(terminal: $terminal, floor: $floor) { ${NODE_FIELDS} } }`,
    { terminal, floor }, 'shop-service').then(d => d.navNodes),

  navEdges: (terminal = null) => gql(cfg.SHOP_URL, `
    query($terminal: String) { navEdges(terminal: $terminal) { id distanceM accessible from { id floor } to { id floor } } }`,
    { terminal }, 'shop-service').then(d => d.navEdges),

  route: (fromNodeId, toNodeId, accessibleOnly = false) => gql(cfg.SHOP_URL, `
    query($from: ID!, $to: ID!, $acc: Boolean) {
      route(fromNodeId: $from, toNodeId: $to, accessibleOnly: $acc) {
        totalDistanceM estimatedMinutes
        steps { instruction distance node { ${NODE_FIELDS} } }
        shopsAlongRoute { ${SHOP_FIELDS} }
      }
    }`, { from: fromNodeId, to: toNodeId, acc: accessibleOnly }, 'shop-service').then(d => d.route),

  // AI: "hvor kan jeg købe kaffe på vej til gate B12?" -> shop + destination + route (aiUsed / fallbackReason)
  askRoute: (question, fromNodeId, accessibleOnly = false) => gql(cfg.SHOP_URL, `
    query($q: String!, $from: ID!, $acc: Boolean) {
      askRoute(question: $q, fromNodeId: $from, accessibleOnly: $acc) {
        aiUsed fallbackReason
        shop { ${SHOP_FIELDS} }
        destination { ${NODE_FIELDS} }
        route {
          totalDistanceM estimatedMinutes
          steps { instruction distance node { ${NODE_FIELDS} } }
          shopsAlongRoute { ${SHOP_FIELDS} }
        }
      }
    }`, { q: question, from: fromNodeId, acc: accessibleOnly }, 'shop-service').then(d => d.askRoute),
};
