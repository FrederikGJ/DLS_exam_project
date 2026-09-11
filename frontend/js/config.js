// -----------------------------------------------------------------------------
// Service URLs - the ONLY place the frontend knows where the backends live.
//
// Local docker-compose: the services are published on localhost:8081-8085 and
// allow CORS from http://localhost:8080 (the nginx frontend).
//
// Kubernetes: this file is replaced by a ConfigMap mounted over js/config.js and
// uses relative paths behind the Ingress, e.g.
//   FLIGHT_URL: '/api/flights/graphql'
// No code changes are needed - only this file differs between environments.
// (Classic script on purpose: it must run before the ES module js/app.js.)
// -----------------------------------------------------------------------------
window.AIRPORT_CONFIG = {
  FLIGHT_URL:  'http://localhost:8081/api/flights/graphql',
  BOOKING_URL: 'http://localhost:8082/api/bookings/graphql',
  PAYMENT_URL: 'http://localhost:8083/api/payments/graphql',
  BAGGAGE_URL: 'http://localhost:8084/api/baggage/graphql',
  SHOP_URL:    'http://localhost:8085/api/shops/graphql'
};
