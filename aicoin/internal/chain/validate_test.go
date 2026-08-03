package chain

import (
	"crypto/ed25519"
	"strings"
	"testing"
)

// sealedBlock builds and signs a valid successor block to prev, carrying
// tx, using priv as the signing key.
func sealedBlock(t *testing.T, prev Block, tx Transaction, priv ed25519.PrivateKey) Block {
	t.Helper()
	txs := []Transaction{tx}
	timestamp := "2026-08-03T12:00:00Z"
	hash, signature, err := Seal(prev.Index+1, prev.Hash, timestamp, txs, priv)
	if err != nil {
		t.Fatalf("Seal: %v", err)
	}
	return Block{
		Index:        prev.Index + 1,
		Timestamp:    timestamp,
		PrevHash:     prev.Hash,
		Hash:         hash,
		Signature:    signature,
		Transactions: txs,
	}
}

func TestValidateBlockAcceptsValidBlock(t *testing.T) {
	pub, priv := genKeyPair(t)
	prev := Genesis()
	b := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, priv)

	if err := ValidateBlock(b, prev, pub); err != nil {
		t.Fatalf("expected valid block to pass validation, got: %v", err)
	}
}

func TestValidateBlockRejectsWrongPrevHash(t *testing.T) {
	pub, priv := genKeyPair(t)
	prev := Genesis()
	b := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, priv)

	// Corrupt PrevHash so it no longer links to prev, without touching the
	// stored Hash/Signature.
	b.PrevHash = "deadbeef00000000000000000000000000000000000000000000000000dead"

	if err := ValidateBlock(b, prev, pub); err == nil {
		t.Fatal("expected error for block with wrong PrevHash, got nil")
	}
}

// TestValidateBlockRejectsTamperedHash proves that a block whose stored
// Hash no longer matches ComputeHash of its own fields is rejected, even
// if its Signature happens to still be present (it necessarily won't
// verify against the tampered digest either, but the hash-mismatch check
// must catch it regardless).
func TestValidateBlockRejectsTamperedHash(t *testing.T) {
	pub, priv := genKeyPair(t)
	prev := Genesis()
	b := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, priv)

	b.Hash = "0000000000000000000000000000000000000000000000000000000000dead"

	if err := ValidateBlock(b, prev, pub); err == nil {
		t.Fatal("expected error for block with a tampered Hash, got nil")
	}
}

// TestValidateBlockRejectsBadSignature proves a block whose Signature has
// been corrupted (but is still valid hex of the right length) fails
// ed25519 verification.
func TestValidateBlockRejectsBadSignature(t *testing.T) {
	pub, priv := genKeyPair(t)
	prev := Genesis()
	b := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, priv)

	b.Signature = strings.Repeat("00", ed25519.SignatureSize)

	if err := ValidateBlock(b, prev, pub); err == nil {
		t.Fatal("expected error for block with a bad/corrupted signature, got nil")
	}
}

// TestValidateBlockRejectsSignatureFromWrongKey proves a block signed by
// some other Ed25519 key entirely — internally self-consistent, just not
// by the trusted signer — is rejected.
func TestValidateBlockRejectsSignatureFromWrongKey(t *testing.T) {
	pub, _ := genKeyPair(t)
	_, otherPriv := genKeyPair(t)
	prev := Genesis()
	b := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, otherPriv)

	if err := ValidateBlock(b, prev, pub); err == nil {
		t.Fatal("expected error for block signed by the wrong key, got nil")
	}
}

// TestValidateBlockGenesisAlwaysValidRegardlessOfSignature proves index 0
// is always special-cased as valid against the well-known genesis
// constant, with no signature check at all — even a nonsense Signature
// value must not cause rejection.
func TestValidateBlockGenesisAlwaysValidRegardlessOfSignature(t *testing.T) {
	pub, _ := genKeyPair(t)
	g := Genesis()
	g.Signature = "not-even-valid-hex-but-should-not-matter"

	if err := ValidateBlock(g, Block{}, pub); err != nil {
		t.Fatalf("expected genesis to always validate regardless of Signature, got: %v", err)
	}
}

func TestValidateChainAcceptsGenesisOnly(t *testing.T) {
	pub, _ := genKeyPair(t)
	if err := ValidateChain([]Block{Genesis()}, pub); err != nil {
		t.Fatalf("expected genesis-only chain to validate, got: %v", err)
	}
}

func TestValidateChainRejectsBadGenesis(t *testing.T) {
	pub, _ := genKeyPair(t)
	fake := Genesis()
	fake.Hash = "0000000000000000000000000000000000000000000000000000000000dead"
	if err := ValidateChain([]Block{fake}, pub); err == nil {
		t.Fatal("expected error for chain with tampered genesis, got nil")
	}
}

func TestValidateChainRejectsBrokenLink(t *testing.T) {
	pub, priv := genKeyPair(t)
	prev := Genesis()
	b1 := sealedBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}, priv)
	b2 := sealedBlock(t, b1, Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:01:00Z"}, priv)

	// Break the link between b1 and b2.
	b2.PrevHash = "0000000000000000000000000000000000000000000000000000000000dead"

	if err := ValidateChain([]Block{prev, b1, b2}, pub); err == nil {
		t.Fatal("expected error for chain with a broken PrevHash link, got nil")
	}
}
