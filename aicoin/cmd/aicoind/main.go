// Command aicoind is the aicoin P2P blockchain node, per CONTRACT.md: it
// starts both the HTTP API server and the P2P TCP gossip server against a
// single chain (in-memory only, or Redis-backed when -redis is set).
//
// There is exactly one legitimate writer, the primary, which holds an
// Ed25519 keypair and signs every block it appends. Followers hold only
// the primary's public key, replicate its signed chain via P2P, and
// reject all writes. See CONTRACT.md's "Roles & signing" section.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"aicoin/internal/api"
	"aicoin/internal/chain"
	"aicoin/internal/p2p"
	"aicoin/internal/state"
	"aicoin/internal/store"
)

func main() {
	httpAddr := flag.String("http", ":9944", "HTTP API listen address")
	p2pAddr := flag.String("p2p", ":9945", "P2P TCP listen address")
	peersFlag := flag.String("peers", "", "comma-separated bootstrap peer P2P addresses (host:port,...)")
	role := flag.String("role", "primary", "node role: primary or follower")
	keyfile := flag.String("keyfile", "aicoin-node.key", "primary only: path to this node's persistent Ed25519 private key (generated on first run if it doesn't exist)")
	trustedPubKeyHex := flag.String("trusted-pubkey", "", "required when -role=follower: the primary's Ed25519 public key, hex-encoded")
	redisAddr := flag.String("redis", "", "optional Redis host:port for chain persistence (unset = in-memory only)")

	defaults := state.DefaultDecayWeights()
	decayHour := flag.Float64("decay-hour", defaults.Hour, "price weight for events in the same UTC hour as now")
	decayDay := flag.Float64("decay-day", defaults.Day, "price weight for events in the same UTC day as now")
	decayWeek := flag.Float64("decay-week", defaults.Week, "price weight for events in the same ISO week as now")
	decayMonth := flag.Float64("decay-month", defaults.Month, "price weight for events in the same UTC month as now")
	decayYear := flag.Float64("decay-year", defaults.Year, "price weight for events in the same UTC year as now")
	decayOlder := flag.Float64("decay-older", defaults.Older, "price weight for events from a prior UTC year")
	flag.Parse()

	peerAddrs := parsePeers(*peersFlag)
	weights := state.DecayWeights{
		Hour:  *decayHour,
		Day:   *decayDay,
		Week:  *decayWeek,
		Month: *decayMonth,
		Year:  *decayYear,
		Older: *decayOlder,
	}

	var chainStore chain.ChainStore
	if strings.TrimSpace(*redisAddr) != "" {
		chainStore = store.NewRedis(*redisAddr)
	}

	roleVal := strings.ToLower(strings.TrimSpace(*role))

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
		log.Fatalf("aicoind: loading chain from redis %s: %v", *redisAddr, err)
	}

	node := p2p.NewNode(*p2pAddr, roleVal, bc)

	if err := node.Listen(); err != nil {
		log.Fatalf("aicoind: p2p listen on %s: %v", *p2pAddr, err)
	}
	go func() {
		if err := node.Serve(); err != nil {
			log.Printf("aicoind: p2p serve stopped: %v", err)
		}
	}()

	if len(peerAddrs) > 0 {
		node.ConnectToPeers(peerAddrs)
	}

	srv := api.NewServer(bc, node, weights, roleVal, pubKeyHex)

	log.Printf("aicoind: http=%s p2p=%s role=%s peers=%v redis=%q genesis=%s",
		*httpAddr, *p2pAddr, roleVal, peerAddrs, *redisAddr, chain.Genesis().Hash)

	if err := http.ListenAndServe(*httpAddr, srv.Router()); err != nil {
		log.Fatalf("aicoind: http listen on %s: %v", *httpAddr, err)
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

func parsePeers(flagVal string) []string {
	if strings.TrimSpace(flagVal) == "" {
		return nil
	}
	var out []string
	for _, a := range strings.Split(flagVal, ",") {
		a = strings.TrimSpace(a)
		if a != "" {
			out = append(out, a)
		}
	}
	return out
}
