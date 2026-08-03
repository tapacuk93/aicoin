package chain

import "strings"

// GenesisTimestamp and genesisPrevHash are fixed constants so that every
// node derives an identical genesis block independently, without needing
// to gossip it. Genesis is never signed (Signature is empty) — index 0 is
// always special-cased as valid in ValidateBlock, regardless of signature.
const GenesisTimestamp = "1970-01-01T00:00:00Z"

var genesisPrevHash = strings.Repeat("0", 64)

// Genesis returns the canonical genesis block (index 0). It is
// deterministic: every node constructs byte-for-byte the same genesis
// block independently, with no signature to verify.
func Genesis() Block {
	txs := []Transaction{}
	hash, err := ComputeHash(0, genesisPrevHash, GenesisTimestamp, txs)
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
		Signature:    "",
		Transactions: txs,
	}
}
