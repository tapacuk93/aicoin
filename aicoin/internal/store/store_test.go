package store

import (
	"testing"

	"aicoin/internal/chain"
)

// TestInMemoryEmptyStoreMeansGenesis proves the chain.ChainStore contract's
// "nothing persisted yet" case: an InMemory store that has never been Saved
// to reports Load() = (nil, nil), and a Blockchain built against it falls
// back to genesis-only, per CONTRACT.md's "Persistence" section.
func TestInMemoryEmptyStoreMeansGenesis(t *testing.T) {
	fake := &InMemory{}

	blocks, err := fake.Load()
	if err != nil {
		t.Fatalf("Load on never-saved store: %v", err)
	}
	if blocks != nil {
		t.Fatalf("Load on never-saved store = %v, want nil", blocks)
	}

	bc, err := chain.NewBlockchainWithStore(1, fake)
	if err != nil {
		t.Fatalf("NewBlockchainWithStore: %v", err)
	}
	if bc.Len() != 1 {
		t.Fatalf("chain backed by empty store should start with just genesis, got len %d", bc.Len())
	}
	if bc.Tip().Hash != chain.Genesis().Hash {
		t.Fatalf("chain backed by empty store should start at genesis, got tip hash %q", bc.Tip().Hash)
	}
}

// TestInMemoryLoadThenSaveRoundTrip proves Save followed by Load returns
// exactly what was saved, and that mutating the slice passed to Save (or
// the slice returned by Load) afterwards does not corrupt the store's
// internal state (defensive copies both ways).
func TestInMemoryLoadThenSaveRoundTrip(t *testing.T) {
	fake := &InMemory{}

	tx := chain.Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}
	original := []chain.Block{
		chain.Genesis(),
		{Index: 1, Timestamp: "2026-08-03T12:00:00Z", PrevHash: chain.Genesis().Hash, Hash: "deadbeef", Nonce: 42, Transactions: []chain.Transaction{tx}},
	}

	if err := fake.Save(original); err != nil {
		t.Fatalf("Save: %v", err)
	}

	// Mutate the caller's slice after Save returns; must not affect what
	// was stored.
	original[1].Hash = "corrupted"

	got, err := fake.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("Load returned %d blocks, want 2", len(got))
	}
	if got[1].Hash != "deadbeef" {
		t.Fatalf("Load()[1].Hash = %q, want %q (unaffected by later mutation of the saved slice)", got[1].Hash, "deadbeef")
	}
	if got[1].Transactions[0].UserID != "alice" {
		t.Fatalf("Load()[1].Transactions[0].UserID = %q, want alice", got[1].Transactions[0].UserID)
	}

	// Mutate the slice returned by Load; must not affect the store either.
	got[1].Hash = "also-corrupted"
	got2, err := fake.Load()
	if err != nil {
		t.Fatalf("second Load: %v", err)
	}
	if got2[1].Hash != "deadbeef" {
		t.Fatalf("second Load()[1].Hash = %q, want %q (Load must return a defensive copy)", got2[1].Hash, "deadbeef")
	}
}

// TestBlockchainWithStorePersistsAppendedBlocks proves the integration end
// to end: a Blockchain backed by an InMemory store persists every mined
// block, and a second Blockchain constructed against the same store
// afterwards picks up right where the first left off (simulating a
// restart), per CONTRACT.md's "on startup, GET ... if present, load it"
// behavior.
func TestBlockchainWithStorePersistsAppendedBlocks(t *testing.T) {
	fake := &InMemory{}

	bc, err := chain.NewBlockchainWithStore(1, fake)
	if err != nil {
		t.Fatalf("NewBlockchainWithStore: %v", err)
	}
	if _, err := bc.MineAndAppend(chain.Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.01, Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
		t.Fatalf("MineAndAppend: %v", err)
	}
	if _, err := bc.MineAndAppend(chain.Transaction{Type: "free_claim", UserID: "alice", Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
		t.Fatalf("MineAndAppend: %v", err)
	}

	// Simulate a restart: a brand new Blockchain against the same store
	// should load the 3-block chain (genesis + 2), not start over at
	// genesis-only.
	restarted, err := chain.NewBlockchainWithStore(1, fake)
	if err != nil {
		t.Fatalf("NewBlockchainWithStore (restart): %v", err)
	}
	if restarted.Len() != bc.Len() {
		t.Fatalf("restarted chain len = %d, want %d (loaded from store)", restarted.Len(), bc.Len())
	}
	if restarted.Tip().Hash != bc.Tip().Hash {
		t.Fatalf("restarted chain tip hash = %q, want %q", restarted.Tip().Hash, bc.Tip().Hash)
	}
}
