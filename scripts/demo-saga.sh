#!/usr/bin/env bash
# Saga demo (dev plan DP-32): the two compensations of the booking saga against a running compose stack.
#   1. A booking that is not paid within PAYMENT_TIMEOUT is cancelled by booking-service ("Payment not received
#      within ..."), which frees the seat.
#   2. The passenger pays anyway (the payment page was still open): payment-service accepts the card, booking-service
#      sees payment.completed for a CANCELLED booking and publishes booking.payment.rejected, and payment-service
#      refunds exactly that payment. "Min booking" (bookingOverview) ends up showing CANCELLED + REFUNDED.
# The default timeout is 15 minutes; start booking-service with a short one first:
#   PAYMENT_TIMEOUT=30s docker compose up -d --wait booking-service
# Requires: curl, jq. Usage: ./scripts/demo-saga.sh   (kind: BOOKING_URL etc. as for e2e-smoke.sh)
set -euo pipefail

FLIGHT=${FLIGHT_URL:-http://localhost:8081/api/flights/graphql}
BOOKING=${BOOKING_URL:-http://localhost:8082/api/bookings/graphql}
PAYMENT=${PAYMENT_URL:-http://localhost:8083/api/payments/graphql}
KEYCLOAK=${KEYCLOAK_URL:-http://localhost:8180}
MAX_WAIT=${MAX_WAIT_SECONDS:-1200}

token() { # token <username> <password>
  curl -sS -d client_id=airport-frontend -d grant_type=password -d "username=$1" -d "password=$2" \
       "$KEYCLOAK/realms/airport/protocol/openid-connect/token" | jq -r '.access_token // empty'
}
gql() { # gql <url> <query> [variables-json] [token]
  local url=$1 query=$2 vars=${3:-'{}'} tok=${4:-}
  local auth=()
  [[ -n "$tok" ]] && auth=(-H "Authorization: Bearer $tok")
  curl -sS -H 'Content-Type: application/json' ${auth[@]+"${auth[@]}"} "$url" \
       --data "$(jq -cn --arg q "$query" --argjson v "$vars" '{query:$q, variables:$v}')"
}
overview() { # overview <reference> -> "status | cancellationReason | payment statuses"
  gql "$BOOKING" 'query($r:String!){ bookingOverview(reference:$r){ status cancellationReason payments { status } } }' \
      "{\"r\":\"$1\"}" "$(token anna anna)" \
    | jq -r '.data.bookingOverview | "\(.status) | \(.cancellationReason // "-") | payments \([.payments[].status])"'
}

TOKEN=$(token anna anna)
[[ -n "$TOKEN" ]] || { echo "FAIL: no token from $KEYCLOAK"; exit 1; }

FLIGHT_ID=$(gql "$FLIGHT" '{ flights(filter:{status:SCHEDULED}) { id } }' | jq -r '.data.flights[0].id')
SEAT=$(gql "$FLIGHT" 'query($id:ID!){ availableSeats(flightId:$id){ seatNumber } }' "{\"id\":\"$FLIGHT_ID\"}" \
       | jq -r '.data.availableSeats[-2].seatNumber')
CREATE=$(gql "$BOOKING" 'mutation($f:ID!,$s:String!,$p:PassengerInput!){ createBooking(flightId:$f, seatNumber:$s, passenger:$p){ bookingReference price paymentDueAt } }' \
  "{\"f\":\"$FLIGHT_ID\",\"s\":\"$SEAT\",\"p\":{\"firstName\":\"Saga\",\"lastName\":\"Demo\",\"email\":\"anna@example.com\",\"passportNumber\":\"P2718281\"}}" "$TOKEN")
REF=$(echo "$CREATE" | jq -r '.data.createBooking.bookingReference')
PRICE=$(echo "$CREATE" | jq -r '.data.createBooking.price')
[[ "$REF" != "null" ]] || { echo "FAIL: createBooking: $CREATE"; exit 1; }
echo "== 1. Booking $REF (flight $FLIGHT_ID, seat $SEAT) is PENDING_PAYMENT, pay before $(echo "$CREATE" | jq -r '.data.createBooking.paymentDueAt')"

start=$(date +%s)
while :; do
  status=$(gql "$BOOKING" 'query($r:String!){ bookingByReference(reference:$r){ status cancellationReason } }' "{\"r\":\"$REF\"}")
  [[ "$(echo "$status" | jq -r '.data.bookingByReference.status')" == "CANCELLED" ]] && break
  (( $(date +%s) - start > MAX_WAIT )) && { echo "FAIL: still not cancelled after ${MAX_WAIT}s"; exit 1; }
  sleep 2
done
echo "   after $(( $(date +%s) - start )) s: CANCELLED - $(echo "$status" | jq -r '.data.bookingByReference.cancellationReason')"
echo "   Min booking: $(overview "$REF")"

echo "== 2. The passenger pays anyway"
PAY=$(gql "$PAYMENT" 'mutation($r:String!,$a:BigDecimal!){ pay(bookingReference:$r, amount:$a, cardNumber:"4242424242424242", expiry:"12/30", cvv:"123"){ id status } }' \
  "{\"r\":\"$REF\",\"a\":$PRICE}" "$TOKEN")
echo "   payment $(echo "$PAY" | jq -r '.data.pay.id'): $(echo "$PAY" | jq -r '.data.pay.status')"
for _ in $(seq 1 30); do
  refunded=$(gql "$PAYMENT" 'query($r:String!){ paymentsByBooking(reference:$r){ status } }' "{\"r\":\"$REF\"}" "$TOKEN" \
             | jq -r '.data.paymentsByBooking[0].status')
  [[ "$refunded" == "REFUNDED" ]] && break
  sleep 0.5
done
[[ "$refunded" == "REFUNDED" ]] || { echo "FAIL: payment is $refunded, expected REFUNDED"; exit 1; }
echo "   payment-service: REFUNDED (booking-service published booking.payment.rejected)"
sleep 1
echo "   Min booking: $(overview "$REF")"
echo
echo "SAGA COMPENSATIONS OK"
