package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// The offline half. What these hold to: a receiver with no network can tell a genuine note from a
// forged one and read its value off it — and cannot tell whether it has already been spent, which
// is stated rather than papered over.

func signedNote(t *testing.T, key ed25519.PrivateKey, id string, amount float64, expires int64) string {
	t.Helper()
	payload, err := json.Marshal(notePayload{Version: 1, ID: id, Amount: amount, Issuer: strings.Repeat("a", 64), Expires: expires})
	if err != nil {
		t.Fatal(err)
	}
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	return encoded + "." + base64.RawURLEncoding.EncodeToString(ed25519.Sign(key, []byte(encoded)))
}

func TestAGenuineNoteVerifiesWithNoNetwork(t *testing.T) {
	public, private, _ := ed25519.GenerateKey(nil)
	note := signedNote(t, private, strings.Repeat("b", 64), 25, time.Now().Add(24*time.Hour).Unix())

	payload, err := verifyNote(note, hex.EncodeToString(public))
	if err != nil {
		t.Fatalf("a genuine note should verify offline: %v", err)
	}
	if payload.Amount != 25 {
		t.Fatalf("the amount is read off the note itself, got %v", payload.Amount)
	}
}

func TestANoteTheLedgerDidNotSignIsRejected(t *testing.T) {
	public, _, _ := ed25519.GenerateKey(nil)
	_, someoneElse, _ := ed25519.GenerateKey(nil)

	// Forged outright.
	note := signedNote(t, someoneElse, strings.Repeat("c", 64), 1000, time.Now().Add(time.Hour).Unix())
	if _, err := verifyNote(note, hex.EncodeToString(public)); err == nil {
		t.Fatal("a note signed by the wrong key must not verify")
	}

	// Genuine note, amount edited afterwards — the signature covers the payload, so this breaks.
	real := signedNote(t, someoneElse, strings.Repeat("d", 64), 1, time.Now().Add(time.Hour).Unix())
	tampered := strings.Replace(real, real[:10], real[:10], 1)
	tampered = "eyJ2IjoxLCJhbXQiOjEwMDB9" + tampered[strings.Index(tampered, "."):]
	if _, err := verifyNote(tampered, hex.EncodeToString(public)); err == nil {
		t.Fatal("an edited payload must not verify")
	}
}

func TestAnExpiredNoteSaysSo(t *testing.T) {
	public, private, _ := ed25519.GenerateKey(nil)
	note := signedNote(t, private, strings.Repeat("e", 64), 5, time.Now().Add(-time.Hour).Unix())
	_, err := verifyNote(note, hex.EncodeToString(public))
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expected an expiry error, got %v", err)
	}
}

func TestWithoutTheCachedKeyNothingIsClaimedAboutTheNote(t *testing.T) {
	// The wallet has never been online, so it cannot verify. It says that, rather than accepting
	// on trust and calling it genuine.
	_, private, _ := ed25519.GenerateKey(nil)
	note := signedNote(t, private, strings.Repeat("f", 64), 5, time.Now().Add(time.Hour).Unix())
	_, err := verifyNote(note, "")
	if err == nil || !strings.Contains(err.Error(), "ledger key") {
		t.Fatalf("expected a missing-key error, got %v", err)
	}
}

func TestTheFingerprintComesFromTheHashNotTheSecret(t *testing.T) {
	id := strings.Repeat("9", 64)
	fingerprint := fingerprintOf(id)
	if !strings.HasPrefix(strings.ToUpper(hashOfNoteID(id)), strings.ReplaceAll(fingerprint, "-", "")) {
		t.Fatal("the fingerprint should be the head of the hash")
	}
	if strings.HasPrefix(strings.ToUpper(id), strings.ReplaceAll(fingerprint, "-", "")) {
		t.Fatal("reading it aloud must not give away the secret")
	}
}

func TestAPurseCanMakeExactAmountsOrRefuses(t *testing.T) {
	p := &purse{Mine: []heldNote{
		{Amount: 50, Hash: "a"}, {Amount: 10, Hash: "b"}, {Amount: 5, Hash: "c"}, {Amount: 1, Hash: "d"},
	}}

	chosen, ok := p.pick(15)
	if !ok {
		t.Fatal("10 + 5 makes 15")
	}
	var sum float64
	for _, note := range chosen {
		sum += note.Amount
	}
	if sum != 15 {
		t.Fatalf("expected exactly 15, got %v", sum)
	}

	// Exact or nothing: paying 3 with a 5 hands over more than is owed, and a note cannot be
	// broken in half without a network.
	if _, ok := p.pick(3); ok {
		t.Fatal("the purse cannot make 3 from 50/10/5/1")
	}
	if _, ok := p.pick(100); ok {
		t.Fatal("the purse cannot make more than it holds")
	}
}

func TestHandedOverNotesStopBeingSpendableAndBecomeReceipts(t *testing.T) {
	p := &purse{Mine: []heldNote{{Amount: 10, Hash: "a"}, {Amount: 5, Hash: "b"}}}
	chosen, _ := p.pick(10)
	p.handOver(chosen)

	if len(p.Mine) != 1 || p.Mine[0].Hash != "b" {
		t.Fatalf("the handed-over note should no longer be carried: %+v", p.Mine)
	}
	if p.total() != 5 {
		t.Fatalf("expected 5 left to spend, got %v", p.total())
	}
	// Kept as a receipt: the issuer needs the string to reclaim coins a receiver never redeemed,
	// and — unavoidably — it is what makes a deliberate replay possible.
	if len(p.Spent) != 1 || p.Spent[0].Hash != "a" || p.Spent[0].HandedAt == 0 {
		t.Fatalf("expected a dated receipt: %+v", p.Spent)
	}
}

func TestAReceiptCanBeFoundByWhatIsWrittenOnIt(t *testing.T) {
	// `note replay 3F-A2-9C` takes the fingerprint two people read to each other, so the reference
	// a person has in front of them is the one that works.
	p := &purse{Spent: []heldNote{{Amount: 10, Hash: "3fa29c00ff", Fingerprint: "3F-A2-9C"}}}
	if _, found := p.findSpent("3F-A2-9C"); !found {
		t.Error("a fingerprint should find the receipt")
	}
	if _, found := p.findSpent("3fa29c"); !found {
		t.Error("the head of the hash should find it too")
	}
	if _, found := p.findSpent("11-22-33"); found {
		t.Error("something else should not")
	}
}

func TestALoadIsSplitIntoNotesWorthCarrying(t *testing.T) {
	notes := denominations(100)
	var sum float64
	for _, amount := range notes {
		sum += amount
	}
	if sum != 100 {
		t.Fatalf("the denominations should add up to the load, got %v", sum)
	}
	if len(notes) < 3 {
		t.Fatalf("a purse of one note cannot pay for anything smaller: %v", notes)
	}
	// Something small enough to pay a small bill with.
	smallest := notes[len(notes)-1]
	if smallest > 5 {
		t.Fatalf("expected small change in the spread, got %v", notes)
	}
}

func TestEachWalletGetsItsOwnPurse(t *testing.T) {
	// Two wallets in one directory sharing a purse means each can spend the other's notes — and it
	// silently swallowed one side of a double-spend demonstration before this was fixed.
	alice := pursePath("/tmp/aicoin/alice.json")
	bob := pursePath("/tmp/aicoin/bob.json")
	if alice == bob {
		t.Fatalf("two wallets must not share a purse: %s", alice)
	}
	if !strings.Contains(alice, "alice") || !strings.Contains(bob, "bob") {
		t.Fatalf("the purse should be named after its wallet: %s / %s", alice, bob)
	}
	// The stats file has the same problem and the same fix.
	if statsPath("/tmp/aicoin/alice.json") == statsPath("/tmp/aicoin/bob.json") {
		t.Fatal("two wallets must not share a record either")
	}
}
