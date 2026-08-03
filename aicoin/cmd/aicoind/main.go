// Command aicoind is the aicoin blockchain node, per CONTRACT.md: it runs
// the HTTP API server against a single chain (in-memory only, or
// DynamoDB-backed when -dynamodb-table is set).
//
// There is exactly one legitimate writer, the primary, which holds an
// Ed25519 keypair and signs every block it appends. Followers hold only
// the primary's public key, replicate its signed chain by polling the
// same DynamoDB table the primary writes to, and reject all writes. See
// CONTRACT.md's "Roles & signing" section.
package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"aicoin/internal/api"
	"aicoin/internal/chain"
	"aicoin/internal/state"
	"aicoin/internal/store"
)

func main() {
	httpAddr := flag.String("http", ":9944", "HTTP API listen address")
	role := flag.String("role", "primary", "node role: primary or follower")
	keyfile := flag.String("keyfile", "aicoin-node.key", "primary only: path to this node's persistent Ed25519 private key (generated on first run if it doesn't exist)")
	trustedPubKeyHex := flag.String("trusted-pubkey", "", "required when -role=follower: the primary's Ed25519 public key, hex-encoded")
	dynamoTable := flag.String("dynamodb-table", "", "DynamoDB table name for chain persistence; optional for a primary (unset = in-memory only), required for a follower (the only replication mechanism). AWS region/credentials come from the standard SDK environment (AWS_REGION, instance role, etc.)")
	followerPollInterval := flag.Duration("follower-poll-interval", 2*time.Second, "follower only: how often to re-query the shared chain from DynamoDB and adopt it if it's grown")

	halfLifeDays := flag.Float64("decay-halflife-days", state.DefaultHalfLifeDays, "price decay half-life in days: weight(age)=2^(-age_days/halflife); default derived from a real, documented ~10x-per-year AI pricing decline rate")
	flag.Parse()

	roleVal := strings.ToLower(strings.TrimSpace(*role))

	// Per CONTRACT.md's "Roles & signing"/"Persistence" sections: DynamoDB
	// is now the only replication mechanism, so a follower with no
	// -dynamodb-table has no way to ever learn the primary's chain. Fail
	// fast, before doing anything else.
	if roleVal == "follower" && strings.TrimSpace(*dynamoTable) == "" {
		log.Fatalf("aicoind: -dynamodb-table is required when -role=follower (DynamoDB is the only replication mechanism; a follower with no -dynamodb-table has no way to learn the primary's chain)")
	}

	var chainStore chain.ChainStore
	if strings.TrimSpace(*dynamoTable) != "" {
		ddb, err := store.NewDynamoDB(context.Background(), *dynamoTable)
		if err != nil {
			log.Fatalf("aicoind: creating DynamoDB client for table %s: %v", *dynamoTable, err)
		}
		chainStore = ddb
	}

	var (
		trustedPubKey ed25519.PublicKey
		signer        ed25519.PrivateKey
		pubKeyHex     string
	)

	switch roleVal {
	case "primary":
		priv, err := loadOrCreateSigningKey(*keyfile)
		if err != nil {
			log.Fatalf("aicoind: loading/generating signing key from %s: %v", *keyfile, err)
		}
		signer = priv
		trustedPubKey = priv.Public().(ed25519.PublicKey)
		pubKeyHex = hex.EncodeToString(trustedPubKey)
		log.Printf("aicoind: role=primary pubkey=%s (copy this into a follower's -trusted-pubkey)", pubKeyHex)

	case "follower":
		hexKey := strings.TrimSpace(*trustedPubKeyHex)
		if hexKey == "" {
			log.Fatalf("aicoind: -trusted-pubkey is required when -role=follower")
		}
		decoded, err := hex.DecodeString(hexKey)
		if err != nil || len(decoded) != ed25519.PublicKeySize {
			log.Fatalf("aicoind: -trusted-pubkey must be a %d-byte hex-encoded Ed25519 public key: %v", ed25519.PublicKeySize, err)
		}
		trustedPubKey = ed25519.PublicKey(decoded)
		pubKeyHex = hexKey
		log.Printf("aicoind: role=follower trusted_pubkey=%s", pubKeyHex)

	default:
		log.Fatalf("aicoind: -role must be %q or %q, got %q", "primary", "follower", *role)
	}

	bc, err := chain.NewBlockchainWithStore(trustedPubKey, signer, chainStore)
	if err != nil {
		log.Fatalf("aicoind: loading chain from dynamodb table %s: %v", *dynamoTable, err)
	}

	// Per CONTRACT.md's "Roles & signing" section: only a follower ever
	// polls/replaces its chain from the store; a primary only ever writes
	// to it (via bc's own persistBlockLocked, triggered by SealAndAppend)
	// and never reads it again after this startup load. So this goroutine
	// is only ever started for -role=follower — a primary simply never
	// runs it (chain.PollAndAdopt is also a structural no-op on a
	// primary-shaped Blockchain, as a second line of defense).
	if roleVal == "follower" {
		go runFollowerPollLoop(bc, chainStore, *followerPollInterval)
	}

	srv := api.NewServer(bc, *halfLifeDays, roleVal, pubKeyHex)

	log.Printf("aicoind: http=%s role=%s dynamodb_table=%q follower_poll_interval=%s genesis=%s",
		*httpAddr, roleVal, *dynamoTable, *followerPollInterval, chain.Genesis().Hash)

	if err := http.ListenAndServe(*httpAddr, srv.Router()); err != nil {
		log.Fatalf("aicoind: http listen on %s: %v", *httpAddr, err)
	}
}

// runFollowerPollLoop runs a follower's replication loop: every interval,
// it calls chain.PollAndAdopt(bc, chainStore) — the same
// ValidateChain/ReplaceIfLonger logic used everywhere else in this
// codebase, just triggered by a timer tick instead of an incoming P2P
// message — and logs the outcome. It blocks; run it in its own goroutine.
// It is never started for a primary (see main above).
func runFollowerPollLoop(bc *chain.Blockchain, chainStore chain.ChainStore, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range ticker.C {
		replaced, err := chain.PollAndAdopt(bc, chainStore)
		if err != nil {
			log.Printf("aicoind: follower poll: querying chain from dynamodb: %v", err)
			continue
		}
		if replaced {
			log.Printf("aicoind: follower poll: adopted longer valid chain from dynamodb (height %d)", bc.Tip().Index)
		}
	}
}

// loadOrCreateSigningKey loads the primary's persistent Ed25519 private
// key from path, generating and writing a fresh one (raw 64-byte private
// key format, per crypto/ed25519) if the file doesn't exist yet, per
// CONTRACT.md's "Roles & signing" section.
func loadOrCreateSigningKey(path string) (ed25519.PrivateKey, error) {
	data, err := os.ReadFile(path)
	if err == nil {
		if len(data) != ed25519.PrivateKeySize {
			return nil, fmt.Errorf("keyfile %s: expected %d raw bytes, got %d (corrupt or wrong format)", path, ed25519.PrivateKeySize, len(data))
		}
		return ed25519.PrivateKey(data), nil
	}
	if !os.IsNotExist(err) {
		return nil, fmt.Errorf("reading keyfile %s: %w", path, err)
	}

	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generating new signing key: %w", err)
	}
	if err := os.WriteFile(path, priv, 0o600); err != nil {
		return nil, fmt.Errorf("writing new keyfile %s: %w", path, err)
	}
	log.Printf("aicoind: no keyfile found at %s; generated a new Ed25519 signing key and saved it there", path)
	return priv, nil
}
