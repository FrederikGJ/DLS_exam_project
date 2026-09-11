#!/usr/bin/env bash
# End-to-end smoke test of Flow A-D against a running "docker compose up" stack.
# Requires: curl, jq. Usage: ./scripts/e2e-smoke.sh
set -euo pipefail

FLIGHT=${FLIGHT_URL:-http://localhost:8081/api/flights/graphql}
BOOKING=${BOOKING_URL:-http://localhost:8082/api/bookings/graphql}
PAYMENT=${PAYMENT_URL:-http://localhost:8083/api/payments/graphql}
BAGGAGE=${BAGGAGE_URL:-http://localhost:8084/api/baggage/graphql}
SHOP=${SHOP_URL:-http://localhost:8085/api/shops/graphql}

gql() { # gql <url> <query> [variables-json]
  local url=$1 query=$2 vars=${3:-'{}'}
  curl -sS -H 'Content-Type: application/json' "$url" \
       --data "$(jq -cn --arg q "$query" --argjson v "$vars" '{query:$q, variables:$v}')"
}
expect() { # expect <description> <actual> <expected>
  if [[ "$2" == "$3" ]]; then echo "  ok   $1 = $2"; else echo "  FAIL $1: expected '$3' got '$2'"; exit 1; fi
}
wait_for() { # wait_for <description> <cmd producing value> <expected> [seconds]
  local desc=$1 cmd=$2 expected=$3 secs=${4:-20} val=""
  for _ in $(seq 1 $((secs*2))); do
    val=$(eval "$cmd" 2>/dev/null || true)
    [[ "$val" == "$expected" ]] && { echo "  ok   $desc = $val"; return 0; }
    sleep 0.5
  done
  echo "  FAIL $desc: expected '$expected' got '$val'"; exit 1
}

echo "== Flow A: booking og betaling =="
FLIGHT_ID=$(gql "$FLIGHT" '{ flights(filter:{status:SCHEDULED}) { id flightNumber gate } }' | jq -r '.data.flights[0].id')
FLIGHT_NO=$(gql "$FLIGHT" '{ flights(filter:{status:SCHEDULED}) { id flightNumber } }' | jq -r '.data.flights[0].flightNumber')
SEAT=$(gql "$FLIGHT" "query(\$id:ID!){ availableSeats(flightId:\$id){ seatNumber } }" "{\"id\":\"$FLIGHT_ID\"}" | jq -r '.data.availableSeats[-1].seatNumber')
echo "  flight $FLIGHT_NO (id $FLIGHT_ID), seat $SEAT"
EMAIL="smoke-$(date +%s)@example.com"
CREATE=$(gql "$BOOKING" 'mutation($f:ID!,$s:String!,$p:PassengerInput!){ createBooking(flightId:$f, seatNumber:$s, passenger:$p){ bookingReference status price flightNumber } }' \
  "{\"f\":\"$FLIGHT_ID\",\"s\":\"$SEAT\",\"p\":{\"firstName\":\"Smoke\",\"lastName\":\"Tester\",\"email\":\"$EMAIL\",\"passportNumber\":\"P1234567\"}}")
REF=$(echo "$CREATE" | jq -r '.data.createBooking.bookingReference')
PRICE=$(echo "$CREATE" | jq -r '.data.createBooking.price')
expect "booking.status" "$(echo "$CREATE" | jq -r '.data.createBooking.status')" "PENDING_PAYMENT"
echo "  reference $REF price $PRICE"

DUP=$(gql "$BOOKING" 'mutation($f:ID!,$s:String!,$p:PassengerInput!){ createBooking(flightId:$f, seatNumber:$s, passenger:$p){ bookingReference } }' \
  "{\"f\":\"$FLIGHT_ID\",\"s\":\"$SEAT\",\"p\":{\"firstName\":\"Other\",\"lastName\":\"Person\",\"email\":\"other-$EMAIL\",\"passportNumber\":\"P7654321\"}}")
expect "duplicate seat -> code" "$(echo "$DUP" | jq -r '.errors[0].extensions.code')" "SEAT_TAKEN"

PAY=$(gql "$PAYMENT" 'mutation($r:String!,$a:BigDecimal!){ pay(bookingReference:$r, amount:$a, cardNumber:"4242 4242 4242 4242", expiry:"12/30", cvv:"123"){ id status cardLast4 } }' "{\"r\":\"$REF\",\"a\":$PRICE}")
expect "payment.status" "$(echo "$PAY" | jq -r '.data.pay.status')" "COMPLETED"
expect "payment.cardLast4" "$(echo "$PAY" | jq -r '.data.pay.cardLast4')" "4242"

wait_for "booking CONFIRMED" "gql '$BOOKING' 'query(\$r:String!){ bookingByReference(reference:\$r){ status } }' '{\"r\":\"$REF\"}' | jq -r '.data.bookingByReference.status'" "CONFIRMED"
wait_for "seat taken in flight-service" "gql '$FLIGHT' 'query(\$id:ID!,\$s:String!){ flight(id:\$id){ seat(seatNumber:\$s){ isAvailable } } }' '{\"id\":\"$FLIGHT_ID\",\"s\":\"$SEAT\"}' | jq -r '.data.flight.seat.isAvailable'" "false"
wait_for "baggage snapshot CONFIRMED" "gql '$BAGGAGE' 'query(\$r:String!){ bookingSnapshot(reference:\$r){ status } }' '{\"r\":\"$REF\"}' | jq -r '.data.bookingSnapshot.status'" "CONFIRMED"

echo "== Flow A (negativ): kort der slutter på 0000 =="
SEAT2=$(gql "$FLIGHT" "query(\$id:ID!){ availableSeats(flightId:\$id){ seatNumber } }" "{\"id\":\"$FLIGHT_ID\"}" | jq -r '.data.availableSeats[-1].seatNumber')
REF2=$(gql "$BOOKING" 'mutation($f:ID!,$s:String!,$p:PassengerInput!){ createBooking(flightId:$f, seatNumber:$s, passenger:$p){ bookingReference } }' \
  "{\"f\":\"$FLIGHT_ID\",\"s\":\"$SEAT2\",\"p\":{\"firstName\":\"Poor\",\"lastName\":\"Payer\",\"email\":\"poor-$EMAIL\",\"passportNumber\":\"P0000001\"}}" | jq -r '.data.createBooking.bookingReference')
FAILPAY=$(gql "$PAYMENT" 'mutation($r:String!,$a:BigDecimal!){ pay(bookingReference:$r, amount:$a, cardNumber:"4000000000000000", expiry:"12/30", cvv:"123"){ status failureReason } }' "{\"r\":\"$REF2\",\"a\":$PRICE}")
expect "payment FAILED" "$(echo "$FAILPAY" | jq -r '.data.pay.status')" "FAILED"
expect "failureReason" "$(echo "$FAILPAY" | jq -r '.data.pay.failureReason')" "Insufficient funds"
wait_for "booking CANCELLED after failed payment" "gql '$BOOKING' 'query(\$r:String!){ bookingByReference(reference:\$r){ status } }' '{\"r\":\"$REF2\"}' | jq -r '.data.bookingByReference.status'" "CANCELLED"

echo "== Flow B: bagage =="
BAG=$(gql "$BAGGAGE" 'mutation($r:String!){ registerBaggage(bookingReference:$r, weightKg:23, type:CHECKED){ tagNumber status lastLocation } }' "{\"r\":\"$REF\"}")
TAG=$(echo "$BAG" | jq -r '.data.registerBaggage.tagNumber')
expect "baggage.status" "$(echo "$BAG" | jq -r '.data.registerBaggage.status')" "REGISTERED"
[[ "$TAG" =~ ^BAG-[A-Z0-9]{8}$ ]] && echo "  ok   tag $TAG" || { echo "  FAIL tag format $TAG"; exit 1; }
UPD=$(gql "$BAGGAGE" 'mutation($t:String!){ updateBaggageStatus(tagNumber:$t, status:LOADED, location:"Belt 4"){ status lastLocation } }' "{\"t\":\"$TAG\"}")
expect "baggage LOADED" "$(echo "$UPD" | jq -r '.data.updateBaggageStatus.status')" "LOADED"
expect "baggage location" "$(echo "$UPD" | jq -r '.data.updateBaggageStatus.lastLocation')" "Belt 4"
TOOHEAVY=$(gql "$BAGGAGE" 'mutation($r:String!){ registerBaggage(bookingReference:$r, weightKg:33, type:CHECKED){ tagNumber } }' "{\"r\":\"$REF\"}")
expect "33 kg -> code" "$(echo "$TOOHEAVY" | jq -r '.errors[0].extensions.code')" "VALIDATION_ERROR"
expect "baggageByBooking count" "$(gql "$BAGGAGE" 'query($r:String!){ baggageByBooking(reference:$r){ tagNumber } }' "{\"r\":\"$REF\"}" | jq -r '.data.baggageByBooking | length')" "1"

echo "== Flow C: aflysning =="
CANCEL=$(gql "$FLIGHT" 'mutation($id:ID!){ updateFlightStatus(flightId:$id, status:CANCELLED){ status } }' "{\"id\":\"$FLIGHT_ID\"}")
expect "flight CANCELLED" "$(echo "$CANCEL" | jq -r '.data.updateFlightStatus.status')" "CANCELLED"
wait_for "booking CANCELLED" "gql '$BOOKING' 'query(\$r:String!){ bookingByReference(reference:\$r){ status flightStatus } }' '{\"r\":\"$REF\"}' | jq -r '.data.bookingByReference.status'" "CANCELLED"
wait_for "payment REFUNDED" "gql '$PAYMENT' 'query(\$r:String!){ paymentsByBooking(reference:\$r){ status } }' '{\"r\":\"$REF\"}' | jq -r '.data.paymentsByBooking[0].status'" "REFUNDED"
wait_for "baggage at RETURN_DESK" "gql '$BAGGAGE' 'query(\$t:String!){ baggage(tagNumber:\$t){ status lastLocation } }' '{\"t\":\"$TAG\"}' | jq -r '.data.baggage.lastLocation'" "RETURN_DESK"
wait_for "seat released" "gql '$FLIGHT' 'query(\$id:ID!,\$s:String!){ flight(id:\$id){ seat(seatNumber:\$s){ isAvailable } } }' '{\"id\":\"$FLIGHT_ID\",\"s\":\"$SEAT\"}' | jq -r '.data.flight.seat.isAvailable'" "true"

echo "== Flow D: navigation =="
FROM=$(gql "$SHOP" '{ navNodes { id name } }' | jq -r '.data.navNodes[] | select(.name=="Security T2") | .id')
TO=$(gql "$SHOP" '{ navNodes { id name } }' | jq -r '.data.navNodes[] | select(.name=="Gate B12") | .id')
ROUTE=$(gql "$SHOP" 'query($f:ID!,$t:ID!){ route(fromNodeId:$f, toNodeId:$t, accessibleOnly:true){ totalDistanceM estimatedMinutes steps { instruction distance } shopsAlongRoute { name } } }' "{\"f\":\"$FROM\",\"t\":\"$TO\"}")
STEPS=$(echo "$ROUTE" | jq -r '.data.route.steps | length')
DIST=$(echo "$ROUTE" | jq -r '.data.route.totalDistanceM')
[[ "$STEPS" -ge 2 && "$DIST" -gt 0 ]] && echo "  ok   route Security T2 -> Gate B12: $STEPS steps, $DIST m, $(echo "$ROUTE" | jq -r '.data.route.estimatedMinutes') min, shops: $(echo "$ROUTE" | jq -c '[.data.route.shopsAlongRoute[].name]')" || { echo "  FAIL route: $ROUTE"; exit 1; }
echo "$ROUTE" | jq -r '.data.route.steps[] | "       - \(.instruction) (\(.distance) m)"'
expect "searchShops(duty) >= 1" "$(gql "$SHOP" '{ searchShops(text:"duty"){ name } }' | jq -r '.data.searchShops | length > 0')" "true"

echo
echo "ALL FLOWS OK"
