#!/usr/bin/env bash
# Full-flow smoke test against the running system (docker compose --profile apps up -d --build):
# a promotion created over HTTP in promotion-service must show up as tasks in
# store-task-service, through the outbox, Kafka and the consumer; cancelling it must clear them.
# Along the way it edits the promotion (ETag / If-Match) to add a second store. Requests carry
# real signed tokens from scripts/dev-jwt.sh, and access control is checked too.
set -euo pipefail

PROMOTIONS=${PROMOTIONS:-http://localhost:8081}
TASKS=${TASKS:-http://localhost:8082}
STORE="smoke-$(date +%s)"
SECOND_STORE="$STORE-b"
# Relative to today: a promotion that has already ended can't be edited, so fixed dates would
# make this test fail once they're in the past.
days_from_today() { python3 -c "import datetime, sys; print(datetime.date.today() + datetime.timedelta(days=int(sys.argv[1])))" "$1"; }
VALID_FROM=$(days_from_today 3)
VALID_UNTIL=$(days_from_today 9)
DEV_JWT="$(dirname "$0")/dev-jwt.sh"

HEAD_OFFICE=$("$DEV_JWT" token '"scope":"promotions:read promotions:write"')
READ_ONLY=$("$DEV_JWT" token '"scope":"promotions:read"')
EMPLOYEE=$("$DEV_JWT" token '"store_id":"'"$STORE"'"')
SECOND_EMPLOYEE=$("$DEV_JWT" token '"store_id":"'"$SECOND_STORE"'"')
OTHER_STORE_EMPLOYEE=$("$DEV_JWT" token '"store_id":"another-store"')

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

expect_status() { # <description> <expected status> <curl args...>
  local what=$1 expected=$2 actual
  shift 2
  actual=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  if [ "$actual" = "$expected" ]; then
    echo "ok: $what ($actual)"
  else
    echo "FAILED: $what: expected $expected, got $actual" >&2
    exit 1
  fi
}

healthy() { curl -fs "$1/actuator/health" | grep -q '"status":"UP"'; }

# Open tasks of the promotion in one store, read with that store's employee token; "until" far
# ahead so tasks due later count too.
open_task_count() { # <store> <token>
  curl -fs -H "Authorization: Bearer $2" "$TASKS/stores/$1/tasks?until=2099-12-31" |
    grep -o '"promotionId":"'"$PROMOTION_ID"'"' | wc -l | tr -d ' '
}
has_open_tasks() { [ "$(open_task_count "$1" "$2")" = "$3" ]; } # <store> <token> <count>

promotion_for() { # <JSON array of store ids>
  printf '{
    "title": "Alle kipfilet", "label": "25%% korting", "kind": "PERCENT_OFF", "percent": 25,
    "validFrom": "%s", "validUntil": "%s", "storeIds": %s, "productIds": ["product-42"]
  }' "$VALID_FROM" "$VALID_UNTIL" "$1"
}
PROMOTION=$(promotion_for '["'"$STORE"'"]')

etag_of_promotion() {
  curl -fs -D - -o /dev/null -H "Authorization: Bearer $HEAD_OFFICE" "$PROMOTIONS/promotions/$PROMOTION_ID" |
    grep -i '^etag:' | tr -d '\r' | cut -d' ' -f2
}

wait_until "promotion-service healthy" 120 healthy "$PROMOTIONS"
wait_until "store-task-service healthy" 120 healthy "$TASKS"

expect_status "no token is refused" 401 "$PROMOTIONS/promotions"
expect_status "a read-only token can't create promotions" 403 \
  -X POST "$PROMOTIONS/promotions" -H "Authorization: Bearer $READ_ONLY" -H 'Content-Type: application/json' -d "$PROMOTION"

PROMOTION_ID=$(
  curl -fs "$PROMOTIONS/promotions" -H "Authorization: Bearer $HEAD_OFFICE" -H 'Content-Type: application/json' -d "$PROMOTION" |
    grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4
)
echo "created promotion $PROMOTION_ID for store $STORE"

wait_until "put-up and take-down tasks in store-task-service" 60 has_open_tasks "$STORE" "$EMPLOYEE" 2

# Edit: add a second store. Optimistic locking over HTTP: the ETag from the read goes in If-Match.
TWO_STORES=$(promotion_for '["'"$STORE"'", "'"$SECOND_STORE"'"]')
ETAG=$(etag_of_promotion)
expect_status "an edit without If-Match is refused" 428 \
  -X PUT "$PROMOTIONS/promotions/$PROMOTION_ID" -H "Authorization: Bearer $HEAD_OFFICE" -H 'Content-Type: application/json' -d "$TWO_STORES"
expect_status "an edit with the current ETag is applied" 200 \
  -X PUT "$PROMOTIONS/promotions/$PROMOTION_ID" -H "Authorization: Bearer $HEAD_OFFICE" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' -d "$TWO_STORES"
expect_status "an edit based on the old ETag is refused" 412 \
  -X PUT "$PROMOTIONS/promotions/$PROMOTION_ID" -H "Authorization: Bearer $HEAD_OFFICE" -H "If-Match: $ETAG" \
  -H 'Content-Type: application/json' -d "$PROMOTION"

wait_until "tasks for the added store" 60 has_open_tasks "$SECOND_STORE" "$SECOND_EMPLOYEE" 2

expect_status "another store's employee can't see these tasks" 403 \
  -H "Authorization: Bearer $OTHER_STORE_EMPLOYEE" "$TASKS/stores/$STORE/tasks"

curl -fs -X POST -H "Authorization: Bearer $HEAD_OFFICE" "$PROMOTIONS/promotions/$PROMOTION_ID/cancel" > /dev/null
echo "cancelled promotion $PROMOTION_ID"

wait_until "tasks cancelled in the first store" 60 has_open_tasks "$STORE" "$EMPLOYEE" 0
wait_until "tasks cancelled in the added store" 60 has_open_tasks "$SECOND_STORE" "$SECOND_EMPLOYEE" 0

echo "smoke test passed"
