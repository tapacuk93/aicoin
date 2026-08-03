package chain

// PollAndAdopt implements a follower's single poll-tick replication step,
// per CONTRACT.md's "Roles & signing" and "Persistence" sections: re-read
// the shared chain from store and, if it is both longer than bc's current
// local chain and every block in it validates against bc's trustedPubKey,
// adopt it wholesale (the same ValidateChain/ReplaceIfLonger logic used
// everywhere else in this package). It is meant to be called on a timer
// (every -follower-poll-interval) by a follower node; a real caller loops
// this, but it is exposed as a single testable call so tests can invoke one
// tick directly instead of waiting on a real timer.
//
// It is a no-op (returns false, nil) in two cases:
//   - store is nil (no ChainStore configured — nothing to poll).
//   - bc is primary-shaped (has a signing key). Per CONTRACT.md, "A primary
//     never re-reads/replaces its own chain from DynamoDB after startup — it
//     is authoritative by definition; DynamoDB is where it writes, not
//     where it takes direction from." A real primary should simply never
//     run the
//     polling goroutine in the first place (see cmd/aicoind/main.go), but
//     this check makes that guarantee hold structurally even if it were
//     ever called on a primary-shaped Blockchain by mistake.
func PollAndAdopt(bc *Blockchain, store ChainStore) (bool, error) {
	if store == nil {
		return false, nil
	}

	bc.mu.RLock()
	isPrimary := bc.signer != nil
	bc.mu.RUnlock()
	if isPrimary {
		return false, nil
	}

	blocks, err := store.Load()
	if err != nil {
		return false, err
	}
	if len(blocks) == 0 {
		return false, nil
	}
	// store.Load() only ever returns blocks after genesis (genesis itself is
	// never written to the store — see NewBlockchainWithStore); prepend it
	// to form the full chain ValidateChain/ReplaceIfLonger expect.
	fullChain := append([]Block{Genesis()}, blocks...)
	return bc.ReplaceIfLonger(fullChain)
}
