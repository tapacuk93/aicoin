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
# balance mutations. Calls to a provider's free targets (model/voice
# listings, token counting — the endpoints the provider doesn't bill the
# proxy for) are forwarded with the proxy's key but cost no coin and record
# no price event; tests 21-23 pin that boundary, including that it fails
# closed on a path that would normalize onto a billed endpoint. Every provider points at the local mock so every paid
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
# live pool. Sized so the legitimate claims add up to exactly the pool, which
# is what lets the exhaustion test below assert that erin — and only erin —
# finds it empty. Derived rather than hand-counted: the funder claims are one
# per provider, so a provider added to PROVIDERS used to silently overdraw the
# pool and fail an unrelated test three hundred lines away.
#   3 claims before the exhaustion test (carol, alice, dave)
# + one funder claim per provider swept below
# + 4 consortium wallets (grace, heidi, ivan, and one more that claims only
#   to fund grace): a consortium is several paid calls, and grace runs three
#   of them
CLAIM_AMOUNT=10
# (the size itself is computed below, once PROVIDERS is known)

PROVIDERS=(openai anthropic google mistral cohere elevenlabs stability kimi)
AUTH_HEADERS=(Authorization x-api-key "" Authorization Authorization xi-api-key Authorization Authorization)
AUTH_PREFIXES=("Bearer " "" "" "Bearer " "Bearer " "" "Bearer " "Bearer ")
TEST_KEYS=(openai-test-key anthropic-test-key google-test-key mistral-test-key cohere-test-key elevenlabs-test-key stability-test-key kimi-test-key)
FREE_COINS_POOL_SIZE=$(( CLAIM_AMOUNT * (3 + ${#PROVIDERS[@]} + 4) ))

PROXY_BIN="$REPO_ROOT/aicoin-proxy/build/install/aicoin-proxy/bin/aicoin-proxy"
# Reuse the existing build only when nothing has been edited since it was made. Reusing it
# unconditionally meant a source change could be tested against the previous binary — the suite
# then passes or fails on code that is no longer in the tree, which is worse than a slow run.
if [ -x "$PROXY_BIN" ] && [ -z "$(find "$REPO_ROOT/aicoin-proxy/src" "$REPO_ROOT/aicoin-proxy/build.gradle" -newer "$PROXY_BIN" -print -quit 2>/dev/null)" ]; then
  log "reusing existing aicoin-proxy build at $PROXY_BIN (nothing newer in src/)"
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
# A local redis-server is preferred over the container purely so this suite runs on a machine
# with no Docker daemon up (a laptop with colima stopped, say). Same server either way — the
# ledger's Lua scripts are what is under test, not how Redis was started.
if command -v redis-server >/dev/null 2>&1; then
  redis-server --port "$REDIS_PORT" --save "" --appendonly no > "$WORKDIR/redis.log" 2>&1 &
  PIDS+=($!)
  log "using local redis-server"
else
  REDIS_CONTAINER=$(docker run -d --rm -p "$REDIS_PORT:6379" redis:7-alpine) || { log "no redis-server on PATH and docker run redis failed"; exit 1; }
  log "using the redis:7-alpine container"
fi

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
AICOIN_PROXY_KIMI_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_KIMI_APIKEY="${TEST_KEYS[7]}" \
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

log "--- test 4: proxy /health lists every configured provider ---"
health=$(curl -s "http://127.0.0.1:$PROXY_PORT/health")
count=$(echo "$health" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['providers']))")
[ "$count" = "${#PROVIDERS[@]}" ] && pass "${#PROVIDERS[@]} providers listed" || fail "expected ${#PROVIDERS[@]} providers, got: $health"

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

# 2 aicoin per funder, not 1: billing is metered, so a call costs what it cost to run rather
# than a flat coin — the two providers priced per call (ElevenLabs, Stability at $0.03) come to
# 4 coins each at the current coin value, and a wallet funded one-coin-per-provider ran dry
# partway through the sweep below.
FUNDING_PER_PROVIDER=2
log "--- funding: dave claims (for test 16's refund check) and one fresh wallet per provider claims + transfers $FUNDING_PER_PROVIDER aicoin to frank (for the one-call-per-provider test) ---"
code=$(live_signed_request "$KEY_DAVE" "$ADDR_DAVE" "POST" "/wallet/api/claim" "" "$WORKDIR/dave-claim.json")
[ "$code" = "200" ] || fail "expected dave's claim to succeed, got $code: $(cat "$WORKDIR/dave-claim.json")"
for i in "${!PROVIDERS[@]}"; do
  fkey="$WORKDIR/funder$i.pem"; gen_wallet "$fkey"; faddr=$(wallet_address "$fkey")
  code=$(live_signed_request "$fkey" "$faddr" "POST" "/wallet/api/claim" "" "$WORKDIR/funder$i-claim.json")
  [ "$code" = "200" ] || fail "expected funder$i's claim to succeed, got $code"
  code=$(live_signed_request "$fkey" "$faddr" "POST" "/wallet/api/transfer" "{\"to_user_id\":\"$ADDR_FRANK\",\"amount\":$FUNDING_PER_PROVIDER}" "$WORKDIR/funder$i-transfer.json")
  [ "$code" = "200" ] || fail "expected funder$i's transfer to frank to succeed, got $code"
done
bal_frank=$(balance_of "$ADDR_FRANK")
[ "$bal_frank" = "$(( FUNDING_PER_PROVIDER * ${#PROVIDERS[@]} ))" ] \
  || fail "expected frank funded to $(( FUNDING_PER_PROVIDER * ${#PROVIDERS[@]} )), got $bal_frank"

log "--- funding: three wallets claim for the consortium tests (a consortium is many paid calls, not one) ---"
KEY_GRACE="$WORKDIR/grace.pem"; gen_wallet "$KEY_GRACE"; ADDR_GRACE=$(wallet_address "$KEY_GRACE")
KEY_HEIDI="$WORKDIR/heidi.pem"; gen_wallet "$KEY_HEIDI"; ADDR_HEIDI=$(wallet_address "$KEY_HEIDI")
KEY_IVAN="$WORKDIR/ivan.pem"; gen_wallet "$KEY_IVAN"; ADDR_IVAN=$(wallet_address "$KEY_IVAN")
KEY_JACK="$WORKDIR/jack.pem"; gen_wallet "$KEY_JACK"; ADDR_JACK=$(wallet_address "$KEY_JACK")
for pair in "$KEY_GRACE:$ADDR_GRACE" "$KEY_HEIDI:$ADDR_HEIDI" "$KEY_IVAN:$ADDR_IVAN" "$KEY_JACK:$ADDR_JACK"; do
  ckey="${pair%%:*}"; caddr="${pair##*:}"
  code=$(live_signed_request "$ckey" "$caddr" "POST" "/wallet/api/claim" "" "$WORKDIR/consortium-claim.json")
  [ "$code" = "200" ] || fail "expected a consortium wallet's claim to succeed, got $code"
done
# Grace runs three consortium calls below (one panel-shaped, two mode tests), which is more than
# one claim covers.
code=$(live_signed_request "$KEY_JACK" "$ADDR_JACK" "POST" "/wallet/api/transfer" \
  "{\"to_user_id\":\"$ADDR_GRACE\",\"amount\":$CLAIM_AMOUNT}" "$WORKDIR/jack-transfer.json")
[ "$code" = "200" ] || fail "expected jack's transfer to grace to succeed, got $code"

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
  headerfile="$WORKDIR/t12-$provider.headers"
  bal_before=$(balance_of "$ADDR_FRANK")
  code=$(curl -s -o "$outfile" -D "$headerfile" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" \
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
  # Billing is metered, so what the call costs is the call's own cost rounded up to whole coins —
  # not a constant this test can hardcode. The proxy states the figure in X-Aicoin-Charged, and
  # what this asserts is that the wallet moved by exactly that and by nothing else.
  charged=$(grep -i "^X-Aicoin-Charged:" "$headerfile" | tr -d "\r" | awk "{print \$2}")
  expected_bal=$(python3 -c "print($bal_before - ${charged:-0})")
  if [ "$got_value" = "$expected_value" ] && [ -n "$charged" ] && [ "$bal_after" = "$expected_bal" ]; then
    pass "$provider: correct key injected ($got_value) and $charged aicoin debited (frank $bal_before -> $bal_after)"
  else
    fail "$provider: expected key '$expected_value' and balance $expected_bal (charged '$charged'), got key '$got_value' balance $bal_after"
  fi
done
# Whatever the sweep left, spend it down to nothing: the zero-balance tests below are about the
# balance gate, and they should not depend on the sweep's arithmetic landing exactly on zero.
bal_frank_final=$(balance_of "$ADDR_FRANK")
if python3 -c "import sys; sys.exit(0 if float('$bal_frank_final') > 0 else 1)"; then
  live_signed_request "$KEY_FRANK" "$ADDR_FRANK" "POST" "/wallet/api/transfer" \
    "{\"to_user_id\":\"$ADDR_BOB\",\"amount\":$bal_frank_final}" "$WORKDIR/frank-drain.json" > /dev/null
  bal_frank_final=$(balance_of "$ADDR_FRANK")
fi
[ "$bal_frank_final" = "0" ] && pass "frank paid for every provider call and is now at 0" || fail "expected frank balance 0 after all provider calls, got $bal_frank_final"

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

log "--- test 21: a free target (GET /v1/models) is forwarded without debiting a coin or recording a price event ---"
bal_before_free=$(balance_of "$ADDR_DAVE")
weighted_before_free=$(curl -s "http://127.0.0.1:$PROXY_PORT/price" | python3 -c "import json,sys;print(json.load(sys.stdin)['weighted_total'])")
FREE_TOKEN=$(build_token "$KEY_DAVE" "$ADDR_DAVE" "$(epoch_seconds)" "$(($(epoch_seconds) + 3600))")
code=$(curl -s -o "$WORKDIR/t21.json" -w "%{http_code}" "http://127.0.0.1:$PROXY_PORT/v1/models" \
  -H "X-Api-Key: $FREE_TOKEN" -H "X-AI: openai")
bal_after_free=$(balance_of "$ADDR_DAVE")
weighted_after_free=$(curl -s "http://127.0.0.1:$PROXY_PORT/price" | python3 -c "import json,sys;print(json.load(sys.stdin)['weighted_total'])")
# The mock echoes the injected key back, so this also confirms a free target still goes out with
# the proxy's own paid credential — free to the wallet, not unauthenticated upstream.
injected=$(python3 -c "import json;print(json.load(open('$WORKDIR/t21.json')).get('received_authorization'))")
# weighted_total is a decayed sum, so it drifts down by a hair between any two reads simply
# because time passed — comparing the two figures as strings made this test fail whenever the
# run was slow enough for the last digits to move. What it is actually asserting is that no
# *event* was recorded, and one event is worth ~1.0, so anything under half of that is noise.
no_new_event=$(python3 -c "print('yes' if 0 <= float('$weighted_before_free') - float('$weighted_after_free') < 0.5 else 'no')")
if [ "$code" = "200" ] && [ "$bal_after_free" = "$bal_before_free" ] \
   && [ "$no_new_event" = "yes" ] && [ "$injected" = "${AUTH_PREFIXES[0]}${TEST_KEYS[0]}" ]; then
  pass "free target relayed (200, key injected) with balance unchanged ($bal_after_free) and no price event (weighted_total still ~$weighted_after_free)"
else
  fail "expected 200 with balance $bal_before_free and weighted_total ~$weighted_before_free, got code=$code balance=$bal_after_free weighted=$weighted_after_free injected=$injected"
fi

log "--- test 22: a free target works at a zero balance, while a paid call at the same balance still 402s ---"
bal_frank_now=$(balance_of "$ADDR_FRANK")
code=$(curl -s -o "$WORKDIR/t22a.json" -w "%{http_code}" "http://127.0.0.1:$PROXY_PORT/v1/models" \
  -H "X-Api-Key: $FRANK_TOKEN" -H "X-AI: openai")
[ "$bal_frank_now" = "0" ] && [ "$code" = "200" ] \
  && pass "free target succeeded on a 0-balance wallet (no coin required to list models)" \
  || fail "expected 200 for a free target at balance 0, got code=$code balance=$bal_frank_now"
code=$(curl -s -o "$WORKDIR/t22b.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" \
  -H "X-Api-Key: $FRANK_TOKEN" -H "X-AI: openai" -H "Content-Type: application/json" -d '{"model":"test"}')
[ "$code" = "402" ] && pass "paid call at the same 0 balance still 402s — free targets didn't weaken the balance gate" \
  || fail "expected 402 for a paid call at balance 0, got $code: $(cat "$WORKDIR/t22b.json")"

log "--- test 23: a traversal path that would normalize onto a billed endpoint is not treated as free ---"
bal_before_traversal=$(balance_of "$ADDR_DAVE")
code=$(curl -s -o "$WORKDIR/t23.json" -w "%{http_code}" --path-as-is \
  "http://127.0.0.1:$PROXY_PORT/v1/models/../chat/completions" -H "X-Api-Key: $FREE_TOKEN" -H "X-AI: openai")
bal_after_traversal=$(balance_of "$ADDR_DAVE")
spent=$(python3 -c "print(round(float('$bal_before_traversal') - float('$bal_after_traversal'), 6))")
if [ "$code" = "200" ] && [ "$spent" = "1.0" ]; then
  pass "/v1/models/../chat/completions was billed 1 aicoin (fails closed to paid, no free ride)"
else
  fail "expected a 1-aicoin debit for the traversal path, got code=$code spent=$spent"
fi

log "--- test 24: a consortium call drafts with every panelist, merges, reviews, and stops when a round is clean ---"
NOW=$(epoch_seconds)
GRACE_TOKEN=$(build_token "$KEY_GRACE" "$ADDR_GRACE" "$NOW" "$((NOW + 86400))")
bal_grace_before=$(balance_of "$ADDR_GRACE")
code=$(curl -s -o "$WORKDIR/t24.json" -D "$WORKDIR/t24.headers" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $GRACE_TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"In one sentence, what is an aicoin?","providers":["openai","anthropic"]}')
bal_grace_after=$(balance_of "$ADDR_GRACE")
if [ "$code" = "200" ]; then
  read -r answer settled rounds calls charged panel editor <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t24.json'))
print(d['answer'].replace(' ', '_'), d['settled'], d['rounds'], d['calls'], d['coins_charged'],
      ','.join(d['panel']), d['editor'])
")"
  # 2 drafts + 1 merge + 2 reviews. Every one of them an ordinary paid call.
  [ "$calls" = "5" ] && [ "$charged" = "5" ] && pass "5 calls, 5 aicoin charged (2 drafts + merge + 2 reviews)" \
    || fail "expected 5 calls/5 coins, got calls=$calls charged=$charged"
  [ "$settled" = "True" ] && [ "$rounds" = "1" ] && pass "settled after one clean review round" \
    || fail "expected settled/1 round, got settled=$settled rounds=$rounds"
  [ "$answer" = "MERGED_ANSWER" ] && pass "the answer returned is the editor's merge, not one panelist's draft" \
    || fail "expected the merged answer, got $answer"
  # Canonical panel order, not the order the request happened to list them in.
  [ "$panel" = "anthropic,openai" ] && [ "$editor" = "anthropic" ] && pass "panel=[anthropic,openai], editor=anthropic (stable order)" \
    || fail "expected panel anthropic,openai editor anthropic; got panel=$panel editor=$editor"
  # The per-provider breakdown must account for every coin the call charged: it is what a client
  # reads to find out which model the money went to.
  spend_total=$(python3 -c "
import json
d = json.load(open('$WORKDIR/t24.json'))
print(sum(d.get('spend', {}).values()), len(d.get('spend', {})))
")
  [ "$spend_total" = "5 2" ] && pass "spend breaks the 5 coins down across both panelists" \
    || fail "expected the spend map to total 5 across 2 providers, got: $spend_total"
  spent=$(python3 -c "print(round(float('$bal_grace_before') - float('$bal_grace_after'), 6))")
  [ "$spent" = "5.0" ] && pass "wallet actually paid 5 aicoin" || fail "expected 5 aicoin spent, got $spent"
  grep -qi "^X-Aicoin-Charged: 5" "$WORKDIR/t24.headers" && pass "X-Aicoin-Charged: 5 (same header a single proxied call sets)" \
    || fail "missing/incorrect X-Aicoin-Charged: $(grep -i aicoin "$WORKDIR/t24.headers")"
else
  fail "expected 200 from /consortium, got $code: $(cat "$WORKDIR/t24.json")"
fi

log "--- test 25: a round with comments is revised and reviewed again, until a round comes back clean ---"
NOW=$(epoch_seconds)
HEIDI_TOKEN=$(build_token "$KEY_HEIDI" "$ADDR_HEIDI" "$NOW" "$((NOW + 86400))")
code=$(curl -s -o "$WORKDIR/t25.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $HEIDI_TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"NEEDS_ONE_ROUND_OF_COMMENTS: what is an aicoin?","providers":["openai","anthropic"]}')
if [ "$code" = "200" ]; then
  read -r answer settled rounds calls reason clean_first <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t25.json'))
first = [r for r in d['reviews'] if r['round'] == 1]
print(d['answer'].replace(' ', '_'), d['settled'], d['rounds'], d['calls'], d['stopped_reason'],
      all(r['clean'] for r in first))
")"
  [ "$clean_first" = "False" ] && pass "round 1 reviewers had comments" || fail "expected comments in round 1"
  [ "$rounds" = "2" ] && [ "$settled" = "True" ] && [ "$reason" = "clean" ] \
    && pass "round 2 came back clean; the call stopped there" || fail "expected 2 rounds ending clean, got rounds=$rounds settled=$settled reason=$reason"
  [ "$answer" = "REVISED_ANSWER" ] && pass "the answer returned is the revision, not the text the reviewers objected to" \
    || fail "expected the revised answer, got $answer"
  # 2 drafts + merge + 2 reviews + revise + 2 reviews.
  [ "$calls" = "8" ] && pass "8 calls for two rounds" || fail "expected 8 calls, got $calls"
else
  fail "expected 200 from /consortium, got $code: $(cat "$WORKDIR/t25.json")"
fi

log "--- test 26: reviewers that never clear are ended by the round cap, not by agreement ---"
NOW=$(epoch_seconds)
IVAN_TOKEN=$(build_token "$KEY_IVAN" "$ADDR_IVAN" "$NOW" "$((NOW + 86400))")
code=$(curl -s -o "$WORKDIR/t26.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $IVAN_TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"ALWAYS_COMMENTS: what is an aicoin?","providers":["openai","anthropic"],"max_rounds":1}')
if [ "$code" = "200" ]; then
  read -r settled rounds reason calls <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t26.json'))
print(d['settled'], d['rounds'], d['stopped_reason'], d['calls'])
")"
  [ "$settled" = "False" ] && [ "$reason" = "round_limit" ] && [ "$rounds" = "1" ] \
    && pass "stopped at the cap, and says so (settled=false, stopped_reason=round_limit)" \
    || fail "expected an unsettled round_limit stop, got settled=$settled reason=$reason rounds=$rounds"
  [ "$calls" = "5" ] && pass "the cap bounded the spend at 5 calls" || fail "expected 5 calls, got $calls"
else
  fail "expected 200 from /consortium, got $code: $(cat "$WORKDIR/t26.json")"
fi

log "--- test 27: a wallet that runs dry mid-consortium keeps the answer it paid for ---"
bal_ivan=$(balance_of "$ADDR_IVAN")
log "ivan has $bal_ivan aicoin left — enough for the drafts, the merge and one review round, not for the revision"
code=$(curl -s -o "$WORKDIR/t27.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $IVAN_TOKEN" -H "Content-Type: application/json" \
  -d '{"prompt":"ALWAYS_COMMENTS: what is an aicoin?","providers":["openai","anthropic"],"max_rounds":3}')
if [ "$code" = "200" ]; then
  read -r answer reason settled <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t27.json'))
print(d['answer'].replace(' ', '_'), d['stopped_reason'], d['settled'])
")"
  [ "$reason" = "insufficient_balance" ] && [ "$settled" = "False" ] \
    && pass "stopped on an empty wallet and said so, rather than 402-ing away work already paid for" \
    || fail "expected stopped_reason=insufficient_balance, got reason=$reason settled=$settled"
  [ "$answer" = "MERGED_ANSWER" ] && pass "the answer paid for is still returned" || fail "expected an answer, got $answer"
else
  fail "expected 200 from a consortium that ran out of coins, got $code: $(cat "$WORKDIR/t27.json")"
fi
bal_ivan_after=$(balance_of "$ADDR_IVAN")
python3 -c "import sys; sys.exit(0 if float('$bal_ivan_after') >= 0 else 1)" \
  && pass "balance never went negative ($bal_ivan_after)" || fail "balance went negative: $bal_ivan_after"

log "--- test 29: a context-heavy call is led by one model instead of drafted by all of them ---"
NOW=$(epoch_seconds)
GRACE_TOKEN2=$(build_token "$KEY_GRACE" "$ADDR_GRACE" "$NOW" "$((NOW + 86400))")
BIG_CONTEXT=$(python3 -c "print('x' * 9000)")
python3 -c "
import json
print(json.dumps({'prompt': 'what is an aicoin?', 'context': '$BIG_CONTEXT',
                  'providers': ['openai', 'anthropic'], 'max_rounds': 1}))
" > "$WORKDIR/t29-req.json"
code=$(curl -s -o "$WORKDIR/t29.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $GRACE_TOKEN2" -H "Content-Type: application/json" --data-binary "@$WORKDIR/t29-req.json")
if [ "$code" = "200" ]; then
  read -r mode calls editor <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t29.json'))
print(d['mode'], d['calls'], d['editor'])
")"
  # One draft from the lead, then one review per panelist — not one draft each plus a merge.
  [ "$mode" = "lead" ] && [ "$calls" = "3" ] \
    && pass "led by $editor: 1 draft + 2 reviews, where the panel shape would have cost 5" \
    || fail "expected mode=lead and 3 calls, got mode=$mode calls=$calls"
else
  fail "expected 200, got $code: $(cat "$WORKDIR/t29.json")"
fi

log "--- test 30: the caller can insist on the panel shape whatever the context size ---"
python3 -c "
import json
print(json.dumps({'prompt': 'what is an aicoin?', 'context': '$BIG_CONTEXT', 'mode': 'panel',
                  'providers': ['openai', 'anthropic'], 'max_rounds': 1}))
" > "$WORKDIR/t30-req.json"
code=$(curl -s -o "$WORKDIR/t30.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $GRACE_TOKEN2" -H "Content-Type: application/json" --data-binary "@$WORKDIR/t30-req.json")
if [ "$code" = "200" ]; then
  read -r mode calls <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t30.json'))
print(d['mode'], d['calls'])
")"
  [ "$mode" = "panel" ] && [ "$calls" = "5" ] && pass "mode=panel forced: 2 drafts + merge + 2 reviews" \
    || fail "expected mode=panel and 5 calls, got mode=$mode calls=$calls"
else
  fail "expected 200, got $code: $(cat "$WORKDIR/t30.json")"
fi
code=$(curl -s -o "$WORKDIR/t30b.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $GRACE_TOKEN2" -H "Content-Type: application/json" -d '{"prompt":"hi","mode":"sideways"}')
[ "$code" = "400" ] && pass "400 on an unknown mode" || fail "expected 400 for a bad mode, got $code"

log "--- test 31: POST /admin/credit puts coins in a wallet, once per reference ---"
bal_bob_before=$(balance_of "$ADDR_BOB")
code=$(curl -s -o "$WORKDIR/t31.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/admin/credit" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"address\":\"$ADDR_BOB\",\"amount\":250,\"reason\":\"e2e top-up\",\"reference\":\"e2e-credit-1\"}")
bal_bob_after=$(balance_of "$ADDR_BOB")
gained=$(python3 -c "print(round(float('$bal_bob_after') - float('$bal_bob_before'), 6))")
if [ "$code" = "200" ] && [ "$gained" = "250.0" ]; then
  pass "credited 250 aicoin (bob $bal_bob_before -> $bal_bob_after)"
else
  fail "expected 200 and +250, got code=$code gained=$gained: $(cat "$WORKDIR/t31.json")"
fi

# The same reference again must not credit again: a retried request is the one way an admin
# endpoint quietly doubles somebody's money.
code=$(curl -s -o "$WORKDIR/t31b.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/admin/credit" \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"address\":\"$ADDR_BOB\",\"amount\":250,\"reason\":\"e2e top-up\",\"reference\":\"e2e-credit-1\"}")
bal_bob_replay=$(balance_of "$ADDR_BOB")
credited=$(python3 -c "import json;print(json.load(open('$WORKDIR/t31b.json')).get('credited'))")
[ "$code" = "200" ] && [ "$credited" = "False" ] && [ "$bal_bob_replay" = "$bal_bob_after" ] \
  && pass "the same reference credits once (replay is a no-op, not an error)" \
  || fail "expected a no-op replay, got code=$code credited=$credited balance=$bal_bob_replay"

# It lands in the wallet's own transaction log, so the balance is always explained.
code=$(curl -s -o "$WORKDIR/t31c.json" -w "%{http_code}" "http://127.0.0.1:$PROXY_PORT/admin/wallets/$ADDR_BOB/transactions" \
  -H "X-Admin-Token: $ADMIN_TOKEN")
has_entry=$(python3 -c "
import json
d = json.load(open('$WORKDIR/t31c.json'))
print(any(t.get('type') == 'admin_credit' and t.get('amount') == 250 for t in d['transactions']))
")
[ "$has_entry" = "True" ] && pass "the credit is in the transaction log as admin_credit" \
  || fail "expected an admin_credit entry: $(cat "$WORKDIR/t31c.json")"

log "--- test 32: /admin/credit refuses what it should ---"
code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/admin/credit" \
  -H "Content-Type: application/json" -d "{\"address\":\"$ADDR_BOB\",\"amount\":1}")
[ "$code" = "401" ] && pass "401 without the admin token" || fail "expected 401, got $code"
for body in "{\"address\":\"nothex\",\"amount\":1}" "{\"address\":\"$ADDR_BOB\",\"amount\":-5}" "{\"address\":\"$ADDR_BOB\",\"amount\":0}" "{\"address\":\"$ADDR_BOB\"}"; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/admin/credit" \
    -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" -d "$body")
  [ "$code" = "400" ] && pass "400 for $body" || fail "expected 400 for $body, got $code"
done

log "--- test 28: /consortium is a paid endpoint and validates its body ---"
code=$(curl -s -o "$WORKDIR/t28a.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" -d '{"prompt":"hi"}')
[ "$code" = "401" ] && pass "401 without an API token" || fail "expected 401, got $code: $(cat "$WORKDIR/t28a.json")"
code=$(curl -s -o "$WORKDIR/t28b.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/consortium" \
  -H "X-Api-Key: $GRACE_TOKEN" -H "Content-Type: application/json" -d '{"providers":["openai"]}')
[ "$code" = "400" ] && pass "400 with no prompt (before any provider is touched)" || fail "expected 400, got $code: $(cat "$WORKDIR/t28b.json")"

log "--- test 33: a note takes coins out of a wallet and can be redeemed by whoever holds it ---"
bal_alice_before=$(balance_of "$ADDR_ALICE")
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/notes/issue" \
  '{"amounts":[2,1],"ttl_seconds":3600}' "$WORKDIR/t33-issue.json")
bal_alice_after=$(balance_of "$ADDR_ALICE")
if [ "$code" = "200" ]; then
  read -r count first_note first_amount first_hash <<<"$(python3 -c "
import json
d = json.load(open('$WORKDIR/t33-issue.json'))
n = d['notes'][0]
print(len(d['notes']), n['note'], n['amount'], n['hash'])
")"
  spent=$(python3 -c "print(round(float('$bal_alice_before') - float('$bal_alice_after'), 6))")
  # The coins leave at issue, which is what stops the issuer spending them while the note is out.
  [ "$count" = "2" ] && [ "$spent" = "3.0" ] && pass "2 notes issued and 3 aicoin left the wallet immediately" \
    || fail "expected 2 notes and a 3-coin debit, got count=$count spent=$spent"

  state=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/notes/status/$first_hash" | python3 -c "import json,sys;print(json.load(sys.stdin)['state'])")
  [ "$state" = "open" ] && pass "the note reads as open, asked for by hash rather than by secret" \
    || fail "expected state=open, got $state"

  # Bob was handed the note offline; this is him coming back online with it.
  bal_bob_before=$(balance_of "$ADDR_BOB")
  code=$(live_signed_request "$KEY_BOB" "$ADDR_BOB" "POST" "/wallet/api/notes/redeem" \
    "{\"note\":\"$first_note\"}" "$WORKDIR/t33-redeem.json")
  bal_bob_after=$(balance_of "$ADDR_BOB")
  gained=$(python3 -c "print(round(float('$bal_bob_after') - float('$bal_bob_before'), 6))")
  [ "$code" = "200" ] && [ "$gained" = "$(python3 -c "print(float('$first_amount'))")" ] \
    && pass "the holder redeemed it: bob $bal_bob_before -> $bal_bob_after" \
    || fail "expected bob to gain $first_amount, got code=$code gained=$gained"

  # The one thing offline hand-off cannot prevent, handled the only way it can be: first come.
  code=$(live_signed_request "$KEY_CAROL" "$ADDR_CAROL" "POST" "/wallet/api/notes/redeem" \
    "{\"note\":\"$first_note\"}" "$WORKDIR/t33-again.json")
  reason=$(python3 -c "import json;d=json.load(open('$WORKDIR/t33-again.json'));print(d.get('credited'), d.get('reason'))")
  [ "$reason" = "False redeemed" ] && pass "the second person to try is told it was already redeemed" \
    || fail "expected a refusal naming 'redeemed', got $reason"
else
  fail "expected 200 from note issue, got $code: $(cat "$WORKDIR/t33-issue.json")"
fi

log "--- test 34: an unredeemed note can be taken back by the wallet that issued it ---"
second_note=$(python3 -c "
import json
print(json.load(open('$WORKDIR/t33-issue.json'))['notes'][1]['note'])
")
bal_before_reclaim=$(balance_of "$ADDR_ALICE")
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/notes/reclaim" \
  "{\"note\":\"$second_note\"}" "$WORKDIR/t34.json")
bal_after_reclaim=$(balance_of "$ADDR_ALICE")
back=$(python3 -c "print(round(float('$bal_after_reclaim') - float('$bal_before_reclaim'), 6))")
[ "$code" = "200" ] && [ "$back" = "1.0" ] && pass "1 aicoin came back — a lost note does not burn the coins" \
  || fail "expected 1 aicoin back, got code=$code back=$back"

# And nobody else can take one back, however genuine the note is in their hands.
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/notes/issue" \
  '{"amounts":[1]}' "$WORKDIR/t34-b.json")
third_note=$(python3 -c "import json;print(json.load(open('$WORKDIR/t34-b.json'))['notes'][0]['note'])")
code=$(live_signed_request "$KEY_BOB" "$ADDR_BOB" "POST" "/wallet/api/notes/reclaim" \
  "{\"note\":\"$third_note\"}" "$WORKDIR/t34-c.json")
reason=$(python3 -c "import json;d=json.load(open('$WORKDIR/t34-c.json'));print(d.get('reclaimed'), d.get('reason'))")
[ "$reason" = "False not_issuer" ] && pass "a holder cannot reclaim somebody else's note (only redeem it)" \
  || fail "expected not_issuer, got $reason"

log "--- test 36: a note made out to one wallet cannot be redeemed by another ---"
code=$(live_signed_request "$KEY_ALICE" "$ADDR_ALICE" "POST" "/wallet/api/notes/issue" \
  "{\"amounts\":[1],\"payee\":\"$ADDR_BOB\"}" "$WORKDIR/t36-issue.json")
bound_note=$(python3 -c "import json;print(json.load(open('$WORKDIR/t36-issue.json'))['notes'][0]['note'])")
# Carol was handed it — genuinely, and it is genuinely worthless to her.
code=$(live_signed_request "$KEY_CAROL" "$ADDR_CAROL" "POST" "/wallet/api/notes/redeem" \
  "{\"note\":\"$bound_note\"}" "$WORKDIR/t36-carol.json")
reason=$(python3 -c "import json;d=json.load(open('$WORKDIR/t36-carol.json'));print(d.get('credited'), d.get('reason'))")
[ "$reason" = "False not_payee" ] && pass "anyone but the payee is refused — this note cannot be double-spent, only wasted" \
  || fail "expected not_payee, got $reason"

bal_bob_before=$(balance_of "$ADDR_BOB")
code=$(live_signed_request "$KEY_BOB" "$ADDR_BOB" "POST" "/wallet/api/notes/redeem" \
  "{\"note\":\"$bound_note\"}" "$WORKDIR/t36-bob.json")
bal_bob_after=$(balance_of "$ADDR_BOB")
gained=$(python3 -c "print(round(float('$bal_bob_after') - float('$bal_bob_before'), 6))")
[ "$code" = "200" ] && [ "$gained" = "1.0" ] && pass "the named payee redeems it normally" \
  || fail "expected bob to gain 1, got code=$code gained=$gained"

# And the binding is on the note itself, so a receiver can see it before going anywhere near a network.
payee_on_note=$(python3 -c "
import base64, json
note = open('$WORKDIR/t36-issue.json'); d = json.load(note)['notes'][0]['note']
p = d.split('.')[0]
print(json.loads(base64.urlsafe_b64decode(p + '=' * (-len(p) % 4)))['pay'])
")
[ "$payee_on_note" = "$ADDR_BOB" ] && pass "the binding travels on the note, checkable offline" \
  || fail "expected the payee in the note payload, got $payee_on_note"

log "--- test 35: the ledger publishes the key a receiver verifies notes with offline ---"
key=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/notes/key" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['public_key'], d['algorithm'])")
python3 - "$key" "$third_note" <<'PYCHECK'
import base64, json, sys, hashlib
key_hex, algorithm = sys.argv[1].split()
note = sys.argv[2]
assert algorithm == "ed25519", algorithm
payload_b64, sig_b64 = note.split(".")
def unpad(s): return s + "=" * (-len(s) % 4)
payload = json.loads(base64.urlsafe_b64decode(unpad(payload_b64)))
assert payload["amt"] == 1, payload
# Verified with nothing but the published key and the note itself — no ledger lookup, which is the
# whole point of the offline half.
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    Ed25519PublicKey.from_public_bytes(bytes.fromhex(key_hex)).verify(
        base64.urlsafe_b64decode(unpad(sig_b64)), payload_b64.encode())
    print("verified")
except ImportError:
    print("skipped (no cryptography module)")
PYCHECK
if [ $? -eq 0 ]; then pass "a note verifies against the published key with no ledger lookup"; else fail "offline verification failed"; fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "=== ALL E2E CHECKS PASSED ==="
  exit 0
else
  echo "=== E2E FAILURES ABOVE — logs in $WORKDIR ==="
  echo "--- proxy.log ---"; tail -80 "$WORKDIR/proxy.log"
  exit 1
fi
