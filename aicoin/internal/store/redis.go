package store

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/redis/go-redis/v9"

	"aicoin/internal/chain"
)

// chainKey is the fixed Redis key CONTRACT.md's "Persistence" section
// specifies: "aicoin:chain".
const chainKey = "aicoin:chain"

// Redis is a chain.ChainStore backed by a Redis (or Redis-compatible, e.g.
// a real AWS ElastiCache/MemoryDB deployment later) server, per
// CONTRACT.md's "Persistence" section. It is the only file in this package
// that imports a Redis client, so the dependency stays contained here.
type Redis struct {
	client *redis.Client
}

// NewRedis creates a Redis-backed ChainStore talking to addr (host:port).
// It does not connect eagerly; connection errors surface on the first
// Load/Save call.
func NewRedis(addr string) *Redis {
	return &Redis{client: redis.NewClient(&redis.Options{Addr: addr})}
}

// Load fetches the chain JSON array stored at chainKey and unmarshals it.
// If the key doesn't exist yet, it returns (nil, nil) — "nothing persisted
// yet", per the chain.ChainStore contract.
func (r *Redis) Load() ([]chain.Block, error) {
	ctx := context.Background()
	val, err := r.client.Get(ctx, chainKey).Result()
	if err == redis.Nil {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("store: redis GET %s: %w", chainKey, err)
	}
	var blocks []chain.Block
	if err := json.Unmarshal([]byte(val), &blocks); err != nil {
		return nil, fmt.Errorf("store: unmarshal chain from redis: %w", err)
	}
	return blocks, nil
}

// Save marshals blocks to JSON and SETs it at chainKey, overwriting
// whatever was there before.
func (r *Redis) Save(blocks []chain.Block) error {
	ctx := context.Background()
	data, err := json.Marshal(blocks)
	if err != nil {
		return fmt.Errorf("store: marshal chain for redis: %w", err)
	}
	if err := r.client.Set(ctx, chainKey, data, 0).Err(); err != nil {
		return fmt.Errorf("store: redis SET %s: %w", chainKey, err)
	}
	return nil
}
