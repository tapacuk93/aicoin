// Package store provides ChainStore implementations for aicoin's optional
// chain persistence (CONTRACT.md's "Persistence" section): a DynamoDB-backed
// implementation (the real deal, and the only file in this package that
// imports an AWS SDK client) and an in-memory fake (for unit tests,
// including tests run in sandboxes/CI with no reachable AWS credentials).
package store

import (
	"sync"

	"aicoin/internal/chain"
)

// InMemory is an in-memory chain.ChainStore fake, safe for concurrent use.
// It exists so callers (and this package's own tests) can exercise the
// load/append contract deterministically without a live DynamoDB table.
//
// The zero value is a valid, empty store: Load returns (nil, nil) until the
// first AppendBlock.
type InMemory struct {
	mu     sync.Mutex
	blocks []chain.Block
	saved  bool
}

// Load returns a defensive copy of every block AppendBlock-ed so far, in the
// order they were appended, or (nil, nil) if AppendBlock has never been
// called (i.e. "nothing persisted yet").
func (m *InMemory) Load() ([]chain.Block, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.saved {
		return nil, nil
	}
	out := make([]chain.Block, len(m.blocks))
	copy(out, m.blocks)
	return out, nil
}

// AppendBlock stores a defensive copy of b (including its Transactions
// slice), appending it after whatever was previously stored.
func (m *InMemory) AppendBlock(b chain.Block) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	cp := b
	cp.Transactions = make([]chain.Transaction, len(b.Transactions))
	copy(cp.Transactions, b.Transactions)
	m.blocks = append(m.blocks, cp)
	m.saved = true
	return nil
}
