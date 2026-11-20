#!/usr/bin/env bash
# Contract drift check for docs/asyncapi.yaml (ticket 16), run in CI and locally:
#   ./scripts/check-asyncapi.sh
# 1. every event name a service declares - published (public static final String X = "a.b" in messaging/*Events.java)
#    or consumed (the same kind of constant in messaging/*Handler.java) - is the address of a channel in the document,
# 2. every queue a service configures (app.messaging.queues.* in application.yml, consumer queues and DLQ) is the
#    address of a channel too, and
# 3. the other way round: every channel address in the document is an event name or queue that exists in the code.
# A new event or queue in the code, or a stale one in the contract, therefore fails CI until the two agree. Schema
# validation of the document itself is done by the AsyncAPI CLI (asyncapi validate --fail-severity=warn).
set -uo pipefail
cd "$(dirname "$0")/.."

SPEC=docs/asyncapi.yaml
addresses=$(grep -E '^    address: ' "$SPEC" | sed -E 's/^    address: //' | sort -u)
missing=0
declared=""

check() { # check <kind> <name> <source>
  declared+="$2"$'\n'
  if ! grep -qxF "$2" <<< "$addresses"; then
    echo "MISSING in $SPEC: $1 '$2' (declared in $3)"
    missing=1
  fi
}

event_names() { # event_names <java file>: the dotted string constants, e.g. "booking.confirmed"
  grep -oE 'public static final String [A-Z_]+ = "[a-z]+(\.[a-z]+)+"' "$1" | grep -oE '"[^"]+"' | tr -d '"'
}

events=0
for file in */src/main/java/dk/airport/*/messaging/*Events.java; do
  for name in $(event_names "$file"); do
    check "published event" "$name" "$file"; events=$((events + 1))
  done
done
consumed=0
for file in */src/main/java/dk/airport/*/messaging/*Handler.java; do
  for name in $(event_names "$file"); do
    check "consumed event" "$name" "$file"; consumed=$((consumed + 1))
  done
done

queues=0
for file in */src/main/resources/application.yml; do
  # the lines below `queues:` inside app.messaging, e.g. "      payment-events: booking-service.payment-events"
  for name in $(awk '/^    queues:/{q=1; next} q && /^      [a-z-]+: /{print $2; next} q{q=0}' "$file"); do
    check queue "$name" "$file"; queues=$((queues + 1))
  done
done

if [ "$events" -eq 0 ] || [ "$consumed" -eq 0 ] || [ "$queues" -eq 0 ]; then
  echo "found no published events ($events), consumed events ($consumed) or queues ($queues) in the code" \
       "- the check itself is broken"
  exit 1
fi

for address in $addresses; do
  if ! grep -qxF "$address" <<< "$declared"; then
    echo "STALE in $SPEC: channel address '$address' is neither an event name nor a queue in the code"
    missing=1
  fi
done

[ "$missing" -eq 0 ] && echo "OK: $events published events, $consumed consumed event names and $queues queues" \
  "match the $(wc -l <<< "$addresses") channel addresses in $SPEC"
exit "$missing"
