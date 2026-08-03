package chain

// ChainStore persists and reloads the chain one block at a time, per
// CONTRACT.md's "Persistence (DynamoDB — genuinely durable, not a cache)"
// section. It is deliberately tiny so that a concrete backend (DynamoDB, an
// in-memory fake for tests, or something else entirely later) can implement
// it without this package knowing anything about the backend.
//
// Load returns (nil, nil) when nothing has been persisted yet — callers
// should treat that exactly like "no chain to restore" (i.e. start from
// genesis). AppendBlock writes exactly one new block (not the whole chain)
// every time the chain grows by one — this is both required by DynamoDB's
// 400KB per-item limit (the whole chain would eventually exceed it) and
// more efficient in general, an O(1) write regardless of chain length.
type ChainStore interface {
	Load() ([]Block, error)
	AppendBlock(b Block) error
}
