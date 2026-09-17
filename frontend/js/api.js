// GraphQL + REST client and one small API module per backend service.
// URLs come from js/config.js (window.AIRPORT_CONFIG). Every request carries the Keycloak access token when the
// user is logged in (auth.js); UNAUTHORIZED/FORBIDDEN answers from the services are turned into Danish messages,
// and UNAUTHORIZED also sends the user to the login page and back to the current route.
// Four services are pure GraphQL; baggage-service is called over its versioned REST API v1 (see rest() below).
import { getToken, login, username } from './auth.js';

const cfg = window.AIRPORT_CONFIG || {};

export class GraphQLError extends Error {
  constructor(message, code = 'UNKNOWN', errors = []) {
    super(message);
    this.name = 'GraphQLError';
    this.code = code;
    this.errors = errors;
  }
}

/**
 * Call a versioned REST endpoint (currently only baggage-service /api/baggage/v1).
 *
 * Errors are RFC 9457 problem details (`application/problem+json`) whose `code` member uses the same vocabulary as
 * GraphQL's errors[].extensions.code, so the pages keep one error model: the same GraphQLError with `.code` is
 * thrown, UNAUTHORIZED sends the user to the login page and FORBIDDEN becomes a Danish message.
 * `body` is serialised as JSON when given; the parsed response body is returned (null for 204).
 */
export async function rest(method, url, body = null, serviceName = 'servicen', extraHeaders = {}) {
  if (!url) throw new GraphQLError(`Ingen URL konfigureret for ${serviceName} (se js/config.js)`, 'CONFIG_ERROR');
  const headers = { Accept: 'application/json', ...extraHeaders };
  if (body !== null) headers['Content-Type'] = 'application/json';
  const token = await getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let res;
  try {
    res = await fetch(url, { method, headers, body: body === null ? undefined : JSON.stringify(body) });
  } catch (_) {
    throw new GraphQLError(`Kan ikke nå ${serviceName}`, 'NETWORK_ERROR');
  }
  let payload = null;
  try { payload = await res.json(); } catch (_) { /* 204 No Content or a non-JSON error page */ }
  if (res.ok) return payload;

  const code = (payload && payload.code) || (res.status === 401 ? 'UNAUTHORIZED' : 'HTTP_' + res.status);
  if (code === 'UNAUTHORIZED') {
    login();   // to Keycloak and back to this route; the user repeats the action once logged in
    throw new GraphQLError('Log ind for at fortsætte', code);
  }
  if (code === 'FORBIDDEN') {
    throw new GraphQLError(`${username() || 'Din bruger'} har ikke rettighed til denne handling`, code);
  }
  throw new GraphQLError((payload && (payload.detail || payload.title))
    || `${serviceName} svarede HTTP ${res.status}`, code);
}

/**
 * A fresh idempotency key: one per intended write (e.g. per filled-in form), sent again unchanged when the same write
 * is retried, so a double click or a retry after a lost response never creates the thing twice (dev plan DP-30).
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
export async function gql(url, query, variables = {}, serviceName = 'servicen') {
  if (!url) throw new GraphQLError(`Ingen URL konfigureret for ${serviceName} (se js/config.js)`, 'CONFIG_ERROR');
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
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
  price currency status cancellationReason paymentDueAt createdAt updatedAt
  passenger { id firstName lastName email passportNumber dateOfBirth }
`;

const OVERVIEW_FIELDS = `
  bookingId bookingReference flightId flightNumber departureTime gate flightStatus seatNumber
  price currency status cancellationReason createdAt updatedAt projectedAt
  passenger { firstName lastName email passportNumber dateOfBirth }
  payments { paymentId status amount currency cardLast4 failureReason createdAt updatedAt }
  baggage { tagNumber type weightKg status lastLocation registeredAt updatedAt }
`;

export const bookingApi = {
  createBooking: (flightId, seatNumber, passenger) => gql(cfg.BOOKING_URL, `
    mutation($flightId: ID!, $seatNumber: String!, $passenger: PassengerInput!) {
      createBooking(flightId: $flightId, seatNumber: $seatNumber, passenger: $passenger) { ${BOOKING_FIELDS} }
    }`, { flightId, seatNumber, passenger }, 'booking-service').then(d => d.createBooking),

  bookingByReference: (reference) => gql(cfg.BOOKING_URL, `
    query($reference: String!) { bookingByReference(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.bookingByReference),

  bookingsByPassenger: (email) => gql(cfg.BOOKING_URL, `
    query($email: String!) { bookingsByPassenger(email: $email) { ${BOOKING_FIELDS} } }`,
    { email }, 'booking-service').then(d => d.bookingsByPassenger),

  cancelBooking: (reference) => gql(cfg.BOOKING_URL, `
    mutation($reference: String!) { cancelBooking(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.cancelBooking),

  checkIn: (reference) => gql(cfg.BOOKING_URL, `
    mutation($reference: String!) { checkIn(reference: $reference) { ${BOOKING_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.checkIn),

  // Read model (CQRS): booking + passenger + payments + baggage from booking-service's booking_overview in one call,
  // instead of one call each to booking-, payment- and baggage-service. Payments and baggage are projected from those
  // services' events and can lag them by about a second. Requires login.
  bookingOverview: (reference) => gql(cfg.BOOKING_URL, `
    query($reference: String!) { bookingOverview(reference: $reference) { ${OVERVIEW_FIELDS} } }`,
    { reference }, 'booking-service').then(d => d.bookingOverview),

  myBookings: () => gql(cfg.BOOKING_URL, `
    query { myBookings { bookingReference status flightNumber departureTime seatNumber } }`,
    {}, 'booking-service').then(d => d.myBookings),
};

// ---------------------------------------------------------------- payment-service
const PAYMENT_FIELDS = `id bookingReference amount currency cardLast4 status failureReason createdAt`;

export const paymentApi = {
  pay: (bookingReference, amount, cardNumber, expiry, cvv) => gql(cfg.PAYMENT_URL, `
    mutation($bookingReference: String!, $amount: BigDecimal!, $cardNumber: String!, $expiry: String!, $cvv: String!) {
      pay(bookingReference: $bookingReference, amount: $amount, cardNumber: $cardNumber, expiry: $expiry, cvv: $cvv) {
        ${PAYMENT_FIELDS}
      }
    }`, { bookingReference, amount, cardNumber, expiry, cvv }, 'payment-service').then(d => d.pay),

  paymentsByBooking: (reference) => gql(cfg.PAYMENT_URL, `
    query($reference: String!) { paymentsByBooking(reference: $reference) { ${PAYMENT_FIELDS} } }`,
    { reference }, 'payment-service').then(d => d.paymentsByBooking),

  refund: (paymentId) => gql(cfg.PAYMENT_URL, `
    mutation($paymentId: ID!) { refund(paymentId: $paymentId) { ${PAYMENT_FIELDS} } }`,
    { paymentId }, 'payment-service').then(d => d.refund),
};

// ---------------------------------------------------------------- baggage-service
// The four baggage operations go through the REST API v1 (/api/baggage/v1) - the same service, the same rules and
// the same error codes as the GraphQL API, but a versioned HTTP resource per operation. The REST answer has exactly
// the fields of the GraphQL type Baggage, so the pages did not change. bookingSnapshot has no REST counterpart and
// stays on GraphQL, which is why the baggage page talks to both APIs (visible in the browser's network tab).
const v1 = (path) => (cfg.BAGGAGE_REST_URL ? cfg.BAGGAGE_REST_URL + path : '');

export const baggageApi = {
  // idempotencyKey: see newIdempotencyKey() - the same key for a retry of the same registration
  registerBaggage: (bookingReference, weightKg, type, idempotencyKey) =>
    rest('POST', v1('/baggage'), { bookingReference, weightKg, type }, 'baggage-service',
      idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),

  updateBaggageStatus: (tagNumber, status, location) =>
    rest('PATCH', v1(`/baggage/${encodeURIComponent(tagNumber)}/status`),
      { status, location: location || null }, 'baggage-service'),

  baggageByBooking: (reference) =>
    rest('GET', v1(`/bookings/${encodeURIComponent(reference)}/baggage`), null, 'baggage-service'),

  // REST answers 404 for an unknown tag; the page expects null like the GraphQL query gave it.
  baggage: (tagNumber) =>
    rest('GET', v1(`/baggage/${encodeURIComponent(tagNumber)}`), null, 'baggage-service')
      .catch(err => { if (err.code === 'NOT_FOUND') return null; throw err; }),

  bookingSnapshot: (reference) => gql(cfg.BAGGAGE_URL, `
    query($reference: String!) { bookingSnapshot(reference: $reference) { bookingReference passengerName flightNumber status } }`,
    { reference }, 'baggage-service').then(d => d.bookingSnapshot),
};

// ------------------------------------------------------------------- shop-service
const NODE_FIELDS = `id name terminal floor x y type`;
const SHOP_FIELDS = `id name category terminal zone floor openingHours description openNow node { ${NODE_FIELDS} }`;
const ROUTE_FIELDS = `
  totalDistanceM estimatedMinutes
  steps { instruction distance node { ${NODE_FIELDS} } }
  shopsAlongRoute { ${SHOP_FIELDS} }
`;

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
      route(fromNodeId: $from, toNodeId: $to, accessibleOnly: $acc) { ${ROUTE_FIELDS} }
    }`, { from: fromNodeId, to: toNodeId, acc: accessibleOnly }, 'shop-service').then(d => d.route),

  // "Spørg om vej": a free-text question is interpreted by shop-service's local language model (Ollama) into a shop
  // (+ optional destination) and answered with the same Route shape as `route`. aiUsed=false + fallbackReason when
  // the model was not available and the keyword fallback answered instead. Can take a few seconds.
  askRoute: (question, fromNodeId, accessibleOnly = false) => gql(cfg.SHOP_URL, `
    query($q: String!, $from: ID!, $acc: Boolean) {
      askRoute(question: $q, fromNodeId: $from, accessibleOnly: $acc) {
        interpretation aiUsed fallbackReason model
        shop { ${SHOP_FIELDS} }
        toNode { ${NODE_FIELDS} }
        route { ${ROUTE_FIELDS} }
      }
    }`, { q: question, from: fromNodeId, acc: accessibleOnly }, 'shop-service').then(d => d.askRoute),
};
