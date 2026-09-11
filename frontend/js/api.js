// GraphQL client + one small API module per backend service.
// URLs come from js/config.js (window.AIRPORT_CONFIG).

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
 * POST a GraphQL document. Resolves with `data`.
 * Throws GraphQLError with `.code` taken from errors[0].extensions.code.
 */
export async function gql(url, query, variables = {}, serviceName = 'servicen') {
  if (!url) throw new GraphQLError(`Ingen URL konfigureret for ${serviceName} (se js/config.js)`, 'CONFIG_ERROR');
  let res;
  try {
    res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
  } catch (e) {
    throw new GraphQLError(`Kan ikke nå ${serviceName}`, 'NETWORK_ERROR');
  }
  let body = null;
  try { body = await res.json(); } catch (_) { /* non-JSON */ }
  if (body && Array.isArray(body.errors) && body.errors.length) {
    const first = body.errors[0];
    const code = (first.extensions && first.extensions.code) || 'UNKNOWN';
    throw new GraphQLError(first.message || 'Ukendt fejl', code, body.errors);
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
  price currency status createdAt updatedAt
  passenger { id firstName lastName email passportNumber dateOfBirth }
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
const BAGGAGE_FIELDS = `id tagNumber bookingReference passengerName flightNumber weightKg type status lastLocation updatedAt`;

export const baggageApi = {
  registerBaggage: (bookingReference, weightKg, type) => gql(cfg.BAGGAGE_URL, `
    mutation($bookingReference: String!, $weightKg: BigDecimal!, $type: BaggageType!) {
      registerBaggage(bookingReference: $bookingReference, weightKg: $weightKg, type: $type) { ${BAGGAGE_FIELDS} }
    }`, { bookingReference, weightKg, type }, 'baggage-service').then(d => d.registerBaggage),

  updateBaggageStatus: (tagNumber, status, location) => gql(cfg.BAGGAGE_URL, `
    mutation($tagNumber: String!, $status: BaggageStatus!, $location: String) {
      updateBaggageStatus(tagNumber: $tagNumber, status: $status, location: $location) { ${BAGGAGE_FIELDS} }
    }`, { tagNumber, status, location: location || null }, 'baggage-service').then(d => d.updateBaggageStatus),

  baggageByBooking: (reference) => gql(cfg.BAGGAGE_URL, `
    query($reference: String!) { baggageByBooking(reference: $reference) { ${BAGGAGE_FIELDS} } }`,
    { reference }, 'baggage-service').then(d => d.baggageByBooking),

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
};
