// Package store provides ChainStore implementations for aicoin's optional
// chain persistence (CONTRACT.md's "Persistence" section): a Redis-backed
// implementation (the real deal, and the only file in this package that
// imports a Redis client) and an in-memory fake (for unit tests, including
// tests run in sandboxes with no reachable Redis).
package store

import (
	"sync"

	"aicoin/internal/chain"
)

// InMemory is an in-memory chain.ChainStore fake, safe for concurrent use.
// It exists so callers (and this package's own tests) can exercise the
// load/save contract deterministically without a live Redis server.
//
// The zero value is a valid, empty store: Load returns (nil, nil) until
// the first Save.
type InMemory struct {
	mu     sync.Mutex
	blocks []chain.Block
	saved  bool
}

// Load returns a defensive copy of the most recently Saved chain, or
// (nil, nil) if Save has never been called (i.e. "nothing persisted yet").
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

// Save stores a defensive copy of blocks, replacing whatever was
// previously saved.
func (m *InMemory) Save(blocks []chain.Block) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]chain.Block, len(blocks))
	copy(out, blocks)
	m.blocks = out
	m.saved = true
	return nil
}
