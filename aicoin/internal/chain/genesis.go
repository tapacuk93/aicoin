package chain

import "strings"

// GenesisTimestamp and GenesisPrevHash are fixed constants so that every
// node derives an identical genesis block independently, without needing
// to gossip it. Nonce is fixed at 0 (genesis is not mined against the
// configured difficulty).
const GenesisTimestamp = "1970-01-01T00:00:00Z"

var genesisPrevHash = strings.Repeat("0", 64)

// Genesis returns the canonical genesis block (index 0). It is
// deterministic: every node, regardless of configured difficulty,
// constructs byte-for-byte the same genesis block.
func Genesis() Block {
	txs := []Transaction{}
	hash, err := ComputeHash(0, genesisPrevHash, GenesisTimestamp, txs, 0)
	if err != nil {
		// ComputeHash only fails to marshal transactions; an empty slice
		// always marshals successfully, so this is unreachable.
		panic(err)
	}
	return Block{
		Index:        0,
		Timestamp:    GenesisTimestamp,
		PrevHash:     genesisPrevHash,
		Hash:         hash,
		Nonce:        0,
		Transactions: txs,
	}
}
