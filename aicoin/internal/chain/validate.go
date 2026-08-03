package chain

import (
	"errors"
	"fmt"
)

// ValidateBlock checks that candidate is a valid successor to prev:
//   - index is exactly prev.Index+1
//   - PrevHash links to prev.Hash
//   - it carries exactly one transaction (every non-genesis block has
//     exactly one, per CONTRACT.md's "one transaction per block" rule)
//   - its stored Hash is exactly ComputeHash(...) of its own fields
//   - its Hash satisfies the configured difficulty
func ValidateBlock(prev, candidate Block, difficulty int) error {
	if candidate.Index != prev.Index+1 {
		return fmt.Errorf("chain: block index %d does not follow previous index %d", candidate.Index, prev.Index)
	}
	if candidate.PrevHash != prev.Hash {
		return fmt.Errorf("chain: block %d prev_hash %q does not match tip hash %q", candidate.Index, candidate.PrevHash, prev.Hash)
	}
	if len(candidate.Transactions) != 1 {
		return fmt.Errorf("chain: block %d must carry exactly 1 transaction, got %d", candidate.Index, len(candidate.Transactions))
	}
	computed, err := ComputeHash(candidate.Index, candidate.PrevHash, candidate.Timestamp, candidate.Transactions, candidate.Nonce)
	if err != nil {
		return err
	}
	if computed != candidate.Hash {
		return fmt.Errorf("chain: block %d hash %q does not match recomputed hash %q", candidate.Index, candidate.Hash, computed)
	}
	if !MeetsDifficulty(candidate.Hash, difficulty) {
		return fmt.Errorf("chain: block %d hash %q does not meet difficulty %d", candidate.Index, candidate.Hash, difficulty)
	}
	return nil
}

// ValidateChain checks that blocks is a full valid chain: it starts with
// the canonical genesis block and every subsequent block validates against
// its predecessor per ValidateBlock.
func ValidateChain(blocks []Block, difficulty int) error {
	if len(blocks) == 0 {
		return errors.New("chain: empty chain")
	}
	g := Genesis()
	if blocks[0].Index != g.Index || blocks[0].PrevHash != g.PrevHash || blocks[0].Hash != g.Hash {
		return errors.New("chain: block 0 is not the canonical genesis block")
	}
	for i := 1; i < len(blocks); i++ {
		if err := ValidateBlock(blocks[i-1], blocks[i], difficulty); err != nil {
			return fmt.Errorf("chain: invalid chain at index %d: %w", i, err)
		}
	}
	return nil
}
