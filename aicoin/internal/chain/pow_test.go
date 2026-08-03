package chain

import (
	"strings"
	"testing"
)

func TestMineProducesValidPoW(t *testing.T) {
	tx := Transaction{Type: "event", UserID: "alice", Provider: "openai", CostUSD: 0.001, Timestamp: "2026-08-03T12:00:00Z"}
	txs := []Transaction{tx}

	const difficulty = 3
	hash, nonce, err := Mine(1, Genesis().Hash, "2026-08-03T12:00:01Z", txs, difficulty)
	if err != nil {
		t.Fatalf("Mine returned error: %v", err)
	}

	if !MeetsDifficulty(hash, difficulty) {
		t.Fatalf("mined hash %q does not have %d leading zeros", hash, difficulty)
	}
	if !strings.HasPrefix(hash, strings.Repeat("0", difficulty)) {
		t.Fatalf("mined hash %q does not have prefix of %d zeros", hash, difficulty)
	}

	// The hash returned must actually be reproducible from the reported
	// nonce via ComputeHash — i.e. Mine isn't just returning a hash that
	// happens to satisfy difficulty independent of the nonce it reports.
	recomputed, err := ComputeHash(1, Genesis().Hash, "2026-08-03T12:00:01Z", txs, nonce)
	if err != nil {
		t.Fatalf("ComputeHash returned error: %v", err)
	}
	if recomputed != hash {
		t.Fatalf("ComputeHash(nonce=%d) = %q, want %q", nonce, recomputed, hash)
	}
}

func TestMeetsDifficultyZeroAlwaysTrue(t *testing.T) {
	if !MeetsDifficulty("ffffffff", 0) {
		t.Fatal("difficulty 0 should always be satisfied")
	}
}

func TestMeetsDifficultyChecksPrefix(t *testing.T) {
	if !MeetsDifficulty("00ff", 2) {
		t.Fatal("expected 2 leading zeros to satisfy difficulty 2")
	}
	if MeetsDifficulty("0aff", 2) {
		t.Fatal("expected 0aff to NOT satisfy difficulty 2 (second char isn't 0)")
	}
	if MeetsDifficulty("0", 2) {
		t.Fatal("hash shorter than difficulty should not satisfy it")
	}
}

func TestGenesisIsDeterministic(t *testing.T) {
	g1 := Genesis()
	g2 := Genesis()
	if g1.Hash != g2.Hash {
		t.Fatalf("genesis hash is not deterministic: %q vs %q", g1.Hash, g2.Hash)
	}
	if g1.Index != 0 {
		t.Fatalf("genesis index = %d, want 0", g1.Index)
	}
	if len(g1.PrevHash) != 64 || strings.Trim(g1.PrevHash, "0") != "" {
		t.Fatalf("genesis PrevHash = %q, want 64 zero chars", g1.PrevHash)
	}
	if len(g1.Transactions) != 0 {
		t.Fatalf("genesis should have no transactions, got %d", len(g1.Transactions))
	}
}
