#!/usr/bin/env bash
# Contract drift check for docs/asyncapi.yaml (dev plan DP-37), run by the CI job `contracts` and locally:
#   ./scripts/check-asyncapi.sh
# 1. every event name a service declares in its *Events class (public static final String X = "a.b") is the address
#    of a channel in the AsyncAPI document, and
# 2. every queue a service or notification-job configures (app.messaging.queues.* in application.yml, and the
#    constants in notification-job's JobConfig/Topology) is the address of a channel too.
# A new event or queue in the code therefore fails CI until the contract describes it. Schema validation of the
# document itself is done by the AsyncAPI CLI in the same job.
set -uo pipefail
cd "$(dirname "$0")/.."

SPEC=docs/asyncapi.yaml
addresses=$(grep -E '^    address: ' "$SPEC" | sed -E 's/^    address: //' | sort -u)
missing=0

check() { # check <kind> <name> <source>
  if ! grep -qxF "$2" <<< "$addresses"; then
    echo "MISSING in $SPEC: $1 '$2' (declared in $3)"
    missing=1
  fi
}

events=0
for file in */src/main/java/dk/airport/*/messaging/*Events.java; do
  for name in $(grep -oE 'public static final String [A-Z_]+ = "[a-z]+(\.[a-z]+)+"' "$file" | grep -oE '"[^"]+"' | tr -d '"'); do
    check event "$name" "$file"; events=$((events + 1))
  done
done

queues=0
for file in */src/main/resources/application.yml; do
  # the lines below `queues:` inside app.messaging, e.g. "      payment-events: booking-service.payment-events"
  for name in $(awk '/^    queues:/{q=1; next} q && /^      [a-z-]+: /{print $2; next} q{q=0}' "$file"); do
    check queue "$name" "$file"; queues=$((queues + 1))
  done
done
# notification-job is plain Java without application.yml: its queue and DLQ are constants
job=notification-job/src/main/java/dk/airport/notification
for name in $(grep -hoE '(DEFAULT_QUEUE|DEAD_LETTER_QUEUE) = "[^"]+"' "$job/JobConfig.java" "$job/Topology.java" \
              | grep -oE '"[^"]+"' | tr -d '"'); do
  check queue "$name" "$job"; queues=$((queues + 1))
done

if [ "$events" -eq 0 ] || [ "$queues" -eq 0 ]; then
  echo "found no events ($events) or queues ($queues) in the code - the check itself is broken"
  exit 1
fi
[ "$missing" -eq 0 ] && echo "OK: $events event declarations and $queues queue declarations are all in $SPEC"
exit "$missing"
