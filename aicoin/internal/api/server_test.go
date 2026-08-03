package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"aicoin/internal/chain"
	"aicoin/internal/state"
)

func newTestServer() *Server {
	bc := chain.NewBlockchain(1)
	return NewServer(bc, nil, state.DefaultDecayWeights())
}

func postJSON(t *testing.T, srv *Server, path string, body interface{}) *httptest.ResponseRecorder {
	t.Helper()
	data, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal request body: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(data))
	rec := httptest.NewRecorder()
	srv.Router().ServeHTTP(rec, req)
	return rec
}

func getJSON(t *testing.T, srv *Server, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, path, nil)
	rec := httptest.NewRecorder()
	srv.Router().ServeHTTP(rec, req)
	return rec
}

func decodeBody(t *testing.T, rec *httptest.ResponseRecorder, v interface{}) {
	t.Helper()
	if err := json.Unmarshal(rec.Body.Bytes(), v); err != nil {
		t.Fatalf("decode response body %q: %v", rec.Body.String(), err)
	}
}

// TestTransferMovesBalanceCorrectly proves POST /transfer's happy path:
// after granting alice a free coin, transferring part of it to bob updates
// both balances exactly as CONTRACT.md's "Peer transfer (buy/sell)"
// section specifies, and mines exactly one new block for the transfer.
func TestTransferMovesBalanceCorrectly(t *testing.T) {
	srv := newTestServer()

	claimRec := postJSON(t, srv, "/free-coins/claim", map[string]string{"user_id": "alice"})
	if claimRec.Code != http.StatusOK {
		t.Fatalf("free-coins/claim status = %d, body = %s", claimRec.Code, claimRec.Body.String())
	}

	heightBefore := srv.Chain.Tip().Index

	rec := postJSON(t, srv, "/transfer", map[string]interface{}{
		"from_user_id": "alice",
		"to_user_id":   "bob",
		"amount":       0.4,
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /transfer status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var transferResp struct {
		Height int    `json:"height"`
		Hash   string `json:"hash"`
	}
	decodeBody(t, rec, &transferResp)
	if transferResp.Height != heightBefore+1 {
		t.Errorf("transfer height = %d, want %d (exactly one new block)", transferResp.Height, heightBefore+1)
	}
	if transferResp.Hash == "" {
		t.Error("transfer hash is empty")
	}

	var aliceBal, bobBal struct {
		UserID  string  `json:"user_id"`
		Balance float64 `json:"balance"`
	}
	decodeBody(t, getJSON(t, srv, "/balance/alice"), &aliceBal)
	decodeBody(t, getJSON(t, srv, "/balance/bob"), &bobBal)

	if !floatEquals(aliceBal.Balance, 0.6) {
		t.Errorf("alice balance = %v, want 0.6 (1.0 free_claim - 0.4 transferred)", aliceBal.Balance)
	}
	if !floatEquals(bobBal.Balance, 0.4) {
		t.Errorf("bob balance = %v, want 0.4 (received transfer)", bobBal.Balance)
	}
}

// TestTransferInsufficientBalanceRejectedNoMutation proves the guard rail
// from CONTRACT.md's "Peer transfer (buy/sell)" section: a transfer that
// exceeds the sender's current derived balance is rejected with
// 400 {"error":"insufficient balance"} and, critically, does NOT mutate
// the chain (no block mined) — the height and full chain contents must be
// identical before and after the rejected attempt.
func TestTransferInsufficientBalanceRejectedNoMutation(t *testing.T) {
	srv := newTestServer()

	// alice has never claimed or received anything: balance is 0.
	heightBefore := srv.Chain.Tip().Index
	chainBefore := srv.Chain.Blocks()

	rec := postJSON(t, srv, "/transfer", map[string]interface{}{
		"from_user_id": "alice",
		"to_user_id":   "bob",
		"amount":       1.0,
	})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400; body = %s", rec.Code, rec.Body.String())
	}
	var errResp struct {
		Error string `json:"error"`
	}
	decodeBody(t, rec, &errResp)
	if errResp.Error != "insufficient balance" {
		t.Errorf("error = %q, want %q", errResp.Error, "insufficient balance")
	}

	if got := srv.Chain.Tip().Index; got != heightBefore {
		t.Errorf("height after rejected transfer = %d, want unchanged %d", got, heightBefore)
	}
	chainAfter := srv.Chain.Blocks()
	if len(chainAfter) != len(chainBefore) {
		t.Errorf("chain length changed after rejected transfer: %d -> %d", len(chainBefore), len(chainAfter))
	}

	var bobBal struct {
		Balance float64 `json:"balance"`
	}
	decodeBody(t, getJSON(t, srv, "/balance/bob"), &bobBal)
	if bobBal.Balance != 0 {
		t.Errorf("bob balance = %v, want 0 (rejected transfer must not have moved anything)", bobBal.Balance)
	}
}

// TestTransferZeroOrNegativeAmountRejected proves amount<=0 is rejected
// exactly like insufficient balance (CONTRACT.md validates both amount>0
// and balance>=amount with the same 400/insufficient-balance response),
// and does not mutate the chain.
func TestTransferZeroOrNegativeAmountRejected(t *testing.T) {
	srv := newTestServer()
	// Give alice a balance so the only failing condition is amount<=0.
	postJSON(t, srv, "/free-coins/claim", map[string]string{"user_id": "alice"})
	heightBefore := srv.Chain.Tip().Index

	for _, amount := range []float64{0, -5} {
		rec := postJSON(t, srv, "/transfer", map[string]interface{}{
			"from_user_id": "alice",
			"to_user_id":   "bob",
			"amount":       amount,
		})
		if rec.Code != http.StatusBadRequest {
			t.Errorf("amount=%v: status = %d, want 400", amount, rec.Code)
		}
	}
	if got := srv.Chain.Tip().Index; got != heightBefore {
		t.Errorf("height changed after rejected zero/negative transfers: %d -> %d", heightBefore, got)
	}
}

// floatEquals mirrors internal/state's test helper (kept separate per
// package, deliberately not exported from state).
func floatEquals(a, b float64) bool {
	const eps = 1e-9
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < eps
}
