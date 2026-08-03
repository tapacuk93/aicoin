package chain

import "testing"

func TestBlockchainMineAndAppend(t *testing.T) {
	bc := NewBlockchain(1)
	if bc.Len() != 1 {
		t.Fatalf("new chain should start with only genesis, got len %d", bc.Len())
	}

	tx := Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.05, Timestamp: "2026-08-03T12:00:00Z"}
	b, err := bc.MineAndAppend(tx)
	if err != nil {
		t.Fatalf("MineAndAppend: %v", err)
	}
	if b.Index != 1 {
		t.Fatalf("expected mined block index 1, got %d", b.Index)
	}
	if bc.Len() != 2 {
		t.Fatalf("expected chain len 2 after append, got %d", bc.Len())
	}
	if bc.Tip().Hash != b.Hash {
		t.Fatalf("tip hash %q does not match mined block hash %q", bc.Tip().Hash, b.Hash)
	}
}

func TestBlockchainAppendRejectsInvalidBlock(t *testing.T) {
	bc := NewBlockchain(1)
	bad := Block{
		Index:        1,
		Timestamp:    "2026-08-03T12:00:00Z",
		PrevHash:     "not-the-real-tip-hash",
		Hash:         "0000",
		Nonce:        0,
		Transactions: []Transaction{{Type: "event", UserID: "eve", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}},
	}
	if err := bc.Append(bad); err == nil {
		t.Fatal("expected Append to reject a block with wrong PrevHash, got nil error")
	}
	if bc.Len() != 1 {
		t.Fatalf("chain should be unchanged after rejected append, got len %d", bc.Len())
	}
}

func TestBlockchainReplaceIfLonger(t *testing.T) {
	bc := NewBlockchain(1)
	_, err := bc.MineAndAppend(Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"})
	if err != nil {
		t.Fatalf("MineAndAppend: %v", err)
	}

	// Build an independent, longer, fully valid candidate chain from
	// genesis.
	other := NewBlockchain(1)
	for i := 0; i < 3; i++ {
		if _, err := other.MineAndAppend(Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
			t.Fatalf("MineAndAppend on other chain: %v", err)
		}
	}

	replaced, err := bc.ReplaceIfLonger(other.Blocks())
	if err != nil {
		t.Fatalf("ReplaceIfLonger: %v", err)
	}
	if !replaced {
		t.Fatal("expected longer valid chain to replace local chain")
	}
	if bc.Len() != other.Len() {
		t.Fatalf("expected bc.Len() == other.Len() after replace, got %d vs %d", bc.Len(), other.Len())
	}

	// A shorter-or-equal candidate must not replace.
	shorter := NewBlockchain(1)
	replaced, err = bc.ReplaceIfLonger(shorter.Blocks())
	if err != nil {
		t.Fatalf("ReplaceIfLonger(shorter): %v", err)
	}
	if replaced {
		t.Fatal("expected shorter chain NOT to replace local chain")
	}
}
