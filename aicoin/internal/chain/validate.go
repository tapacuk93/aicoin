package chain

import (
	"crypto/ed25519"
	"errors"
	"fmt"
)

// ValidateBlock checks that block is valid, per CONTRACT.md:
//
//   - index 0 must exactly match the well-known deterministic genesis
//     constant — always valid, no signature check (prev is unused).
//   - index >= 1: index is exactly prev.Index+1; PrevHash links to
//     prev.Hash; it carries exactly one transaction (every non-genesis
//     block has exactly one, per CONTRACT.md's "one transaction per block"
//     rule); its stored Hash is exactly ComputeHash(...) of its own
//     fields; and its Signature verifies (via ed25519.Verify) against
//     trustedPubKey over the block's raw digest. There is no difficulty/
//     PoW check — that mechanism no longer exists.
func ValidateBlock(block, prev Block, trustedPubKey ed25519.PublicKey) error {
	if block.Index == 0 {
		g := Genesis()
		if block.Index != g.Index || block.PrevHash != g.PrevHash || block.Timestamp != g.Timestamp || block.Hash != g.Hash {
			return errors.New("chain: block 0 is not the canonical genesis block")
		}
		return nil
	}

	if block.Index != prev.Index+1 {
		return fmt.Errorf("chain: block index %d does not follow previous index %d", block.Index, prev.Index)
	}
	if block.PrevHash != prev.Hash {
		return fmt.Errorf("chain: block %d prev_hash %q does not match tip hash %q", block.Index, block.PrevHash, prev.Hash)
	}
	if len(block.Transactions) != 1 {
		return fmt.Errorf("chain: block %d must carry exactly 1 transaction, got %d", block.Index, len(block.Transactions))
	}

	computed, err := ComputeHash(block.Index, block.PrevHash, block.Timestamp, block.Transactions)
	if err != nil {
		return err
	}
	if computed != block.Hash {
		return fmt.Errorf("chain: block %d hash %q does not match recomputed hash %q", block.Index, block.Hash, computed)
	}

	ok, err := verifySignature(block, trustedPubKey)
	if err != nil {
		return fmt.Errorf("chain: block %d signature invalid: %w", block.Index, err)
	}
	if !ok {
		return fmt.Errorf("chain: block %d signature does not verify against trusted pubkey", block.Index)
	}
	return nil
}

// ValidateChain checks that blocks is a full valid chain: it starts with
// the canonical genesis block and every subsequent block validates against
// its predecessor per ValidateBlock, all against the same trustedPubKey.
func ValidateChain(blocks []Block, trustedPubKey ed25519.PublicKey) error {
	if len(blocks) == 0 {
		return errors.New("chain: empty chain")
	}
	if err := ValidateBlock(blocks[0], Block{}, trustedPubKey); err != nil {
		return fmt.Errorf("chain: invalid chain at index 0: %w", err)
	}
	for i := 1; i < len(blocks); i++ {
		if err := ValidateBlock(blocks[i], blocks[i-1], trustedPubKey); err != nil {
			return fmt.Errorf("chain: invalid chain at index %d: %w", i, err)
		}
	}
	return nil
}
