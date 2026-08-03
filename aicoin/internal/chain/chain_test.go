package chain

import (
	"crypto/ed25519"
	"testing"
)

func genKeyPair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey) {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey: %v", err)
	}
	return pub, priv
}

func TestBlockchainSealAndAppend(t *testing.T) {
	pub, priv := genKeyPair(t)
	bc := NewBlockchain(pub, priv)
	if bc.Len() != 1 {
		t.Fatalf("new chain should start with only genesis, got len %d", bc.Len())
	}

	tx := Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.05, Timestamp: "2026-08-03T12:00:00Z"}
	b, err := bc.SealAndAppend(tx)
	if err != nil {
		t.Fatalf("SealAndAppend: %v", err)
	}
	if b.Index != 1 {
		t.Fatalf("expected sealed block index 1, got %d", b.Index)
	}
	if b.Signature == "" {
		t.Fatal("expected sealed block to carry a non-empty signature")
	}
	if bc.Len() != 2 {
		t.Fatalf("expected chain len 2 after append, got %d", bc.Len())
	}
	if bc.Tip().Hash != b.Hash {
		t.Fatalf("tip hash %q does not match sealed block hash %q", bc.Tip().Hash, b.Hash)
	}
}

func TestBlockchainSealAndAppendRequiresSigner(t *testing.T) {
	pub, _ := genKeyPair(t)
	// No signer: this is what a follower's Blockchain looks like — it
	// must not be able to seal new blocks.
	bc := NewBlockchain(pub, nil)
	if _, err := bc.SealAndAppend(Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}); err == nil {
		t.Fatal("expected SealAndAppend to fail without a signing key (follower), got nil error")
	}
	if bc.Len() != 1 {
		t.Fatalf("chain should be unchanged after failed seal attempt, got len %d", bc.Len())
	}
}

func TestBlockchainAppendRejectsInvalidBlock(t *testing.T) {
	pub, _ := genKeyPair(t)
	bc := NewBlockchain(pub, nil)
	bad := Block{
		Index:        1,
		Timestamp:    "2026-08-03T12:00:00Z",
		PrevHash:     "not-the-real-tip-hash",
		Hash:         "0000",
		Signature:    "",
		Transactions: []Transaction{{Type: "event", UserID: "eve", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}},
	}
	if err := bc.Append(bad); err == nil {
		t.Fatal("expected Append to reject a block with wrong PrevHash, got nil error")
	}
	if bc.Len() != 1 {
		t.Fatalf("chain should be unchanged after rejected append, got len %d", bc.Len())
	}
}

// TestBlockchainReplaceIfLonger exercises the follower side of
// CONTRACT.md's chain-replacement rule: a chain with no signer (as a
// follower's Blockchain has) adopts a longer, fully valid chain — signed
// by the same trusted key — produced independently (as it would be by the
// real primary), but does not adopt a shorter-or-equal one.
func TestBlockchainReplaceIfLonger(t *testing.T) {
	pub, priv := genKeyPair(t)

	primary := NewBlockchain(pub, priv)
	for i := 0; i < 3; i++ {
		if _, err := primary.SealAndAppend(Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
			t.Fatalf("SealAndAppend on primary chain: %v", err)
		}
	}

	follower := NewBlockchain(pub, nil)
	replaced, err := follower.ReplaceIfLonger(primary.Blocks())
	if err != nil {
		t.Fatalf("ReplaceIfLonger: %v", err)
	}
	if !replaced {
		t.Fatal("expected longer valid chain to replace local chain")
	}
	if follower.Len() != primary.Len() {
		t.Fatalf("expected follower.Len() == primary.Len() after replace, got %d vs %d", follower.Len(), primary.Len())
	}

	// A shorter-or-equal candidate must not replace.
	shorter := NewBlockchain(pub, nil)
	replaced, err = follower.ReplaceIfLonger(shorter.Blocks())
	if err != nil {
		t.Fatalf("ReplaceIfLonger(shorter): %v", err)
	}
	if replaced {
		t.Fatal("expected shorter chain NOT to replace local chain")
	}
}
