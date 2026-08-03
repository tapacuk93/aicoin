package chain

// ChainStore persists and reloads the full chain, per CONTRACT.md's
// "Persistence" section ("a Redis stand-in for a real AWS in-memory
// store"). It is deliberately tiny so that a concrete backend (Redis, an
// in-memory fake for tests, or something else entirely later) can
// implement it without this package knowing anything about the backend.
//
// Load returns (nil, nil) when nothing has been persisted yet — callers
// should treat that exactly like "no chain to restore" (i.e. start from
// genesis). Save is called with the full current chain every time it
// changes; a well-behaved implementation persists it atomically enough
// that a concurrent Load never observes a partial write.
type ChainStore interface {
	Load() ([]Block, error)
	Save(blocks []Block) error
}
