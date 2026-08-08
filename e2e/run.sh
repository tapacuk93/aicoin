#!/usr/bin/env bash
# End-to-end test for aicoin-proxy (Java/Netty): a single process that both
# reverse-proxies AI calls and owns the coin ledger, backed by Redis. Wallet
# identities are real Ed25519 keypairs (openssl-generated here, mirroring
# what the browser wallet does with WebCrypto): wallet-management actions
# (claim/transfer/revoke-tokens) are live-signed per request; AI-proxy calls
# use a signed, expiring API token instead, atomically debiting exactly 1.0
# aicoin per successful call (refunded if the upstream call then fails) —
# "1 aicoin is worth 1 paid AI call," enforced, not just a tagline. Free-coin
# claims draw from a shared, atomically-decremented pool across every
# wallet, separate from both the price event log and the debit/refund
# balance mutations. All 7 providers point at the local mock so every paid
# call — including the one-per-provider sweep — is exercised deterministically,
# with no dependency on real provider network/credentials. See CONTRACT.md
# for the exact API/behavior this asserts against.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# jenv resolves the `java` on PATH by searching for a .java-version file
# upward from the current directory, falling back to a global default if
# none is found; pin CWD to the repo root (whose .java-version currently
# pins GraalVM 25 — see CONTRACT.md's "Docker / docker-compose" section for
# why this isn't GraalVM JDK 26 yet) so the proxy binary launched below
# doesn't inherit whatever directory this script happened to be invoked from.
cd "$REPO_ROOT"
WORKDIR="$(mktemp -d /tmp/aicoin-e2e.XXXXXX)"
FAIL=0
PIDS=()
REDIS_CONTAINER=""

log() { echo "[e2e] $*"; }
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAIL=1; }

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" >/dev/null 2>&1 || true; done
  wait >/dev/null 2>&1 || true
  # kill/wait above only reaches the process we forked directly; the
  # generated Gradle "application" start script forks the JVM as a child
  # that can survive the parent's death — sweep any listener still
  # squatting on our ports as a backstop.
  for p in "$MOCK_PORT" "$PROXY_PORT"; do
    pids_on_port=$(lsof -t -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$pids_on_port" ] && kill -9 $pids_on_port 2>/dev/null || true
  done
  [ -n "$REDIS_CONTAINER" ] && docker stop "$REDIS_CONTAINER" >/dev/null 2>&1 || true
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

# ---- Ed25519 wallet helpers (openssl 1.1.1+/3.x; mirrors what the browser
# wallet does client-side with WebCrypto) ----

gen_wallet() {
  # $1 = path to write the new private key (PEM) to
  openssl genpkey -algorithm ed25519 -out "$1" 2>/dev/null
}

wallet_address() {
  # $1 = private key PEM path -> prints the 64-hex-char address (raw 32-byte
  # public key). The DER SubjectPublicKeyInfo is a fixed 12-byte prefix +
  # the raw 32-byte key (44 bytes = 88 hex chars); keep the last 64.
  openssl pkey -in "$1" -pubout -outform DER 2>/dev/null | xxd -p -c 256 | tr -d '\n' | tail -c 64
}

str_to_b64url() {
  printf '%s' "$1" | python3 -c "import sys,base64; sys.stdout.write(base64.urlsafe_b64encode(sys.stdin.buffer.read()).decode().rstrip('='))"
}

hex_to_b64url() {
  xxd -r -p <<< "$1" | python3 -c "import sys,base64; sys.stdout.write(base64.urlsafe_b64encode(sys.stdin.buffer.read()).decode().rstrip('='))"
}

epoch_millis() { python3 -c "import time; print(int(time.time()*1000))"; }
epoch_seconds() { python3 -c "import time; print(int(time.time()))"; }

# live_signed_request <keyfile> <address> <method> <path> <body-or-empty> <outfile>
# Prints the HTTP status code; writes the response body to <outfile>.
live_signed_request() {
  local keyfile="$1" address="$2" method="$3" path="$4" body="$5" outfile="$6"
  local ts bodyhash msgfile sig url
  ts=$(epoch_millis)
  bodyhash=$(printf '%s' "$body" | openssl dgst -sha256 -binary | xxd -p -c 256 | tr -d '\n')
  msgfile=$(mktemp "$WORKDIR/sigmsg.XXXXXX")
  printf '%s\n%s\n%s\n%s\n%s' "$address" "$ts" "$method" "$path" "$bodyhash" > "$msgfile"
  sig=$(openssl pkeyutl -sign -rawin -inkey "$keyfile" -in "$msgfile" | xxd -p -c 256 | tr -d '\n')
  rm -f "$msgfile"
  url="http://127.0.0.1:$PROXY_PORT$path"
  if [ -n "$body" ]; then
    curl -s -o "$outfile" -w "%{http_code}" -X "$method" "$url" \
      -H "X-Api-Key: $address" -H "X-Api-Signature: $sig" -H "X-Api-Timestamp: $ts" \
      -H "Content-Type: application/json" -d "$body"
  else
    curl -s -o "$outfile" -w "%{http_code}" -X "$method" "$url" \
      -H "X-Api-Key: $address" -H "X-Api-Signature: $sig" -H "X-Api-Timestamp: $ts"
  fi
}

# build_token <keyfile> <address> <iatSeconds> <expSeconds>
build_token() {
  local keyfile="$1" address="$2" iat="$3" exp="$4"
  local payload payload_b64 msgfile sig_hex sig_b64
  payload="{\"addr\":\"$address\",\"iat\":$iat,\"exp\":$exp}"
  payload_b64=$(str_to_b64url "$payload")
  msgfile=$(mktemp "$WORKDIR/tokenmsg.XXXXXX")
  printf '%s' "$payload_b64" > "$msgfile"
  sig_hex=$(openssl pkeyutl -sign -rawin -inkey "$keyfile" -in "$msgfile" | xxd -p -c 256 | tr -d '\n')
  rm -f "$msgfile"
  sig_b64=$(hex_to_b64url "$sig_hex")
  echo "${payload_b64}.${sig_b64}"
}

balance_of() {
  curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/balance/$1" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])"
}

MOCK_PORT=18090
PROXY_PORT=18080
REDIS_PORT=16379
# Each successful claim mints CLAIM_AMOUNT (10) aicoin, not 1 — mirrors the
# server's hardcoded FREE_CLAIM_AMOUNT_AICOIN in ProxyFrontendHandler. 3
# legitimate claims before the exhaustion test (carol, alice, dave) + 7
# funder claims (one per provider tested below) = 10 claims x 10 aicoin =
# 100 exactly; the pool must hit zero right when the exhaustion test's
# wallet (erin) tries.
CLAIM_AMOUNT=10
FREE_COINS_POOL_SIZE=100

PROVIDERS=(openai anthropic google mistral cohere elevenlabs stability)
AUTH_HEADERS=(Authorization x-api-key "" Authorization Authorization xi-api-key Authorization)
AUTH_PREFIXES=("Bearer " "" "" "Bearer " "Bearer " "" "Bearer ")
TEST_KEYS=(openai-test-key anthropic-test-key google-test-key mistral-test-key cohere-test-key elevenlabs-test-key stability-test-key)

PROXY_BIN="$REPO_ROOT/aicoin-proxy/build/install/aicoin-proxy/bin/aicoin-proxy"
if [ -x "$PROXY_BIN" ]; then
  log "reusing existing aicoin-proxy build at $PROXY_BIN (delete build/install to force a rebuild)"
else
  log "building aicoin-proxy"
  (cd "$REPO_ROOT/aicoin-proxy" && ./gradlew installDist -q --no-daemon) || { log "gradle build failed"; exit 1; }
fi

# A crashed/killed previous run can leave a stale listener on these ports —
# if so, this run's own mock/proxy processes fail to bind and every
# request silently hits the old stale process instead, which is far more
# confusing to debug than refusing to start.
for p in "$MOCK_PORT" "$PROXY_PORT" "$REDIS_PORT"; do
  stale_pids=$(lsof -t -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$stale_pids" ]; then
    log "killing stale listener(s) on port $p from a previous run: $stale_pids"
    kill -9 $stale_pids 2>/dev/null || true
    sleep 0.5
  fi
done

log "starting Redis on :$REDIS_PORT"
REDIS_CONTAINER=$(docker run -d --rm -p "$REDIS_PORT:6379" redis:7-alpine) || { log "docker run redis failed"; exit 1; }

log "starting mock AI provider on :$MOCK_PORT (stands in for all 7 real providers)"
python3 "$REPO_ROOT/e2e/mock_provider.py" "$MOCK_PORT" > "$WORKDIR/mock.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$MOCK_PORT/" || true

log "starting aicoin-proxy on :$PROXY_PORT (free-coins pool size $FREE_COINS_POOL_SIZE, all providers -> mock)"
ADMIN_TOKEN="e2e-admin-secret"
AICOIN_PROXY_PORT="$PROXY_PORT" \
AICOIN_PROXY_REDIS_HOST="127.0.0.1" \
AICOIN_PROXY_REDIS_PORT="$REDIS_PORT" \
AICOIN_PROXY_FREE_COINS_POOL_SIZE="$FREE_COINS_POOL_SIZE" \
AICOIN_PROXY_ADMIN_TOKEN="$ADMIN_TOKEN" \
AICOIN_PROXY_OPENAI_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_OPENAI_APIKEY="${TEST_KEYS[0]}" \
AICOIN_PROXY_ANTHROPIC_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_ANTHROPIC_APIKEY="${TEST_KEYS[1]}" \
AICOIN_PROXY_GOOGLE_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_GOOGLE_APIKEY="${TEST_KEYS[2]}" \
AICOIN_PROXY_MISTRAL_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_MISTRAL_APIKEY="${TEST_KEYS[3]}" \
AICOIN_PROXY_COHERE_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_COHERE_APIKEY="${TEST_KEYS[4]}" \
AICOIN_PROXY_ELEVENLABS_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_ELEVENLABS_APIKEY="${TEST_KEYS[5]}" \
AICOIN_PROXY_STABILITY_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_STABILITY_APIKEY="${TEST_KEYS[6]}" \
  "$PROXY_BIN" > "$WORKDIR/proxy.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$PROXY_PORT/health" || { log "proxy never came up"; cat "$WORKDIR/proxy.log"; exit 1; }

log "generating test wallets (Ed25519, via openssl — mirrors the browser wallet's WebCrypto flow)"
KEY_ALICE="$WORKDIR/alice.pem"; gen_wallet "$KEY_ALICE"; ADDR_ALICE=$(wallet_address "$KEY_ALICE")
KEY_BOB="$WORKDIR/bob.pem"; gen_wallet "$KEY_BOB"; ADDR_BOB=$(wallet_address "$KEY_BOB")
KEY_CAROL="$WORKDIR/carol.pem"; gen_wallet "$KEY_CAROL"; ADDR_CAROL=$(wallet_address "$KEY_CAROL")
KEY_DAVE="$WORKDIR/dave.pem"; gen_wallet "$KEY_DAVE"; ADDR_DAVE=$(wallet_address "$KEY_DAVE")
KEY_ERIN="$WORKDIR/erin.pem"; gen_wallet "$KEY_ERIN"; ADDR_ERIN=$(wallet_address "$KEY_ERIN")
KEY_FRANK="$WORKDIR/frank.pem"; gen_wallet "$KEY_FRANK"; ADDR_FRANK=$(wallet_address "$KEY_FRANK")
log "alice=$ADDR_ALICE bob=$ADDR_BOB carol=$ADDR_CAROL dave=$ADDR_DAVE erin=$ADDR_ERIN frank=$ADDR_FRANK"

log "--- test 1: missing X-Api-Key on a proxied call ---"
code=$(curl -s -o "$WORKDIR/t1.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-AI: openai" -d '{}')
if [ "$code" = "401" ] && grep -q "X-Api-Key" "$WORKDIR/t1.json"; then pass "401 missing X-Api-Key"; else fail "expected 401/X-Api-Key error, got $code: $(cat "$WORKDIR/t1.json")"; fi

log "--- test 2: missing X-AI header (a syntactically valid token is present) ---"
NOW=$(epoch_seconds)
CAROL_TOKEN=$(build_token "$KEY_CAROL" "$ADDR_CAROL" "$NOW" "$((NOW + 86400))")
code=$(curl -s -o "$WORKDIR/t2.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: $CAROL_TOKEN" -d '{}')
if [ "$code" = "400" ]; then pass "400 missing X-AI"; else fail "expected 400, got $code: $(cat "$WORKDIR/t2.json")"; fi

log "--- test 3: full happy-path proxied call via API token (routing + key injection + passthrough + 1-aicoin debit) ---"
log "granting carol a free coin first (live-signed claim, pool $FREE_COINS_POOL_SIZE -> $((FREE_COINS_POOL_SIZE - CLAIM_AMOUNT))) — the proxy debits 1 aicoin per call, not just a balance>0 gate"
live_signed_request "$KEY_CAROL" "$ADDR_CAROL" "POST" "/wallet/api/claim" "" "$WORKDIR/carol-claim.json" > /dev/null
code=$(curl -s -o "$WORKDIR/t3.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: $CAROL_TOKEN" -H "X-AI: openai" -d '{"model":"gpt-4"}')
if [ "$code" = "200" ]; then
  auth=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_authorization'))")
  xai=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_x_ai'))")
  tokens=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('usage',{}).get('total_tokens'))")
  [ "$auth" = "Bearer ${TEST_KEYS[0]}" ] && pass "proxy injected its own key ($auth)" || fail "expected injected key, mock saw: $auth"
  [ "$xai" = "None" ] && pass "X-AI stripped before forwarding" || fail "X-AI leaked upstream: $xai"
  [ "$tokens" = "100" ] && pass "passthrough body intact (usage.total_tokens=100)" || fail "unexpected body: tokens=$tokens"
else
  fail "expected 200, got $code: $(cat "$WORKDIR/t3.json")"
fi
bal_carol_after_call=$(balance_of "$ADDR_CAROL")
[ "$bal_carol_after_call" = "$((CLAIM_AMOUNT - 1))" ] && pass "carol's paid call debited exactly 1 aicoin (balance $CLAIM_AMOUNT -> $((CLAIM_AMOUNT - 1)))" || fail "expected carol balance $((CLAIM_AMOUNT - 1)) after the call, got $bal_carol_after_call"

log "--- test 4: proxy /health lists all 7 providers ---"
health=$(curl -s "http://127.0.0.1:$PROXY_PORT/health")
count=$(echo "$health" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['providers']))")
[ "$count" = "7" ] && pass "7 providers listed" || fail "expected 7 providers, got: $health"

log "--- test 5: proxy /price reflects the paid call (and only the paid call — not the free claim) ---"
sleep 1
price=$(curl -s "http://127.0.0.1:$PROXY_PORT/price")
price_usd=$(echo "$price" | python3 -c "import json,sys;print(json.load(sys.stdin)['price_usd'])")
weighted_total=$(echo "$price" | python3 -c "import json,sys;print(json.load(sys.stdin)['weighted_total'])")
python3 -c "import sys; sys.exit(0 if float('$price_usd') > 0 else 1)" && pass "price_usd > 0 ($price_usd) after paid call" || fail "expected positive price, got: $price"
# Exactly one event has been recorded so far (carol's call) — if the free
# claim had leaked into the price event log, weighted_total would reflect
# more than one contribution; at this point in the script it must be ~1.0
# (a single event's weight, still near its full value seconds after being
# recorded).
python3 -c "import sys; sys.exit(0 if 0.9 < float('$weighted_total') <= 1.01 else 1)" && pass "weighted_total reflects exactly the one paid call (free claim did not feed the price formula): $weighted_total" || fail "expected weighted_total ~1.0 (only carol's call), got $weighted_total"

log "--- test 6: proxy /free-coins/available reflects the real shared pool (not a static file) ---"
avail=$(curl -s "http://127.0.0.1:$PROXY_PORT/free-coins/available")
echo "$avail" | grep -q "\"available\":$((FREE_COINS_POOL_SIZE - CLAIM_AMOUNT))" && pass "available:$((FREE_COINS_POOL_SIZE - CLAIM_AMOUNT)) after carol's claim" || fail "unexpected: $avail"

log "--- test 7: live-signed wallet claim mints exactly $CLAIM_AMOUNT free coins (and consumes a pool slot) ---"
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/claim" "" "$WORKDIR/t7.json")
bal=$(balance_of "$ADDR_ALICE")
[ "$code" = "200" ] && [ "$bal" = "$CLAIM_AMOUNT" ] && pass "alice balance is $CLAIM_AMOUNT after claim (priced calls did not mint)" || fail "expected 200/balance $CLAIM_AMOUNT, got code=$code balance=$bal: $(cat "$WORKDIR/t7.json")"

log "--- test 8: second claim within the hour is rejected (cooldown, not pool exhaustion) ---"
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/claim" "" "$WORKDIR/t8.json")
bal2=$(balance_of "$ADDR_ALICE")
reason8=$(python3 -c "import json;print(json.load(open('$WORKDIR/t8.json')).get('reason'))" 2>/dev/null || echo "")
[ "$code" = "429" ] && [ "$bal2" = "$CLAIM_AMOUNT" ] && [ "$reason8" = "cooldown" ] && pass "429 not eligible yet (reason=cooldown), balance still $CLAIM_AMOUNT (no double-claim)" || fail "expected 429/cooldown/balance $CLAIM_AMOUNT, got code=$code reason=$reason8 balance=$bal2: $(cat "$WORKDIR/t8.json")"

log "--- test 9: live-signed peer transfer (buy/sell) ---"
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/transfer" "{\"to_user_id\":\"$ADDR_BOB\",\"amount\":4}" "$WORKDIR/t9.json")
bal_alice=$(balance_of "$ADDR_ALICE")
bal_bob=$(balance_of "$ADDR_BOB")
if [ "$code" = "200" ] && [ "$bal_alice" = "6" ] && [ "$bal_bob" = "4" ]; then pass "transfer moved balance (alice=6, bob=4)"; else fail "transfer mismatch: code=$code alice=$bal_alice bob=$bal_bob"; fi

log "--- test 10: overdraft transfer is rejected ---"
code=$(live_signed_request "$KEY_BOB" "$ADDR_BOB" "POST" "/wallet/api/transfer" "{\"to_user_id\":\"$ADDR_ALICE\",\"amount\":999}" "$WORKDIR/t10.json")
[ "$code" = "400" ] && pass "400 insufficient balance" || fail "expected 400, got $code: $(cat "$WORKDIR/t10.json")"

log "--- funding: dave claims (for test 16's refund check) and 7 fresh wallets claim+transfer 1 aicoin each to frank (for the one-call-per-provider test) ---"
code=$(live_signed_request "$KEY_DAVE" "$ADDR_DAVE" "POST" "/wallet/api/claim" "" "$WORKDIR/dave-claim.json")
[ "$code" = "200" ] || fail "expected dave's claim to succeed, got $code: $(cat "$WORKDIR/dave-claim.json")"
for i in "${!PROVIDERS[@]}"; do
  fkey="$WORKDIR/funder$i.pem"; gen_wallet "$fkey"; faddr=$(wallet_address "$fkey")
  code=$(live_signed_request "$fkey" "$faddr" "POST" "/wallet/api/claim" "" "$WORKDIR/funder$i-claim.json")
  [ "$code" = "200" ] || fail "expected funder$i's claim to succeed, got $code"
  code=$(live_signed_request "$fkey" "$faddr" "POST" "/wallet/api/transfer" "{\"to_user_id\":\"$ADDR_FRANK\",\"amount\":1}" "$WORKDIR/funder$i-transfer.json")
  [ "$code" = "200" ] || fail "expected funder$i's transfer to frank to succeed, got $code"
done
bal_frank=$(balance_of "$ADDR_FRANK")
[ "$bal_frank" = "${#PROVIDERS[@]}" ] || fail "expected frank funded to ${#PROVIDERS[@]}, got $bal_frank"

log "--- test 11: the shared free-coins pool is now exhausted for a brand-new wallet ---"
code=$(live_signed_request "$KEY_ERIN" "$ADDR_ERIN" "POST" "/wallet/api/claim" "" "$WORKDIR/t11.json")
reason11=$(python3 -c "import json;print(json.load(open('$WORKDIR/t11.json')).get('reason'))" 2>/dev/null || echo "")
avail_after=$(curl -s "http://127.0.0.1:$PROXY_PORT/free-coins/available")
[ "$code" = "429" ] && [ "$reason11" = "pool_exhausted" ] && echo "$avail_after" | grep -q '"available":0' \
  && pass "429 pool_exhausted for erin (a wallet with no cooldown history of her own), available:0" \
  || fail "expected 429/pool_exhausted/available:0, got code=$code reason=$reason11 avail=$avail_after: $(cat "$WORKDIR/t11.json")"

log "--- test 12: one paid call succeeds for every configured provider, with the correct per-provider key injected ---"
FRANK_TOKEN=$(build_token "$KEY_FRANK" "$ADDR_FRANK" "$(epoch_seconds)" "$(($(epoch_seconds) + 3600))")
for i in "${!PROVIDERS[@]}"; do
  provider="${PROVIDERS[$i]}"
  header_name="${AUTH_HEADERS[$i]}"
  expected_value="${AUTH_PREFIXES[$i]}${TEST_KEYS[$i]}"
  outfile="$WORKDIR/t12-$provider.json"
  bal_before=$(balance_of "$ADDR_FRANK")
  code=$(curl -s -o "$outfile" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" \
    -H "X-AI: $provider" -H "X-Api-Key: $FRANK_TOKEN" -H "Content-Type: application/json" -d '{"model":"test"}')
  if [ "$code" != "200" ]; then
    fail "$provider: expected 200, got $code: $(cat "$outfile")"
    continue
  fi
  if [ "$provider" = "google" ]; then
    got_value=$(python3 -c "import json;print(json.load(open('$outfile')).get('received_query',{}).get('key'))")
  else
    got_value=$(python3 -c "
import json
headers = json.load(open('$outfile')).get('received_headers', {})
print(headers.get('$header_name'.lower()))
")
  fi
  bal_after=$(balance_of "$ADDR_FRANK")
  expected_bal=$(python3 -c "print($bal_before - 1)")
  if [ "$got_value" = "$expected_value" ] && [ "$bal_after" = "$expected_bal" ]; then
    pass "$provider: correct key injected ($got_value) and 1 aicoin debited (frank $bal_before -> $bal_after)"
  else
    fail "$provider: expected key '$expected_value' and balance $expected_bal, got key '$got_value' balance $bal_after"
  fi
done
bal_frank_final=$(balance_of "$ADDR_FRANK")
[ "$bal_frank_final" = "0" ] && pass "frank spent exactly ${#PROVIDERS[@]} aicoin for ${#PROVIDERS[@]} paid calls (balance now 0)" || fail "expected frank balance 0 after all provider calls, got $bal_frank_final"

log "--- test 13: an expired API token is rejected ---"
EXPIRED_TOKEN=$(build_token "$KEY_CAROL" "$ADDR_CAROL" "$((NOW - 200000))" "$((NOW - 100000))")
code=$(curl -s -o "$WORKDIR/t13.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: $EXPIRED_TOKEN" -H "X-AI: openai" -d '{}')
[ "$code" = "401" ] && grep -q "expired" "$WORKDIR/t13.json" && pass "401 token expired" || fail "expected 401/expired, got $code: $(cat "$WORKDIR/t13.json")"

log "--- test 14: revoking tokens invalidates a previously-valid token ---"
code=$(curl -s -o "$WORKDIR/t14-precheck.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: $CAROL_TOKEN" -H "X-AI: openai" -d '{}')
[ "$code" = "200" ] || fail "carol's token should still work before revocation (she still has balance left over from her test-3 claim), got $code: $(cat "$WORKDIR/t14-precheck.json")"
code=$(live_signed_request "$KEY_CAROL" "$ADDR_CAROL" "POST" "/wallet/api/revoke-tokens" "" "$WORKDIR/t14-revoke.json")
[ "$code" = "200" ] || fail "expected 200 from revoke-tokens, got $code: $(cat "$WORKDIR/t14-revoke.json")"
code=$(curl -s -o "$WORKDIR/t14.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: $CAROL_TOKEN" -H "X-AI: openai" -d '{}')
[ "$code" = "401" ] && grep -q "revoked" "$WORKDIR/t14.json" && pass "401 token revoked after revoke-tokens" || fail "expected 401/revoked, got $code: $(cat "$WORKDIR/t14.json")"

log "--- test 15: a tampered live-signature (signed body != sent body) is rejected ---"
TS=$(epoch_millis)
BODYHASH=$(printf '%s' '{"to_user_id":"'"$ADDR_BOB"'","amount":0.1}' | openssl dgst -sha256 -binary | xxd -p -c 256 | tr -d '\n')
MSGFILE=$(mktemp "$WORKDIR/tamper-msg.XXXXXX")
printf '%s\n%s\n%s\n%s\n%s' "$ADDR_ALICE" "$TS" "POST" "/wallet/api/transfer" "$BODYHASH" > "$MSGFILE"
SIG=$(openssl pkeyutl -sign -rawin -inkey "$KEY_ALICE" -in "$MSGFILE" | xxd -p -c 256 | tr -d '\n')
rm -f "$MSGFILE"
code=$(curl -s -o "$WORKDIR/t15.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/transfer" \
  -H "X-Api-Key: $ADDR_ALICE" -H "X-Api-Signature: $SIG" -H "X-Api-Timestamp: $TS" -H "Content-Type: application/json" \
  -d '{"to_user_id":"'"$ADDR_BOB"'","amount":999}')
[ "$code" = "401" ] && grep -q "signature" "$WORKDIR/t15.json" && pass "401 signature verification failed (body tampered after signing)" || fail "expected 401/signature error, got $code: $(cat "$WORKDIR/t15.json")"

log "--- test 16: a failed upstream call refunds the debit (paid calls vs failed calls are properly distinguished) ---"
bal_before_fail=$(balance_of "$ADDR_DAVE")
FAIL_TOKEN=$(build_token "$KEY_DAVE" "$ADDR_DAVE" "$(epoch_seconds)" "$(($(epoch_seconds) + 3600))")
code=$(curl -s -o "$WORKDIR/t16.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" \
  -H "X-Api-Key: $FAIL_TOKEN" -H "X-AI: openai" -H "Content-Type: application/json" -d '{"simulate_failure":true}')
bal_after_fail=$(balance_of "$ADDR_DAVE")
if [ "$code" = "500" ] && [ "$bal_after_fail" = "$bal_before_fail" ]; then
  pass "failed upstream call (500, relayed verbatim) left dave's balance unchanged ($bal_after_fail) — debit was refunded, not kept as if it were a paid call"
else
  fail "expected 500 with balance refunded to $bal_before_fail, got code=$code balance=$bal_after_fail"
fi

log "--- test 17: admin endpoints reject a missing or wrong X-Admin-Token ---"
code=$(curl -s -o "$WORKDIR/t17a.json" -w "%{http_code}" "http://127.0.0.1:$PROXY_PORT/admin/wallets")
[ "$code" = "401" ] && pass "401 with no X-Admin-Token" || fail "expected 401, got $code: $(cat "$WORKDIR/t17a.json")"
code=$(curl -s -o "$WORKDIR/t17b.json" -w "%{http_code}" -H "X-Admin-Token: wrong-token" "http://127.0.0.1:$PROXY_PORT/admin/wallets")
[ "$code" = "401" ] && pass "401 with a wrong X-Admin-Token" || fail "expected 401, got $code: $(cat "$WORKDIR/t17b.json")"

log "--- test 18: admin wallet list includes known wallets with the right balance and transaction counts ---"
code=$(curl -s -o "$WORKDIR/t18.json" -w "%{http_code}" -H "X-Admin-Token: $ADMIN_TOKEN" "http://127.0.0.1:$PROXY_PORT/admin/wallets")
dave_balance=$(python3 -c "
import json
wallets = json.load(open('$WORKDIR/t18.json'))['wallets']
byaddr = {w['address']: w for w in wallets}
print(byaddr.get('$ADDR_DAVE', {}).get('balance'))
")
dave_tx_count=$(python3 -c "
import json
wallets = json.load(open('$WORKDIR/t18.json'))['wallets']
byaddr = {w['address']: w for w in wallets}
print(byaddr.get('$ADDR_DAVE', {}).get('transaction_count'))
")
if [ "$code" = "200" ] && [ "$dave_balance" = "$bal_after_fail" ] && [ "$dave_tx_count" = "3" ]; then
  pass "admin wallet list shows dave with balance $dave_balance and 3 tx entries (claim, debit, refund)"
else
  fail "expected dave balance=$bal_after_fail tx_count=3, got code=$code balance=$dave_balance tx_count=$dave_tx_count: $(cat "$WORKDIR/t18.json")"
fi

log "--- test 19: admin transaction log for dave shows claim/debit/refund, most-recent first ---"
code=$(curl -s -o "$WORKDIR/t19.json" -w "%{http_code}" -H "X-Admin-Token: $ADMIN_TOKEN" "http://127.0.0.1:$PROXY_PORT/admin/wallets/$ADDR_DAVE/transactions")
types=$(python3 -c "
import json
print(','.join(tx['type'] for tx in json.load(open('$WORKDIR/t19.json'))['transactions']))
")
if [ "$code" = "200" ] && [ "$types" = "refund,debit,claim" ]; then
  pass "dave's transaction log is [refund, debit, claim] (most-recent first): $types"
else
  fail "expected types=refund,debit,claim, got code=$code types=$types: $(cat "$WORKDIR/t19.json")"
fi

log "--- test 20: /price/history reconstructs the price series from the same event log /price reads live ---"
code=$(curl -s -o "$WORKDIR/t20.json" -w "%{http_code}" "http://127.0.0.1:$PROXY_PORT/price/history?points=5")
current_price=$(curl -s "http://127.0.0.1:$PROXY_PORT/price" | python3 -c "import json,sys;print(json.load(sys.stdin)['price_usd'])")
history_check=$(python3 -c "
import json
body = json.load(open('$WORKDIR/t20.json'))
points = body['points']
if len(points) != 5:
    print('wrong point count: %d' % len(points)); exit()
ats = [p['at'] for p in points]
if ats != sorted(ats):
    print('points not in chronological order'); exit()
last_price = points[-1]['price_usd']
current = $current_price
if abs(last_price - current) > 0.01 * max(current, 1e-9):
    print('last point (%s) does not match live /price (%s)' % (last_price, current)); exit()
print('ok')
")
if [ "$code" = "200" ] && [ "$history_check" = "ok" ]; then
  pass "price history has 5 chronologically-ordered points, last one matching live /price ($current_price)"
else
  fail "expected ok, got code=$code check=$history_check: $(cat "$WORKDIR/t20.json")"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "=== ALL E2E CHECKS PASSED ==="
  exit 0
else
  echo "=== E2E FAILURES ABOVE — logs in $WORKDIR ==="
  echo "--- proxy.log ---"; tail -80 "$WORKDIR/proxy.log"
  exit 1
fi
