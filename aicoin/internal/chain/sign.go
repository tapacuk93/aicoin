package chain

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
)

// digestBytes computes the raw 32-byte SHA-256 digest over a block's own
// fields, exactly as specified by CONTRACT.md's Hash formula:
//
//	SHA256(index|prevHash|timestamp|txJSON)
//
// txJSON is the JSON encoding of the block's Transactions slice. This raw
// digest (not its hex encoding) is what gets signed/verified — ComputeHash
// hex-encodes it for storage in Block.Hash.
func digestBytes(index int, prevHash, timestamp string, txs []Transaction) ([sha256.Size]byte, error) {
	txJSON, err := json.Marshal(txs)
	if err != nil {
		return [sha256.Size]byte{}, fmt.Errorf("chain: marshal transactions: %w", err)
	}
	data := fmt.Sprintf("%d|%s|%s|%s", index, prevHash, timestamp, string(txJSON))
	return sha256.Sum256([]byte(data)), nil
}

// ComputeHash computes the block's content hash exactly as specified by
// CONTRACT.md: hex(SHA256(index|prevHash|timestamp|txJSON)). It is a plain
// content hash, not a puzzle — there is no nonce and nothing to search for.
func ComputeHash(index int, prevHash, timestamp string, txs []Transaction) (string, error) {
	d, err := digestBytes(index, prevHash, timestamp, txs)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(d[:]), nil
}

// Seal computes a new block's Hash and Ed25519 Signature, per
// CONTRACT.md's "Chain model" section: for index >= 1, the primary
// computes Hash, then signs the raw 32-byte digest (not the hex Hash
// string) with its private key and hex-encodes the resulting signature.
// This is a one-shot computation — no search/delay, unlike the PoW mining
// step it replaces.
func Seal(index int, prevHash, timestamp string, txs []Transaction, priv ed25519.PrivateKey) (hash, signature string, err error) {
	d, err := digestBytes(index, prevHash, timestamp, txs)
	if err != nil {
		return "", "", err
	}
	hash = hex.EncodeToString(d[:])
	signature = hex.EncodeToString(ed25519.Sign(priv, d[:]))
	return hash, signature, nil
}

// verifySignature reports whether block's Signature is a valid Ed25519
// signature, by trustedPubKey, over the raw digest of block's own fields.
func verifySignature(block Block, trustedPubKey ed25519.PublicKey) (bool, error) {
	sigBytes, err := hex.DecodeString(block.Signature)
	if err != nil {
		return false, fmt.Errorf("signature is not valid hex: %w", err)
	}
	d, err := digestBytes(block.Index, block.PrevHash, block.Timestamp, block.Transactions)
	if err != nil {
		return false, err
	}
	return ed25519.Verify(trustedPubKey, d[:], sigBytes), nil
}
