package chain

import (
	"crypto/ed25519"
	"errors"
	"log"
	"sync"
	"time"
)

// Blockchain is a thread-safe, in-memory chain of blocks, starting from the
// canonical genesis block (or, when a ChainStore is configured and has a
// prior chain saved, from that chain instead — see NewBlockchainWithStore).
//
// Every block (other than genesis) is validated against trustedPubKey, per
// CONTRACT.md's "Roles & signing" section: on a primary, trustedPubKey is
// derived from signer (its own Ed25519 keypair); on a follower, signer is
// nil and trustedPubKey is the configured -trusted-pubkey. Only a
// Blockchain with a non-nil signer can SealAndAppend new blocks — that is
// what makes a node a primary rather than a read-only follower.
type Blockchain struct {
	mu            sync.RWMutex
	blocks        []Block
	trustedPubKey ed25519.PublicKey
	signer        ed25519.PrivateKey
	store         ChainStore
}

// NewBlockchain creates a new chain containing only the genesis block, with
// no persistence (pure in-memory, per CONTRACT.md's default when -redis is
// unset). signer is the node's own Ed25519 private key on a primary (used
// by SealAndAppend), or nil on a follower (which can only Append/
// ReplaceIfLonger blocks validated against trustedPubKey).
func NewBlockchain(trustedPubKey ed25519.PublicKey, signer ed25519.PrivateKey) *Blockchain {
	bc, _ := NewBlockchainWithStore(trustedPubKey, signer, nil)
	return bc
}

// NewBlockchainWithStore creates a chain backed by store. If store is nil,
// this is identical to NewBlockchain (pure in-memory). Otherwise, per
// CONTRACT.md's "Persistence" section: on startup it calls store.Load(); if
// that returns a non-empty chain, it is used as the starting chain instead
// of genesis-only. After every successful append (SealAndAppend, Append, or
// ReplaceIfLonger), the full current chain is written back via store.Save.
func NewBlockchainWithStore(trustedPubKey ed25519.PublicKey, signer ed25519.PrivateKey, store ChainStore) (*Blockchain, error) {
	bc := &Blockchain{
		blocks:        []Block{Genesis()},
		trustedPubKey: trustedPubKey,
		signer:        signer,
		store:         store,
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

// TrustedPubKey returns the Ed25519 public key every non-genesis block is
// validated against (the primary's own public key on a primary; the
// configured -trusted-pubkey on a follower).
func (bc *Blockchain) TrustedPubKey() ed25519.PublicKey {
	return bc.trustedPubKey
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

// SealAndAppend builds a new block on top of the current tip carrying tx as
// its sole transaction, computes its Hash and Ed25519 Signature (Seal —
// one-shot, no search/delay; this replaces the old PoW mining step
// one-for-one), appends it to the chain, and returns it. It fails if this
// Blockchain has no signing key configured (i.e. this node is a follower,
// not a primary).
func (bc *Blockchain) SealAndAppend(tx Transaction) (Block, error) {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	if bc.signer == nil {
		return Block{}, errors.New("chain: cannot seal a block without a signing key (this node is not a primary)")
	}

	tip := bc.blocks[len(bc.blocks)-1]
	timestamp := time.Now().UTC().Format(time.RFC3339)
	txs := []Transaction{tx}

	hash, signature, err := Seal(tip.Index+1, tip.Hash, timestamp, txs, bc.signer)
	if err != nil {
		return Block{}, err
	}

	newBlock := Block{
		Index:        tip.Index + 1,
		Timestamp:    timestamp,
		PrevHash:     tip.Hash,
		Hash:         hash,
		Signature:    signature,
		Transactions: txs,
	}
	bc.blocks = append(bc.blocks, newBlock)
	bc.persistLocked()
	return newBlock, nil
}

// Append validates candidate against the current tip (and trustedPubKey)
// and, if valid, appends it to the chain.
func (bc *Blockchain) Append(candidate Block) error {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	tip := bc.blocks[len(bc.blocks)-1]
	if err := ValidateBlock(candidate, tip, bc.trustedPubKey); err != nil {
		return err
	}
	bc.blocks = append(bc.blocks, candidate)
	bc.persistLocked()
	return nil
}

// ReplaceIfLonger validates candidate as a full chain (against
// trustedPubKey) and, if it is both valid and strictly longer than the
// current local chain, replaces the local chain with it
// (longest-valid-chain rule). It reports whether the replacement happened.
//
// Per CONTRACT.md's "Chain-replacement asymmetry": this method exists on
// every Blockchain, but callers must only invoke it on a follower's chain
// — a primary is authoritative by construction and must never replace its
// own chain via this path. That gating lives in the p2p layer (see
// p2p.Node.Role), not here.
func (bc *Blockchain) ReplaceIfLonger(candidate []Block) (bool, error) {
	bc.mu.Lock()
	defer bc.mu.Unlock()

	if len(candidate) <= len(bc.blocks) {
		return false, nil
	}
	if err := ValidateChain(candidate, bc.trustedPubKey); err != nil {
		return false, err
	}
	newBlocks := make([]Block, len(candidate))
	copy(newBlocks, candidate)
	bc.blocks = newBlocks
	bc.persistLocked()
	return true, nil
}
