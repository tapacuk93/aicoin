package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"testing"
	"time"
)

// These pin the two signature constructions against CONTRACT.md's wording rather than against this
// CLI's own behaviour. Both are exact-match protocols: one byte out of place in the canonical
// message, or a padded base64 where the wallet page emits unpadded, and the proxy answers 401 to
// everything this CLI sends — with no clue as to which half is wrong.

func testWallet(t *testing.T) *Wallet {
	t.Helper()
	wallet, err := newWallet()
	if err != nil {
		t.Fatalf("newWallet: %v", err)
	}
	return wallet
}

func TestAddressIsTheHexPublicKey(t *testing.T) {
	wallet := testWallet(t)
	seed, err := hex.DecodeString(wallet.Seed)
	if err != nil {
		t.Fatalf("seed is not hex: %v", err)
	}
	expected := hex.EncodeToString(ed25519.NewKeyFromSeed(seed).Public().(ed25519.PublicKey))
	if wallet.Address != expected {
		t.Fatalf("address %s is not the public key %s", wallet.Address, expected)
	}
	if len(wallet.Address) != 64 {
		t.Fatalf("address must be 64 hex chars, got %d", len(wallet.Address))
	}
}

func TestImportAcceptsASeedOrAnExpandedKeyAndAgreesOnTheAddress(t *testing.T) {
	wallet := testWallet(t)
	seed, _ := hex.DecodeString(wallet.Seed)
	expanded := hex.EncodeToString(ed25519.NewKeyFromSeed(seed))

	fromSeed, err := walletFromSeed(wallet.Seed)
	if err != nil {
		t.Fatalf("import seed: %v", err)
	}
	fromExpanded, err := walletFromSeed(expanded)
	if err != nil {
		t.Fatalf("import expanded key: %v", err)
	}
	if fromSeed.Address != wallet.Address || fromExpanded.Address != wallet.Address {
		t.Fatalf("importing the same key gave different addresses: %s / %s / %s",
			wallet.Address, fromSeed.Address, fromExpanded.Address)
	}
	if _, err := walletFromSeed("not hex"); err == nil {
		t.Fatal("expected an error importing a non-hex key")
	}
	if _, err := walletFromSeed("abcd"); err == nil {
		t.Fatal("expected an error importing a key of the wrong length")
	}
}

// The canonical message is fixed by CONTRACT.md:
//
//	address \n timestampMillis \n method \n path \n hex(sha256(body))
func TestLiveSignatureMatchesTheCanonicalMessage(t *testing.T) {
	wallet := testWallet(t)
	body := []byte(`{"to_user_id":"abc","amount":1}`)

	headers := wallet.signLive("POST", "/wallet/api/transfer", body)

	if headers["X-Api-Key"] != wallet.Address {
		t.Fatalf("X-Api-Key must be the bare address, got %s", headers["X-Api-Key"])
	}
	timestamp, err := strconv.ParseInt(headers["X-Api-Timestamp"], 10, 64)
	if err != nil {
		t.Fatalf("timestamp is not a number: %v", err)
	}
	if drift := time.Since(time.UnixMilli(timestamp)); drift > time.Minute || drift < -time.Minute {
		t.Fatalf("timestamp is %v away from now; the proxy's skew window is 120s", drift)
	}
	sum := sha256.Sum256(body)
	message := fmt.Sprintf("%s\n%d\n%s\n%s\n%s",
		wallet.Address, timestamp, "POST", "/wallet/api/transfer", hex.EncodeToString(sum[:]))
	signature, err := hex.DecodeString(headers["X-Api-Signature"])
	if err != nil {
		t.Fatalf("signature is not hex: %v", err)
	}
	if len(signature) != ed25519.SignatureSize {
		t.Fatalf("signature must be the raw 64-byte form, got %d bytes", len(signature))
	}
	public, _ := hex.DecodeString(wallet.Address)
	if !ed25519.Verify(public, []byte(message), signature) {
		t.Fatal("the signature does not verify against the canonical message")
	}
}

func TestLiveSignatureIsBoundToTheRequestItSigns(t *testing.T) {
	// The whole point of signing method, path and body: a signature lifted off one request must
	// not authorise another.
	wallet := testWallet(t)
	body := []byte(`{"amount":1}`)
	headers := wallet.signLive("POST", "/wallet/api/transfer", body)
	signature, _ := hex.DecodeString(headers["X-Api-Signature"])
	public, _ := hex.DecodeString(wallet.Address)
	timestamp := headers["X-Api-Timestamp"]

	tampered := sha256.Sum256([]byte(`{"amount":999}`))
	message := fmt.Sprintf("%s\n%s\n%s\n%s\n%s",
		wallet.Address, timestamp, "POST", "/wallet/api/transfer", hex.EncodeToString(tampered[:]))
	if ed25519.Verify(public, []byte(message), signature) {
		t.Fatal("a signature over one body verified against another")
	}
}

// Token format, per CONTRACT.md: `base64url(payloadJson).base64url(signature)`, unpadded, with the
// signature over the encoded payload string — not over the raw JSON.
func TestTokenIsSignedOverTheEncodedPayload(t *testing.T) {
	wallet := testWallet(t)

	token, err := wallet.token(24 * time.Hour)
	if err != nil {
		t.Fatalf("token: %v", err)
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		t.Fatalf("a token is two dot-separated parts, got %d", len(parts))
	}
	if strings.Contains(token, "=") {
		t.Fatal("the wallet page emits unpadded base64url; padding here would not match it")
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		t.Fatalf("payload is not base64url: %v", err)
	}
	var payload struct {
		Addr string `json:"addr"`
		Iat  int64  `json:"iat"`
		Exp  int64  `json:"exp"`
	}
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		t.Fatalf("payload is not JSON: %v", err)
	}
	if payload.Addr != wallet.Address {
		t.Fatalf("payload address %s is not the wallet's %s", payload.Addr, wallet.Address)
	}
	if payload.Exp-payload.Iat != int64((24 * time.Hour).Seconds()) {
		t.Fatalf("expiry should be the requested span, got %d seconds", payload.Exp-payload.Iat)
	}
	if payload.Iat > time.Now().Unix()+5 {
		t.Fatal("iat is in the future; the proxy compares it against its own clock")
	}

	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatalf("signature is not base64url: %v", err)
	}
	public, _ := hex.DecodeString(wallet.Address)
	if !ed25519.Verify(public, []byte(parts[0]), signature) {
		t.Fatal("the signature must cover the encoded payload string, exactly as sent")
	}
	if ed25519.Verify(public, payloadBytes, signature) {
		t.Fatal("signing the raw JSON instead of the encoded form reintroduces key-ordering ambiguity")
	}
}

func TestFormatCoinsReadsLikeABalance(t *testing.T) {
	cases := map[float64]string{10: "10", 0: "0", 2.5: "2.5", 1.25: "1.25", 0.5: "0.5"}
	for value, expected := range cases {
		if got := formatCoins(value); got != expected {
			t.Errorf("formatCoins(%v) = %q, want %q", value, got, expected)
		}
	}
}

func TestApiErrorSurfacesWhatTheProxySaid(t *testing.T) {
	// The proxy's error text is the useful part — "insufficient aicoin balance" with the balance
	// is an answer, "HTTP 402" is not.
	err := &apiError{Status: 402, Body: `{"error":"insufficient aicoin balance","balance":0.5}`}
	if got := err.Error(); got != "insufficient aicoin balance (balance 0.5)" {
		t.Fatalf("got %q", got)
	}
	plain := &apiError{Status: 500, Body: "upstream exploded"}
	if got := plain.Error(); !strings.Contains(got, "upstream exploded") {
		t.Fatalf("a non-JSON body should still be shown, got %q", got)
	}
}
