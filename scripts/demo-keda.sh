#!/usr/bin/env bash
# KEDA demo (dev plan DP-21): creates 5 bookings and pays them through the Ingress, which puts 10 booking events
# (booking.created + booking.confirmed) in the queue `notifications`, and then shows every 2 s how KEDA starts
# notification-job Jobs, how the queue is drained and how the finished Jobs disappear again.
# Requires: curl, jq, kubectl against the kind cluster with k8s/overlays/demo applied and KEDA installed
# (see k8s/README.md "Demo-overlay: AI og serverless (KEDA)"). Usage: ./scripts/demo-keda.sh
set -euo pipefail

BASE=${BASE_URL:-http://localhost:8090}
FLIGHT=$BASE/api/flights/graphql
BOOKING=$BASE/api/bookings/graphql
PAYMENT=$BASE/api/payments/graphql
KEYCLOAK=${KEYCLOAK_URL:-$BASE/auth}
NS=${NAMESPACE:-airport}
BOOKINGS=${BOOKINGS:-5}
WATCH_SECONDS=${WATCH_SECONDS:-120}

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
queue_length() { # messages ready + unacknowledged in `notifications`
  kubectl -n "$NS" exec rabbitmq-0 -- rabbitmqctl list_queues name messages --no-table-headers --quiet 2>/dev/null \
    | awk '$1 == "notifications" {print $2}'
}
jobs_line() { # "<active> aktive, <succeeded> færdige, <total> jobs"
  kubectl -n "$NS" get jobs -l app.kubernetes.io/name=notification-job -o json 2>/dev/null | jq -r '
    [.items[] | (.status.active // 0)] as $a | [.items[] | (.status.succeeded // 0)] as $s |
    "\($a | add // 0) aktive, \($s | add // 0) færdige, \(.items | length) jobs i alt"'
}

command -v kubectl >/dev/null || { echo "kubectl mangler" >&2; exit 1; }
kubectl -n "$NS" get scaledjob notification-job >/dev/null 2>&1 \
  || { echo "ScaledJob notification-job findes ikke - kubectl apply -k k8s/overlays/demo/ (og KEDA installeret?)" >&2; exit 1; }

# On a fresh cluster KEDA's first connection to RabbitMQ can fail while RabbitMQ is still starting; the ScaledJob is
# then not Ready and KEDA retries with a back-off. Events would pile up unnoticed, so wait for Ready first.
for _ in $(seq 1 60); do
  ready=$(kubectl -n "$NS" get scaledjob notification-job -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')
  [[ "$ready" == "True" ]] && break
  sleep 5
done
[[ "$ready" == "True" ]] || { echo "ScaledJob notification-job er ikke Ready efter 5 min (kubectl -n $NS describe scaledjob notification-job)" >&2; exit 1; }

echo "== Før: kø og jobs =="
echo "  notifications: $(queue_length) beskeder · $(jobs_line)"

echo "== $BOOKINGS bookinger + betalinger som anna =="
TOKEN=$(token anna anna)
[[ -n "$TOKEN" ]] || { echo "  kunne ikke hente token fra $KEYCLOAK" >&2; exit 1; }
FLIGHT_ID=$(gql "$FLIGHT" '{ flights(filter:{status:SCHEDULED}) { id } }' | jq -r '.data.flights[0].id')
mapfile -t SEATS < <(gql "$FLIGHT" 'query($id:ID!){ availableSeats(flightId:$id){ seatNumber } }' "{\"id\":\"$FLIGHT_ID\"}" \
  | jq -r '.data.availableSeats | reverse | .[].seatNumber')
made=0
for seat in "${SEATS[@]}"; do
  (( made >= BOOKINGS )) && break
  created=$(gql "$BOOKING" 'mutation($f:ID!,$s:String!,$p:PassengerInput!){ createBooking(flightId:$f, seatNumber:$s, passenger:$p){ bookingReference price } }' \
    "{\"f\":\"$FLIGHT_ID\",\"s\":\"$seat\",\"p\":{\"firstName\":\"Anna\",\"lastName\":\"Jensen\",\"email\":\"anna@example.com\",\"passportNumber\":\"P1234567\"}}" "$TOKEN")
  ref=$(echo "$created" | jq -r '.data.createBooking.bookingReference // empty')
  [[ -z "$ref" ]] && continue                     # seat reserved by an earlier, unpaid booking: try the next one
  price=$(echo "$created" | jq -r '.data.createBooking.price')
  status=$(gql "$PAYMENT" 'mutation($r:String!,$a:BigDecimal!){ pay(bookingReference:$r, amount:$a, cardNumber:"4242 4242 4242 4242", expiry:"12/30", cvv:"123"){ status } }' \
    "{\"r\":\"$ref\",\"a\":$price}" "$TOKEN" | jq -r '.data.pay.status')
  made=$((made + 1))
  echo "  $made. booking $ref sæde $seat betalt: $status"
done
(( made == BOOKINGS )) || { echo "  kun $made bookinger lykkedes" >&2; exit 1; }

echo "== KEDA i gang (poll hvert 5. s, 1 job pr. 5 beskeder, max 3) - følges i ${WATCH_SECONDS} s =="
started=$(date +%s)
emptied=""
seen_job=""
while (( $(date +%s) - started < WATCH_SECONDS )); do
  t=$(( $(date +%s) - started ))
  q=$(queue_length); j=$(jobs_line)
  printf '  %3ss  kø: %-3s  %s\n' "$t" "${q:-?}" "$j"
  [[ "$j" != "0 aktive, 0 færdige, 0 jobs i alt" ]] && seen_job=yes
  [[ -z "$emptied" && -n "$seen_job" && "$q" == "0" ]] && emptied=$t
  if [[ -n "$emptied" && "$j" == "0 aktive, 0 færdige, 0 jobs i alt" ]]; then
    echo "== Køen var tom efter ${emptied} s, og alle jobs er væk igen efter ${t} s =="
    echo "   Mails i loggen fra de seneste jobs forsvinder med dem; se dem undervejs med:"
    echo "   kubectl -n $NS logs -l app.kubernetes.io/name=notification-job --tail=20"
    exit 0
  fi
  sleep 2
done
echo "== Stoppede efter ${WATCH_SECONDS} s (kø tom efter: ${emptied:-ikke tom}) =="
exit 1
