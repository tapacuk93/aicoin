package chain

import "testing"

const testDifficulty = 2

func mineValidBlock(t *testing.T, prev Block, tx Transaction) Block {
	t.Helper()
	txs := []Transaction{tx}
	timestamp := "2026-08-03T12:00:00Z"
	hash, nonce, err := Mine(prev.Index+1, prev.Hash, timestamp, txs, testDifficulty)
	if err != nil {
		t.Fatalf("Mine: %v", err)
	}
	return Block{
		Index:        prev.Index + 1,
		Timestamp:    timestamp,
		PrevHash:     prev.Hash,
		Hash:         hash,
		Nonce:        nonce,
		Transactions: txs,
	}
}

func TestValidateBlockAcceptsValidBlock(t *testing.T) {
	prev := Genesis()
	b := mineValidBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"})

	if err := ValidateBlock(prev, b, testDifficulty); err != nil {
		t.Fatalf("expected valid block to pass validation, got: %v", err)
	}
}

func TestValidateBlockRejectsWrongPrevHash(t *testing.T) {
	prev := Genesis()
	b := mineValidBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"})

	// Corrupt PrevHash so it no longer links to prev, without touching the
	// stored Hash — this must be rejected even though the PoW itself
	// (relative to the tampered fields) might still look internally
	// consistent or not, depending on the tamper.
	b.PrevHash = "deadbeef00000000000000000000000000000000000000000000000000dead"

	if err := ValidateBlock(prev, b, testDifficulty); err == nil {
		t.Fatal("expected error for block with wrong PrevHash, got nil")
	}
}

func TestValidateBlockRejectsInvalidPoW(t *testing.T) {
	prev := Genesis()
	b := mineValidBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"})

	// Tamper with the nonce so the stored Hash no longer matches what
	// ComputeHash derives from the block's fields (simulating a forged/
	// invalid-PoW block).
	b.Nonce = b.Nonce + 1

	if err := ValidateBlock(prev, b, testDifficulty); err == nil {
		t.Fatal("expected error for block with invalid PoW (hash/nonce mismatch), got nil")
	}
}

func TestValidateBlockRejectsHashNotMeetingDifficulty(t *testing.T) {
	prev := Genesis()
	txs := []Transaction{{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:00:00Z"}}
	timestamp := "2026-08-03T12:00:00Z"

	// Use nonce 0 directly (not mined) — vanishingly unlikely to
	// coincidentally satisfy a difficulty-4 requirement.
	hash, err := ComputeHash(prev.Index+1, prev.Hash, timestamp, txs, 0)
	if err != nil {
		t.Fatalf("ComputeHash: %v", err)
	}
	b := Block{Index: prev.Index + 1, Timestamp: timestamp, PrevHash: prev.Hash, Hash: hash, Nonce: 0, Transactions: txs}

	if err := ValidateBlock(prev, b, 4); err == nil {
		t.Fatal("expected error for block whose hash doesn't meet the configured difficulty, got nil")
	}
}

func TestValidateChainAcceptsGenesisOnly(t *testing.T) {
	if err := ValidateChain([]Block{Genesis()}, testDifficulty); err != nil {
		t.Fatalf("expected genesis-only chain to validate, got: %v", err)
	}
}

func TestValidateChainRejectsBadGenesis(t *testing.T) {
	fake := Genesis()
	fake.Hash = "0000000000000000000000000000000000000000000000000000000000dead"
	if err := ValidateChain([]Block{fake}, testDifficulty); err == nil {
		t.Fatal("expected error for chain with tampered genesis, got nil")
	}
}

func TestValidateChainRejectsBrokenLink(t *testing.T) {
	prev := Genesis()
	b1 := mineValidBlock(t, prev, Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"})
	b2 := mineValidBlock(t, b1, Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:01:00Z"})

	// Break the link between b1 and b2.
	b2.PrevHash = "0000000000000000000000000000000000000000000000000000000000dead"

	if err := ValidateChain([]Block{prev, b1, b2}, testDifficulty); err == nil {
		t.Fatal("expected error for chain with a broken PrevHash link, got nil")
	}
}
