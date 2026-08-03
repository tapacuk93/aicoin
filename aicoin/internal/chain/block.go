// Package chain implements the aicoin block/chain model: block and
// transaction types, proof-of-work mining, and chain validation, per
// CONTRACT.md.
package chain

// Transaction is a single transaction recorded on chain. It is a tagged
// union over Type ("event", "free_claim", or "transfer"); which of the
// other fields are populated depends on Type, per CONTRACT.md:
//
//   - "event":       UserID, Provider, CostUSD, Timestamp
//   - "free_claim":  UserID, Timestamp
//   - "transfer":    FromUserID, ToUserID, Amount, Timestamp
//
// Field names and JSON tags follow CONTRACT.md's bodies (user_id,
// provider, cost_usd, from_user_id, to_user_id, amount, timestamp) plus a
// literal "type" discriminator. Fields not relevant to a given Type are
// omitted from its JSON encoding (omitempty) so each transaction type's
// on-chain JSON only carries the fields that actually apply to it.
type Transaction struct {
	Type       string  `json:"type"`
	UserID     string  `json:"user_id,omitempty"`
	Provider   string  `json:"provider,omitempty"`
	CostUSD    float64 `json:"cost_usd,omitempty"`
	Timestamp  string  `json:"timestamp"`
	FromUserID string  `json:"from_user_id,omitempty"`
	ToUserID   string  `json:"to_user_id,omitempty"`
	Amount     float64 `json:"amount,omitempty"`
}

// Block is a single block in the chain.
//
// Transactions is always length 0 (genesis only) or length 1 (every other
// block), per CONTRACT.md's "one transaction per block" rule.
type Block struct {
	Index        int           `json:"index"`
	Timestamp    string        `json:"timestamp"`
	PrevHash     string        `json:"prev_hash"`
	Hash         string        `json:"hash"`
	Nonce        int           `json:"nonce"`
	Transactions []Transaction `json:"transactions"`
}
