package chain

import "testing"

// TestPollAndAdoptFollowerAdoptsLongerValidChain proves the follower half
// of CONTRACT.md's replication rule: given a store that already holds a
// longer, validly-signed chain (as it would after a primary writes it via
// its own SealAndAppend->persistLocked path), a single PollAndAdopt call on
// a follower-shaped Blockchain (no signer) against that same store adopts
// it wholesale — exactly what a real follower's poll-tick goroutine would
// do, but invoked directly instead of waiting on a real timer.
func TestPollAndAdoptFollowerAdoptsLongerValidChain(t *testing.T) {
	pub, priv := genKeyPair(t)
	store := &fakeStore{}

	// A primary-shaped Blockchain plays the role of the real primary: it
	// writes a longer signed chain directly into the shared store, the
	// same way a primary's persistLocked does after every SealAndAppend.
	primary, err := NewBlockchainWithStore(pub, priv, store)
	if err != nil {
		t.Fatalf("NewBlockchainWithStore(primary): %v", err)
	}
	for i := 0; i < 3; i++ {
		if _, err := primary.SealAndAppend(Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
			t.Fatalf("SealAndAppend on primary: %v", err)
		}
	}

	follower := NewBlockchain(pub, nil)
	if follower.Len() != 1 {
		t.Fatalf("follower should start at genesis-only, got len %d", follower.Len())
	}

	replaced, err := PollAndAdopt(follower, store)
	if err != nil {
		t.Fatalf("PollAndAdopt: %v", err)
	}
	if !replaced {
		t.Fatal("expected PollAndAdopt to report a replacement (store held a longer valid chain)")
	}
	if follower.Len() != primary.Len() {
		t.Fatalf("follower.Len() = %d, want %d after PollAndAdopt", follower.Len(), primary.Len())
	}
	if follower.Tip().Hash != primary.Tip().Hash {
		t.Fatalf("follower.Tip().Hash = %q, want %q after PollAndAdopt", follower.Tip().Hash, primary.Tip().Hash)
	}

	// A second poll tick, with nothing new written to the store, must be a
	// harmless no-op (not shorter-or-equal-triggered error, just false).
	replaced, err = PollAndAdopt(follower, store)
	if err != nil {
		t.Fatalf("PollAndAdopt (second tick, no change): %v", err)
	}
	if replaced {
		t.Fatal("expected second PollAndAdopt tick with no store change to report no replacement")
	}
}

// TestPollAndAdoptNoStoreIsNoOp proves PollAndAdopt is a harmless no-op
// when no ChainStore is configured (e.g. a follower somehow constructed
// without one — main.go's -dynamodb-table-required-for-follower check
// prevents this in practice, but the function itself must not panic or
// misbehave).
func TestPollAndAdoptNoStoreIsNoOp(t *testing.T) {
	pub, _ := genKeyPair(t)
	follower := NewBlockchain(pub, nil)

	replaced, err := PollAndAdopt(follower, nil)
	if err != nil {
		t.Fatalf("PollAndAdopt with nil store: %v", err)
	}
	if replaced {
		t.Fatal("expected no replacement with nil store")
	}
	if follower.Len() != 1 {
		t.Fatalf("follower chain should be unchanged, got len %d", follower.Len())
	}
}

// TestPollAndAdoptPrimaryNeverReplaces proves the other half of
// CONTRACT.md's "Roles & signing" rule: even if the shared store holds a
// longer, validly-signed chain, PollAndAdopt is a structural no-op on a
// primary-shaped Blockchain (one with a signing key) — a primary is
// authoritative by construction and must never adopt a chain from the
// store it itself writes to. In the real binary this case never arises
// because cmd/aicoind/main.go only ever starts the poll-tick goroutine for
// -role=follower; this test proves the belt-and-suspenders guarantee holds
// even if PollAndAdopt were ever called directly against a primary.
func TestPollAndAdoptPrimaryNeverReplaces(t *testing.T) {
	pub, priv := genKeyPair(t)
	store := &fakeStore{}

	// Some other primary-shaped chain (signed by the very same trusted
	// key) writes a longer chain into the shared store.
	other, err := NewBlockchainWithStore(pub, priv, store)
	if err != nil {
		t.Fatalf("NewBlockchainWithStore(other): %v", err)
	}
	for i := 0; i < 5; i++ {
		if _, err := other.SealAndAppend(Transaction{Type: "event", UserID: "bob", Provider: "anthropic", CostUSD: 0.02, Timestamp: "2026-08-03T12:00:00Z"}); err != nil {
			t.Fatalf("SealAndAppend on other: %v", err)
		}
	}

	primary := NewBlockchain(pub, priv)
	if primary.Len() != 1 {
		t.Fatalf("primary should start at genesis-only, got len %d", primary.Len())
	}

	replaced, err := PollAndAdopt(primary, store)
	if err != nil {
		t.Fatalf("PollAndAdopt(primary): %v", err)
	}
	if replaced {
		t.Fatal("expected PollAndAdopt to report no replacement on a primary-shaped Blockchain")
	}
	if primary.Len() != 1 {
		t.Fatalf("primary chain len = %d, want unchanged 1 (genesis only); a primary must never adopt a chain via PollAndAdopt", primary.Len())
	}
}

// fakeStore is a minimal in-package ChainStore fake (internal/store's
// InMemory would create an import cycle from this package, so this test
// file defines its own trivial equivalent).
type fakeStore struct {
	blocks []Block
	saved  bool
}

func (f *fakeStore) Load() ([]Block, error) {
	if !f.saved {
		return nil, nil
	}
	out := make([]Block, len(f.blocks))
	copy(out, f.blocks)
	return out, nil
}

func (f *fakeStore) AppendBlock(b Block) error {
	f.blocks = append(f.blocks, b)
	f.saved = true
	return nil
}
