package main

import "testing"

func TestShouldAttemptClaim(t *testing.T) {
	cases := []struct {
		available int
		want      bool
	}{
		{available: 0, want: false},
		{available: -1, want: false},
		{available: 1, want: true},
		{available: 5, want: true},
	}
	for _, c := range cases {
		if got := shouldAttemptClaim(c.available); got != c.want {
			t.Errorf("shouldAttemptClaim(%d) = %v, want %v", c.available, got, c.want)
		}
	}
}

func TestParseAvailable(t *testing.T) {
	r, err := parseAvailable([]byte(`{"available": 3}`))
	if err != nil {
		t.Fatalf("parseAvailable: %v", err)
	}
	if r.Available != 3 {
		t.Errorf("Available = %d, want 3", r.Available)
	}
}

func TestParseAvailableInvalidJSON(t *testing.T) {
	if _, err := parseAvailable([]byte(`not json`)); err == nil {
		t.Fatal("parseAvailable: expected error on invalid JSON, got nil")
	}
}

func TestParseClaimGranted(t *testing.T) {
	body := []byte(`{"granted":true,"height":5,"hash":"abc123","next_eligible_at":"2026-08-03T13:00:00Z"}`)
	r, err := parseClaim(body)
	if err != nil {
		t.Fatalf("parseClaim: %v", err)
	}
	if !r.Granted {
		t.Error("Granted = false, want true")
	}
	if r.Height != 5 {
		t.Errorf("Height = %d, want 5", r.Height)
	}
	if r.Hash != "abc123" {
		t.Errorf("Hash = %q, want abc123", r.Hash)
	}
	if r.NextEligibleAt != "2026-08-03T13:00:00Z" {
		t.Errorf("NextEligibleAt = %q, want 2026-08-03T13:00:00Z", r.NextEligibleAt)
	}
}

func TestParseClaimNotGranted(t *testing.T) {
	body := []byte(`{"granted":false,"next_eligible_at":"2026-08-03T13:30:00Z"}`)
	r, err := parseClaim(body)
	if err != nil {
		t.Fatalf("parseClaim: %v", err)
	}
	if r.Granted {
		t.Error("Granted = true, want false")
	}
	if r.NextEligibleAt != "2026-08-03T13:30:00Z" {
		t.Errorf("NextEligibleAt = %q, want 2026-08-03T13:30:00Z", r.NextEligibleAt)
	}
}

func TestParseBalance(t *testing.T) {
	r, err := parseBalance([]byte(`{"user_id":"alice","balance":1}`))
	if err != nil {
		t.Fatalf("parseBalance: %v", err)
	}
	if r.UserID != "alice" {
		t.Errorf("UserID = %q, want alice", r.UserID)
	}
	if r.Balance != 1 {
		t.Errorf("Balance = %v, want 1", r.Balance)
	}
}
