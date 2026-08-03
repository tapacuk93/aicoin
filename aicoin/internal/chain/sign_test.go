package chain

import (
	"crypto/ed25519"
	"encoding/hex"
	"strings"
	"testing"
)

// TestSealProducesVerifiableSignature proves the core of the new signing
// scheme (CONTRACT.md's "Roles & signing"/"Chain model" sections): Seal
// computes a Hash matching plain ComputeHash of the same fields, and a
// Signature that ed25519.Verify accepts over the raw digest (not the hex
// Hash string) using the corresponding public key.
func TestSealProducesVerifiableSignature(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}

	txs := []Transaction{{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.001, Timestamp: "2026-08-03T12:00:00Z"}}
	const (
		index     = 1
		timestamp = "2026-08-03T12:00:01Z"
	)
	prevHash := Genesis().Hash

	hash, signature, err := Seal(index, prevHash, timestamp, txs, priv)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	if hash == "" {
		t.Fatal("Seal returned an empty hash")
	}
	if signature == "" {
		t.Fatal("Seal returned an empty signature")
	}

	wantHash, err := ComputeHash(index, prevHash, timestamp, txs)
	if err != nil {
		t.Fatalf("ComputeHash: %v", err)
	}
	if hash != wantHash {
		t.Fatalf("Seal hash = %q, want %q (must match plain ComputeHash)", hash, wantHash)
	}

	d, err := digestBytes(index, prevHash, timestamp, txs)
	if err != nil {
		t.Fatalf("digestBytes: %v", err)
	}
	sigBytes, err := hex.DecodeString(signature)
	if err != nil {
		t.Fatalf("decoding signature hex: %v", err)
	}
	if !ed25519.Verify(pub, d[:], sigBytes) {
		t.Fatal("ed25519.Verify rejected the signature produced by Seal, using the matching public key")
	}
}

// TestSealSignatureRejectedByWrongKey proves a signature produced for one
// keypair does not verify against a different (unrelated) public key —
// i.e. Seal's signature is actually tied to the signing key, not just
// always "true".
func TestSealSignatureRejectedByWrongKey(t *testing.T) {
	_, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}
	otherPub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}

	txs := []Transaction{{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.001, Timestamp: "2026-08-03T12:00:00Z"}}
	_, signature, err := Seal(1, Genesis().Hash, "2026-08-03T12:00:01Z", txs, priv)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}

	d, err := digestBytes(1, Genesis().Hash, "2026-08-03T12:00:01Z", txs)
	if err != nil {
		t.Fatalf("digestBytes: %v", err)
	}
	sigBytes, err := hex.DecodeString(signature)
	if err != nil {
		t.Fatalf("decoding signature hex: %v", err)
	}
	if ed25519.Verify(otherPub, d[:], sigBytes) {
		t.Fatal("ed25519.Verify unexpectedly accepted a signature against an unrelated public key")
	}
}

func TestGenesisIsDeterministic(t *testing.T) {
	g1 := Genesis()
	g2 := Genesis()
	if g1.Hash != g2.Hash {
		t.Fatalf("genesis hash is not deterministic: %q vs %q", g1.Hash, g2.Hash)
	}
	if g1.Index != 0 {
		t.Fatalf("genesis index = %d, want 0", g1.Index)
	}
	if len(g1.PrevHash) != 64 || strings.Trim(g1.PrevHash, "0") != "" {
		t.Fatalf("genesis PrevHash = %q, want 64 zero chars", g1.PrevHash)
	}
	if len(g1.Transactions) != 0 {
		t.Fatalf("genesis should have no transactions, got %d", len(g1.Transactions))
	}
	if g1.Signature != "" {
		t.Fatalf("genesis Signature = %q, want empty (genesis is never signed)", g1.Signature)
	}
}
