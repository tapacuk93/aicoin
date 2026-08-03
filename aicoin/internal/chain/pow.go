package chain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
)

// ComputeHash computes the block hash exactly as specified by CONTRACT.md:
//
//	hash = hex(SHA256(index|prevHash|timestamp|txJSON|nonce))
//
// txJSON is the JSON encoding of the block's Transactions slice.
func ComputeHash(index int, prevHash, timestamp string, txs []Transaction, nonce int) (string, error) {
	txJSON, err := json.Marshal(txs)
	if err != nil {
		return "", fmt.Errorf("chain: marshal transactions: %w", err)
	}
	data := fmt.Sprintf("%d|%s|%s|%s|%d", index, prevHash, timestamp, string(txJSON), nonce)
	sum := sha256.Sum256([]byte(data))
	return hex.EncodeToString(sum[:]), nil
}

// MeetsDifficulty reports whether hash has at least difficulty leading hex
// '0' characters.
func MeetsDifficulty(hash string, difficulty int) bool {
	if difficulty <= 0 {
		return true
	}
	if len(hash) < difficulty {
		return false
	}
	for i := 0; i < difficulty; i++ {
		if hash[i] != '0' {
			return false
		}
	}
	return true
}

// Mine performs proof-of-work: it searches for a nonce such that
// ComputeHash(index, prevHash, timestamp, txs, nonce) satisfies
// MeetsDifficulty, and returns the resulting hash and nonce.
func Mine(index int, prevHash, timestamp string, txs []Transaction, difficulty int) (hash string, nonce int, err error) {
	for n := 0; ; n++ {
		h, err := ComputeHash(index, prevHash, timestamp, txs, n)
		if err != nil {
			return "", 0, err
		}
		if MeetsDifficulty(h, difficulty) {
			return h, n, nil
		}
	}
}
