package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Wallet is an Ed25519 keypair, exactly what the browser wallet page holds: the address is the
// hex-encoded raw 32-byte public key, and it is both where coins are received and the identity the
// proxy verifies signatures against (CONTRACT.md, "Auth for wallet-management actions").
type Wallet struct {
	// Seed is the 32-byte Ed25519 seed, hex-encoded — the whole secret. Stored rather than the
	// expanded private key because the expanded form is derivable from it and half of it is the
	// public key anyway.
	Seed    string `json:"seed"`
	Address string `json:"address"`
}

func (w *Wallet) private() ed25519.PrivateKey {
	seed, err := hex.DecodeString(w.Seed)
	if err != nil || len(seed) != ed25519.SeedSize {
		return nil
	}
	return ed25519.NewKeyFromSeed(seed)
}

func newWallet() (*Wallet, error) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		return nil, err
	}
	return &Wallet{
		Seed:    hex.EncodeToString(priv.Seed()),
		Address: hex.EncodeToString(pub),
	}, nil
}

// walletFromSeed rebuilds a wallet from a hex seed. A 64-byte expanded private key is accepted too,
// since that is what some exports carry, and its first half is the seed.
func walletFromSeed(seedHex string) (*Wallet, error) {
	seedHex = strings.TrimSpace(strings.ToLower(seedHex))
	raw, err := hex.DecodeString(seedHex)
	if err != nil {
		return nil, fmt.Errorf("key must be hex: %w", err)
	}
	switch len(raw) {
	case ed25519.SeedSize:
	case ed25519.PrivateKeySize:
		raw = raw[:ed25519.SeedSize]
	default:
		return nil, fmt.Errorf("key must be %d or %d bytes of hex, got %d",
			ed25519.SeedSize, ed25519.PrivateKeySize, len(raw))
	}
	priv := ed25519.NewKeyFromSeed(raw)
	return &Wallet{
		Seed:    hex.EncodeToString(raw),
		Address: hex.EncodeToString(priv.Public().(ed25519.PublicKey)),
	}, nil
}

func loadWallet(path string) (*Wallet, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, fmt.Errorf("no wallet at %s — run `aicoin new` first", path)
		}
		return nil, err
	}
	var w Wallet
	if err := json.Unmarshal(data, &w); err != nil {
		return nil, fmt.Errorf("%s is not a wallet file: %w", path, err)
	}
	if w.private() == nil {
		return nil, fmt.Errorf("%s holds no usable key", path)
	}
	return &w, nil
}

// save writes the wallet 0600, in a 0700 directory. This file is the wallet: anyone who reads it
// can spend every coin in it, and there is no recovery phrase or server-side copy to fall back on.
func (w *Wallet) save(path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(w, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0o600)
}

// signLive builds the three headers a wallet-management action needs, signing this exact request.
// The canonical message is fixed by CONTRACT.md and must match byte for byte:
//
//	address \n timestampMillis \n method \n path \n hex(sha256(body))
//
// `path` carries no query string.
func (w *Wallet) signLive(method, path string, body []byte) map[string]string {
	timestamp := time.Now().UnixMilli()
	sum := sha256.Sum256(body)
	message := fmt.Sprintf("%s\n%d\n%s\n%s\n%s", w.Address, timestamp, method, path, hex.EncodeToString(sum[:]))
	signature := ed25519.Sign(w.private(), []byte(message))
	return map[string]string{
		"X-Api-Key":       w.Address,
		"X-Api-Signature": hex.EncodeToString(signature),
		"X-Api-Timestamp": fmt.Sprintf("%d", timestamp),
	}
}

// token issues an API token: `base64url(payload).base64url(signature)`, unpadded, with the
// signature over the encoded payload string — not over the raw JSON, so there is no key-ordering
// ambiguity. Issuance is entirely client-side; the proxy has no endpoint for it and never sees the
// private key.
//
// A token can only spend the wallet's coins on AI calls. Claiming and transferring need the key
// itself, so a leaked token cannot drain a wallet — but it can run the balance down until it
// expires or `aicoin revoke` invalidates every token issued so far.
func (w *Wallet) token(validFor time.Duration) (string, error) {
	now := time.Now().Unix()
	payload := map[string]any{
		"addr": w.Address,
		"iat":  now,
		"exp":  now + int64(validFor.Seconds()),
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	payloadB64 := base64.RawURLEncoding.EncodeToString(encoded)
	signature := ed25519.Sign(w.private(), []byte(payloadB64))
	return payloadB64 + "." + base64.RawURLEncoding.EncodeToString(signature), nil
}
