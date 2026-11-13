#!/usr/bin/env bash
# Full-flow smoke test against the running system (docker compose --profile apps up -d --build):
# a promotion created over HTTP in promotion-service must show up as tasks in
# store-task-service, through the outbox, Kafka and the consumer; cancelling it must clear them.
set -euo pipefail

PROMOTIONS=${PROMOTIONS:-http://localhost:8081}
TASKS=${TASKS:-http://localhost:8082}
STORE="smoke-$(date +%s)"

wait_until() { # <description> <seconds> <command...>
  local what=$1 seconds=$2
  shift 2
  for _ in $(seq "$seconds"); do
    if "$@"; then echo "ok: $what"; return 0; fi
    sleep 1
  done
  echo "FAILED: timed out after ${seconds}s waiting for $what" >&2
  return 1
}

healthy() { curl -fs "$1/actuator/health" | grep -q '"status":"UP"'; }

# Open tasks of the smoke-test store; "until" far ahead so tasks due later count too.
open_tasks() { curl -fs "$TASKS/stores/$STORE/tasks?until=2099-12-31"; }
open_task_count() { open_tasks | grep -o '"promotionId":"'"$PROMOTION_ID"'"' | wc -l | tr -d ' '; }
has_open_tasks() { [ "$(open_task_count)" = "$1" ]; }

wait_until "promotion-service healthy" 120 healthy "$PROMOTIONS"
wait_until "store-task-service healthy" 120 healthy "$TASKS"

PROMOTION_ID=$(
  curl -fs "$PROMOTIONS/promotions" -H 'Content-Type: application/json' -d '{
    "title": "Alle kipfilet", "label": "25% korting", "kind": "PERCENT_OFF", "percent": 25,
    "validFrom": "2026-11-16", "validUntil": "2026-11-22",
    "storeIds": ["'"$STORE"'"], "productIds": ["product-42"]
  }' | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
)
echo "created promotion $PROMOTION_ID for store $STORE"

wait_until "put-up and take-down tasks in store-task-service" 60 has_open_tasks 2

curl -fs -X POST "$PROMOTIONS/promotions/$PROMOTION_ID/cancel" > /dev/null
echo "cancelled promotion $PROMOTION_ID"

wait_until "tasks cancelled (none open)" 60 has_open_tasks 0

echo "smoke test passed"
