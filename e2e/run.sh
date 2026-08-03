#!/usr/bin/env bash
# End-to-end test wiring aicoin (Go blockchain node) + aicoin-proxy
# (Java/Netty) together against a throwaway mock AI provider. See
# CONTRACT.md for the exact API/behavior this asserts against.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# jenv resolves the `java` on PATH by searching for a .java-version file
# upward from the current directory, falling back to a global default if
# none is found; pin CWD to the repo root (whose .java-version is 11.0.29)
# so the proxy binary launched below doesn't inherit whatever directory
# this script happened to be invoked from.
cd "$REPO_ROOT"
WORKDIR="$(mktemp -d /tmp/aicoin-e2e.XXXXXX)"
FAIL=0
PIDS=()
DYNAMODB_CONTAINER=""

log() { echo "[e2e] $*"; }
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAIL=1; }

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" >/dev/null 2>&1 || true; done
  wait >/dev/null 2>&1 || true
  # kill/wait above only reaches the process we forked directly; some start
  # scripts exec into a child (e.g. the generated Gradle "application" start
  # script forking the JVM) that can survive the parent's death — sweep any
  # listener still squatting on our ports as a backstop.
  for p in "$NODE_A_HTTP" "$NODE_B_HTTP" "$MOCK_PORT" "$PROXY_PORT"; do
    pids_on_port=$(lsof -t -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$pids_on_port" ] && kill -9 $pids_on_port 2>/dev/null || true
  done
  [ -n "$DYNAMODB_CONTAINER" ] && docker stop "$DYNAMODB_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for() {
  local url="$1" tries=0 body
  while true; do
    body=$(curl -s -m 1 "$url" 2>/dev/null || true)
    # This sandbox mediates all loopback HTTP through a local proxy that
    # returns a synthetic {"error":{"type":"proxy_error",...}} body (a
    # real, curl-success HTTP response) when it can't/won't reach the
    # real destination — so a plain curl-exit-code check isn't enough;
    # reject that specific synthetic body and require real content.
    if [ -n "$body" ] && [[ "$body" != *proxy_error* ]]; then
      return 0
    fi
    tries=$((tries + 1))
    if [ "$tries" -gt 30 ]; then
      log "timed out waiting for $url (last response: $body)"
      return 1
    fi
    sleep 0.3
  done
}

NODE_A_HTTP=19944
NODE_B_HTTP=19946
MOCK_PORT=18090
PROXY_PORT=18080
DYNAMODB_PORT=18000
DYNAMODB_TABLE=aicoin-e2e-chain

log "building Go binaries"
(cd "$REPO_ROOT/aicoin" && go build -o "$WORKDIR/aicoind" ./cmd/aicoind && go build -o "$WORKDIR/wallet" ./cmd/wallet) || { log "go build failed"; exit 1; }

PROXY_BIN="$REPO_ROOT/aicoin-proxy/build/install/aicoin-proxy/bin/aicoin-proxy"
if [ -x "$PROXY_BIN" ]; then
  log "reusing existing aicoin-proxy build at $PROXY_BIN (delete build/install to force a rebuild)"
else
  log "building aicoin-proxy"
  (cd "$REPO_ROOT/aicoin-proxy" && ./gradlew installDist -q --no-daemon) || { log "gradle build failed"; exit 1; }
fi

# Replication between node A and node B now goes through a shared DynamoDB
# table (polled by the follower), not P2P. There's no production local
# stand-in for DynamoDB the way redis:alpine stood in for a managed cache
# (see CONTRACT.md's "Docker / docker-compose" section), but AWS officially
# ships DynamoDB Local specifically for this kind of test/dev use, so this
# e2e test uses that -- the AWS SDK in cmd/aicoind picks up the endpoint
# override via the standard AWS_ENDPOINT_URL_DYNAMODB env var, no code
# changes needed.
log "starting DynamoDB Local on :$DYNAMODB_PORT"
DYNAMODB_CONTAINER=$(docker run -d --rm -p "$DYNAMODB_PORT:8000" amazon/dynamodb-local:latest -jar DynamoDBLocal.jar -inMemory -sharedDb) || { log "docker run dynamodb-local failed"; exit 1; }

export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=fake
export AWS_SECRET_ACCESS_KEY=fake
export AWS_ENDPOINT_URL_DYNAMODB="http://127.0.0.1:$DYNAMODB_PORT"

log "creating DynamoDB table $DYNAMODB_TABLE"
aws dynamodb create-table \
  --endpoint-url "$AWS_ENDPOINT_URL_DYNAMODB" --region "$AWS_REGION" \
  --table-name "$DYNAMODB_TABLE" \
  --attribute-definitions AttributeName=chain_id,AttributeType=S AttributeName=block_index,AttributeType=N \
  --key-schema AttributeName=chain_id,KeyType=HASH AttributeName=block_index,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST >/dev/null || { log "creating dynamodb table failed"; exit 1; }

log "starting mock AI provider on :$MOCK_PORT"
python3 "$REPO_ROOT/e2e/mock_provider.py" "$MOCK_PORT" > "$WORKDIR/mock.log" 2>&1 &
PIDS+=($!)

log "starting aicoin node A as primary on :$NODE_A_HTTP (dynamodb table $DYNAMODB_TABLE)"
"$WORKDIR/aicoind" -http=":$NODE_A_HTTP" -role=primary -keyfile="$WORKDIR/nodeA.key" -dynamodb-table="$DYNAMODB_TABLE" > "$WORKDIR/nodeA.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$NODE_A_HTTP/health" || { log "node A never came up"; exit 1; }
PRIMARY_PUBKEY=$(grep -oE 'pubkey=[0-9a-f]{64}' "$WORKDIR/nodeA.log" | head -1 | cut -d= -f2)
if [ -z "$PRIMARY_PUBKEY" ]; then log "could not read primary's pubkey from its startup log"; cat "$WORKDIR/nodeA.log"; exit 1; fi
log "primary pubkey: $PRIMARY_PUBKEY"

log "starting aicoin node B as a follower of A on :$NODE_B_HTTP (same dynamodb table, polling every 1s)"
"$WORKDIR/aicoind" -http=":$NODE_B_HTTP" -role=follower -trusted-pubkey="$PRIMARY_PUBKEY" -dynamodb-table="$DYNAMODB_TABLE" -follower-poll-interval=1s > "$WORKDIR/nodeB.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$MOCK_PORT/" || true
wait_for "http://127.0.0.1:$NODE_B_HTTP/health" || { log "node B never came up"; exit 1; }

echo "3" > "$WORKDIR/free-coins-counter.txt"

log "starting aicoin-proxy on :$PROXY_PORT"
AICOIN_PROXY_PORT="$PROXY_PORT" \
AICOIN_PROXY_OPENAI_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_OPENAI_APIKEY="test-secret-key" \
AICOIN_PROXY_AICOIN_EVENTS_URL="http://127.0.0.1:$NODE_A_HTTP/events" \
AICOIN_PROXY_AICOIN_PRICE_URL="http://127.0.0.1:$NODE_A_HTTP/price" \
AICOIN_PROXY_AICOIN_BALANCE_URL_BASE="http://127.0.0.1:$NODE_A_HTTP" \
AICOIN_PROXY_FREE_COINS_COUNTER_FILE="$WORKDIR/free-coins-counter.txt" \
  "$PROXY_BIN" > "$WORKDIR/proxy.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$PROXY_PORT/health" || { log "proxy never came up"; cat "$WORKDIR/proxy.log"; exit 1; }

log "--- test 1: missing X-Api-Key on a proxied call ---"
code=$(curl -s -o "$WORKDIR/t1.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-AI: openai" -d '{}')
if [ "$code" = "401" ] && grep -q "X-Api-Key" "$WORKDIR/t1.json"; then pass "401 missing X-Api-Key"; else fail "expected 401/X-Api-Key error, got $code: $(cat "$WORKDIR/t1.json")"; fi

log "--- test 2: missing X-AI header ---"
code=$(curl -s -o "$WORKDIR/t2.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: alice" -d '{}')
if [ "$code" = "400" ]; then pass "400 missing X-AI"; else fail "expected 400, got $code: $(cat "$WORKDIR/t2.json")"; fi

log "--- test 3: full happy-path proxied call (routing + key injection + passthrough) ---"
log "granting carol a free coin first — the proxy now gates on a positive balance (kept separate from alice, who tests 7/8 exercise below)"
curl -s -X POST "http://127.0.0.1:$NODE_A_HTTP/free-coins/claim" -d '{"user_id":"carol"}' > /dev/null
code=$(curl -s -o "$WORKDIR/t3.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: carol" -H "X-AI: openai" -d '{"model":"gpt-4"}')
if [ "$code" = "200" ]; then
  auth=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_authorization'))")
  xai=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_x_ai'))")
  tokens=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('usage',{}).get('total_tokens'))")
  [ "$auth" = "Bearer test-secret-key" ] && pass "proxy injected its own key ($auth)" || fail "expected injected key, mock saw: $auth"
  [ "$xai" = "None" ] && pass "X-AI stripped before forwarding" || fail "X-AI leaked upstream: $xai"
  [ "$tokens" = "100" ] && pass "passthrough body intact (usage.total_tokens=100)" || fail "unexpected body: tokens=$tokens"
else
  fail "expected 200, got $code: $(cat "$WORKDIR/t3.json")"
fi

log "--- test 4: proxy /health lists all 7 providers ---"
health=$(curl -s "http://127.0.0.1:$PROXY_PORT/health")
count=$(echo "$health" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['providers']))")
[ "$count" = "7" ] && pass "7 providers listed" || fail "expected 7 providers, got: $health"

log "--- test 5: proxy /price forwards to aicoin node and reflects the paid call ---"
sleep 1
price=$(curl -s "http://127.0.0.1:$PROXY_PORT/price")
price_usd=$(echo "$price" | python3 -c "import json,sys;print(json.load(sys.stdin)['price_usd'])")
python3 -c "import sys; sys.exit(0 if float('$price_usd') > 0 else 1)" && pass "price_usd > 0 ($price_usd) after paid call" || fail "expected positive price, got: $price"

log "--- test 6: proxy /free-coins/available reads the counter file ---"
avail=$(curl -s "http://127.0.0.1:$PROXY_PORT/free-coins/available")
echo "$avail" | grep -q '"available":3' && pass "available:3 from counter file" || fail "unexpected: $avail"

log "--- test 7: wallet CLI claims a free coin end-to-end (proxy allowance + node claim) ---"
out=$("$WORKDIR/wallet" -user=alice -node="http://127.0.0.1:$NODE_A_HTTP" -proxy="http://127.0.0.1:$PROXY_PORT" 2>&1)
bal=$(curl -s "http://127.0.0.1:$NODE_A_HTTP/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
[ "$bal" = "1" ] && pass "alice balance is 1 after claim (event calls did not mint): $out" || fail "expected balance 1, got $bal. wallet output: $out"

log "--- test 8: wallet CLI second claim within the hour is rejected ---"
out2=$("$WORKDIR/wallet" -user=alice -node="http://127.0.0.1:$NODE_A_HTTP" -proxy="http://127.0.0.1:$PROXY_PORT" 2>&1)
bal2=$(curl -s "http://127.0.0.1:$NODE_A_HTTP/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
[ "$bal2" = "1" ] && pass "alice balance still 1 (no double-claim): $out2" || fail "expected balance still 1, got $bal2"

log "--- test 9: peer transfer (buy/sell) ---"
code=$(curl -s -o "$WORKDIR/t9.json" -w "%{http_code}" -X POST "http://127.0.0.1:$NODE_A_HTTP/transfer" -d '{"from_user_id":"alice","to_user_id":"bob","amount":0.4}')
bal_alice=$(curl -s "http://127.0.0.1:$NODE_A_HTTP/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
bal_bob=$(curl -s "http://127.0.0.1:$NODE_A_HTTP/balance/bob" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
if [ "$code" = "200" ] && [ "$bal_alice" = "0.6" ] && [ "$bal_bob" = "0.4" ]; then pass "transfer moved balance (alice=0.6, bob=0.4)"; else fail "transfer mismatch: code=$code alice=$bal_alice bob=$bal_bob"; fi

log "--- test 10: overdraft transfer is rejected ---"
code=$(curl -s -o "$WORKDIR/t10.json" -w "%{http_code}" -X POST "http://127.0.0.1:$NODE_A_HTTP/transfer" -d '{"from_user_id":"bob","to_user_id":"alice","amount":999}')
[ "$code" = "400" ] && pass "400 insufficient balance" || fail "expected 400, got $code: $(cat "$WORKDIR/t10.json")"

log "--- test 11: DynamoDB replication — follower B polls and mirrors primary A's signed chain ---"
sleep 2
height_a=$(curl -s "http://127.0.0.1:$NODE_A_HTTP/chain" | python3 -c "import json,sys;print(len(json.load(sys.stdin)))")
height_b=$(curl -s "http://127.0.0.1:$NODE_B_HTTP/chain" | python3 -c "import json,sys;print(len(json.load(sys.stdin)))")
if [ "$height_a" = "$height_b" ] && [ "$height_a" -gt 1 ]; then pass "follower B replicated primary A's chain via DynamoDB polling (height $height_a)"; else fail "chain mismatch: A=$height_a B=$height_b"; fi

log "--- test 12: follower rejects writes (no signing key) ---"
code=$(curl -s -o "$WORKDIR/t12.json" -w "%{http_code}" -X POST "http://127.0.0.1:$NODE_B_HTTP/events" -d '{"user_id":"eve","provider":"openai","cost_usd":0.01}')
[ "$code" = "403" ] && pass "403 read-only replica" || fail "expected 403, got $code: $(cat "$WORKDIR/t12.json")"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "=== ALL E2E CHECKS PASSED ==="
  exit 0
else
  echo "=== E2E FAILURES ABOVE — logs in $WORKDIR ==="
  echo "--- proxy.log ---"; tail -60 "$WORKDIR/proxy.log"
  echo "--- nodeA.log ---"; tail -30 "$WORKDIR/nodeA.log"
  echo "--- nodeB.log ---"; tail -30 "$WORKDIR/nodeB.log"
  exit 1
fi
