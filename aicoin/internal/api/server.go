// Package api implements the aicoin HTTP API described in CONTRACT.md.
package api

import (
	"encoding/json"
	"net/http"
	"time"

	"aicoin/internal/chain"
	"aicoin/internal/p2p"
	"aicoin/internal/state"
)

// Server holds the dependencies shared by all HTTP handlers.
//
// Role and PubKeyHex back GET /health's "role"/"pubkey" fields and the
// write-endpoint 403 gate on followers, per CONTRACT.md's "Roles &
// signing" section: on a primary, PubKeyHex is its own signing key's
// public half; on a follower, it's the configured -trusted-pubkey.
type Server struct {
	Chain        *chain.Blockchain
	Node         *p2p.Node
	HalfLifeDays float64
	Role         string
	PubKeyHex    string
}

// NewServer creates an API server backed by bc, gossiping any locally
// sealed block via node (node may be nil in tests that don't need P2P),
// using halfLifeDays for the /price recency-decay formula (see
// CONTRACT.md's "Derived state — price (final formula, v2: smooth
// exponential decay)" section), and role/pubKeyHex for GET /health and the
// follower write-rejection gate.
func NewServer(bc *chain.Blockchain, node *p2p.Node, halfLifeDays float64, role, pubKeyHex string) *Server {
	return &Server{Chain: bc, Node: node, HalfLifeDays: halfLifeDays, Role: role, PubKeyHex: pubKeyHex}
}

// Router builds the http.Handler exposing all endpoints from CONTRACT.md.
func (s *Server) Router() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /events", s.handlePostEvents)
	mux.HandleFunc("POST /free-coins/claim", s.handlePostFreeCoinsClaim)
	mux.HandleFunc("POST /transfer", s.handlePostTransfer)
	mux.HandleFunc("GET /price", s.handleGetPrice)
	mux.HandleFunc("GET /chain", s.handleGetChain)
	mux.HandleFunc("GET /peers", s.handleGetPeers)
	mux.HandleFunc("GET /balance/{user_id}", s.handleGetBalance)
	mux.HandleFunc("GET /health", s.handleGetHealth)
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// rejectIfFollower implements CONTRACT.md's write-endpoint rejection rule:
// on a -role=follower node, every write endpoint (POST /events, /transfer,
// /free-coins/claim) refuses with 403, since a follower holds no signing
// key and cannot legitimately append a block. It reports whether the
// request was rejected (in which case the caller must return immediately
// without touching the chain).
func (s *Server) rejectIfFollower(w http.ResponseWriter) bool {
	if s.Role != "follower" {
		return false
	}
	writeError(w, http.StatusForbidden, "this node is a read-only replica; write to the primary")
	return true
}

type eventRequest struct {
	UserID    string  `json:"user_id"`
	Provider  string  `json:"provider"`
	CostUSD   float64 `json:"cost_usd"`
	Timestamp string  `json:"timestamp"`
}

// handlePostEvents implements POST /events: build the Transaction, seal
// and sign a new block on top of the local tip (primary only — see
// rejectIfFollower), append it locally, broadcast it to all connected
// peers, and return the new block info.
func (s *Server) handlePostEvents(w http.ResponseWriter, r *http.Request) {
	if s.rejectIfFollower(w) {
		return
	}

	var req eventRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if req.UserID == "" {
		writeError(w, http.StatusBadRequest, "user_id is required")
		return
	}

	ts := req.Timestamp
	if ts == "" {
		ts = time.Now().UTC().Format(time.RFC3339)
	}

	tx := chain.Transaction{
		Type:      "event",
		UserID:    req.UserID,
		Provider:  req.Provider,
		CostUSD:   req.CostUSD,
		Timestamp: ts,
	}

	block, err := s.Chain.SealAndAppend(tx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	if s.Node != nil {
		s.Node.BroadcastBlock(block, nil)
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"height": block.Index,
		"hash":   block.Hash,
	})
}

type freeCoinsClaimRequest struct {
	UserID string `json:"user_id"`
}

// handlePostFreeCoinsClaim implements POST /free-coins/claim per
// CONTRACT.md's "Free-coin faucet" section: look at the chain for the most
// recent free_claim tx for this user; if there is none, or its Timestamp is
// >= 1 hour in the past, seal+sign a new free_claim tx (through the exact
// same sealing/gossip pipeline as an event tx, primary only — see
// rejectIfFollower) and grant it. Otherwise, reject with 429 and report
// when the user becomes eligible again.
func (s *Server) handlePostFreeCoinsClaim(w http.ResponseWriter, r *http.Request) {
	if s.rejectIfFollower(w) {
		return
	}

	var req freeCoinsClaimRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if req.UserID == "" {
		writeError(w, http.StatusBadRequest, "user_id is required")
		return
	}

	now := time.Now().UTC()
	eligible, lastClaim, _ := state.FaucetEligibility(s.Chain.Blocks(), req.UserID, now)

	if !eligible {
		nextEligibleAt := lastClaim.Add(time.Hour)
		writeJSON(w, http.StatusTooManyRequests, map[string]interface{}{
			"granted":          false,
			"next_eligible_at": nextEligibleAt.Format(time.RFC3339),
		})
		return
	}

	tx := chain.Transaction{
		Type:      "free_claim",
		UserID:    req.UserID,
		Timestamp: now.Format(time.RFC3339),
	}

	block, err := s.Chain.SealAndAppend(tx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	if s.Node != nil {
		s.Node.BroadcastBlock(block, nil)
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"granted":          true,
		"height":           block.Index,
		"hash":             block.Hash,
		"next_eligible_at": now.Add(time.Hour).Format(time.RFC3339),
	})
}

// handleGetPrice implements GET /price: the recency-weighted average price
// described in CONTRACT.md's "Derived state — price (final formula, v2:
// smooth exponential decay)" section, recomputed from the chain against
// "now" (wall-clock time at query time) and the server's configured decay
// half-life.
func (s *Server) handleGetPrice(w http.ResponseWriter, r *http.Request) {
	blocks := s.Chain.Blocks()
	stats := state.Price(blocks, time.Now().UTC(), s.HalfLifeDays)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"price_usd":       stats.PriceUSD,
		"total_spend_usd": stats.TotalSpendUSD,
		"weighted_total":  stats.WeightedTotal,
		"height":          s.Chain.Tip().Index,
		"half_life_days":  s.HalfLifeDays,
	})
}

type transferRequest struct {
	FromUserID string  `json:"from_user_id"`
	ToUserID   string  `json:"to_user_id"`
	Amount     float64 `json:"amount"`
}

// handlePostTransfer implements POST /transfer per CONTRACT.md's "Peer
// transfer (buy/sell)" section: validate amount > 0 and the sender's
// current derived balance >= amount; if either fails, reject with 400
// without mutating the chain. Otherwise seal+sign a "transfer" tx (through
// the same signing/gossip pipeline as any other transaction, primary only
// — see rejectIfFollower) and return its height/hash.
func (s *Server) handlePostTransfer(w http.ResponseWriter, r *http.Request) {
	if s.rejectIfFollower(w) {
		return
	}

	var req transferRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body")
		return
	}
	if req.FromUserID == "" || req.ToUserID == "" {
		writeError(w, http.StatusBadRequest, "from_user_id and to_user_id are required")
		return
	}

	balance := state.Balance(s.Chain.Blocks(), req.FromUserID)
	if req.Amount <= 0 || balance < req.Amount {
		writeError(w, http.StatusBadRequest, "insufficient balance")
		return
	}

	tx := chain.Transaction{
		Type:       "transfer",
		FromUserID: req.FromUserID,
		ToUserID:   req.ToUserID,
		Amount:     req.Amount,
		Timestamp:  time.Now().UTC().Format(time.RFC3339),
	}

	block, err := s.Chain.SealAndAppend(tx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	if s.Node != nil {
		s.Node.BroadcastBlock(block, nil)
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"height": block.Index,
		"hash":   block.Hash,
	})
}

// handleGetChain implements GET /chain: full chain as a JSON array of
// blocks.
func (s *Server) handleGetChain(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.Chain.Blocks())
}

// handleGetPeers implements GET /peers: connected peer P2P addresses.
func (s *Server) handleGetPeers(w http.ResponseWriter, r *http.Request) {
	peers := []string{}
	if s.Node != nil {
		peers = s.Node.Peers()
	}
	writeJSON(w, http.StatusOK, peers)
}

// handleGetBalance implements GET /balance/{user_id}.
func (s *Server) handleGetBalance(w http.ResponseWriter, r *http.Request) {
	userID := r.PathValue("user_id")
	balance := state.Balance(s.Chain.Blocks(), userID)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"user_id": userID,
		"balance": balance,
	})
}

// handleGetHealth implements GET /health, per CONTRACT.md's "Roles &
// signing" section: role is "primary" or "follower"; pubkey is this
// node's own signing key's public half on a primary, or the configured
// -trusted-pubkey on a follower.
func (s *Server) handleGetHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"status": "ok",
		"height": s.Chain.Tip().Index,
		"role":   s.Role,
		"pubkey": s.PubKeyHex,
	})
}
