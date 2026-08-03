package chain

import (
	"log"
	"sync"
	"time"
)

// Blockchain is a thread-safe, in-memory chain of blocks, starting from the
// canonical genesis block (or, when a ChainStore is configured and has a
// prior chain saved, from that chain instead — see NewBlockchainWithStore).
type Blockchain struct {
	mu         sync.RWMutex
	blocks     []Block
	difficulty int
	store      ChainStore
}

// NewBlockchain creates a new chain containing only the genesis block, with
// no persistence (pure in-memory, per CONTRACT.md's default when -redis is
// unset).
func NewBlockchain(difficulty int) *Blockchain {
	bc, _ := NewBlockchainWithStore(difficulty, nil)
	return bc
}

// NewBlockchainWithStore creates a chain backed by store. If store is nil,
// this is identical to NewBlockchain (pure in-memory). Otherwise, per
// CONTRACT.md's "Persistence" section: on startup it calls store.Load(); if
// that returns a non-empty chain, it is used as the starting chain instead
// of genesis-only. After every successful append (MineAndAppend, Append, or
// ReplaceIfLonger), the full current chain is written back via store.Save.
func NewBlockchainWithStore(difficulty int, store ChainStore) (*Blockchain, error) {
	bc := &Blockchain{
		blocks:     []Block{Genesis()},
		difficulty: difficulty,
		store:      store,
	}
	if store != nil {
		loaded, err := store.Load()
		if err != nil {
			return nil, err
		}
		if len(loaded) > 0 {
			bc.blocks = loaded
		}
	}
	return bc, nil
}

// persistLocked saves the current chain to the configured store, if any.
// Must be called with bc.mu already held (for writing). Save errors are
// logged, not returned/propagated: persistence is best-effort and must
// never cause an otherwise-valid local chain mutation to fail or roll back.
func (bc *Blockchain) persistLocked() {
	if bc.store == nil {
		return
	}
	if err := bc.store.Save(bc.blocks); err != nil {
		log.Printf("chain: persisting chain to store failed: %v", err)
	}
}

// Difficulty returns the configured PoW difficulty.
func (bc *Blockchain) Difficulty() int {
	return bc.difficulty
}

// Tip returns the current last block.
func (bc *Blockchain) Tip() Block {
	bc.mu.RLock()
	defer bc.mu.RUnlock()
	return bc.blocks[len(bc.blocks)-1]
}

// Len returns the number of blocks in the chain (including genesis).
func (bc *Blockchain) Len() int {
	bc.mu.RLock()
	defer bc.mu.RUnlock()
	return len(bc.blocks)
}

// Blocks returns a defensive copy of the full chain.
func (bc *Blockchain) Blocks() []Block {
	bc.mu.RLock()
	defer bc.mu.RUnlock()
	out := make([]Block, len(bc.blocks))
	copy(out, bc.blocks)
	return out
}

// MineAndAppend builds a new block on top of the current tip carrying tx as
// its sole transaction, mines it (proof-of-work against the configured
// difficulty), appends it to the chain, and returns it.
func (bc *Blockchain) MineAndAppend(tx Transaction) (Block, error) {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	tip := bc.blocks[len(bc.blocks)-1]
	timestamp := time.Now().UTC().Format(time.RFC3339)
	txs := []Transaction{tx}

	hash, nonce, err := Mine(tip.Index+1, tip.Hash, timestamp, txs, bc.difficulty)
	if err != nil {
		return Block{}, err
	}

	newBlock := Block{
		Index:        tip.Index + 1,
		Timestamp:    timestamp,
		PrevHash:     tip.Hash,
		Hash:         hash,
		Nonce:        nonce,
		Transactions: txs,
	}
	bc.blocks = append(bc.blocks, newBlock)
	bc.persistLocked()
	return newBlock, nil
}

// Append validates candidate against the current tip and, if valid, appends
// it to the chain.
func (bc *Blockchain) Append(candidate Block) error {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	tip := bc.blocks[len(bc.blocks)-1]
	if err := ValidateBlock(tip, candidate, bc.difficulty); err != nil {
		return err
	}
	bc.blocks = append(bc.blocks, candidate)
	bc.persistLocked()
	return nil
}

// ReplaceIfLonger validates candidate as a full chain and, if it is both
// valid and strictly longer than the current local chain, replaces the
// local chain with it (longest-valid-chain rule). It reports whether the
// replacement happened.
func (bc *Blockchain) ReplaceIfLonger(candidate []Block) (bool, error) {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	if len(candidate) <= len(bc.blocks) {
		return false, nil
	}
	if err := ValidateChain(candidate, bc.difficulty); err != nil {
		return false, err
	}
	newBlocks := make([]Block, len(candidate))
	copy(newBlocks, candidate)
	bc.blocks = newBlocks
	bc.persistLocked()
	return true, nil
}
