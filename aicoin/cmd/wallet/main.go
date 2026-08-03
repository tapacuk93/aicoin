// Command wallet is a small CLI client for the aicoin free-coin faucet and
// balance query, per CONTRACT.md's "Wallet CLI" section.
//
// Usage:
//
//	go run ./cmd/wallet -user=<id> [-node=http://localhost:9944] [-proxy=http://localhost:8080] [-balance-only]
//
// Default behavior:
//  1. GET {proxy}/free-coins/available -> {"available": N}.
//  2. If N > 0: POST {node}/free-coins/claim {"user_id": <id>}.
//     - granted:true  -> print the outcome and the new balance (via
//     GET {node}/balance/{user}).
//     - granted:false (429) -> print next_eligible_at.
//  3. If N == 0: print that no free coins are available right now.
//
// -balance-only skips the faucet entirely and just prints
// GET {node}/balance/{user}.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	user := flag.String("user", "", "user id (required)")
	node := flag.String("node", "http://localhost:9944", "aicoin node base URL")
	proxy := flag.String("proxy", "http://localhost:8080", "aicoin-proxy base URL")
	balanceOnly := flag.Bool("balance-only", false, "skip the faucet and just print current balance")
	flag.Parse()

	if strings.TrimSpace(*user) == "" {
		fmt.Fprintln(os.Stderr, "wallet: -user is required")
		os.Exit(1)
	}

	client := &http.Client{Timeout: 10 * time.Second}

	if *balanceOnly {
		if err := printBalance(client, *node, *user); err != nil {
			fmt.Fprintf(os.Stderr, "wallet: %v\n", err)
			os.Exit(1)
		}
		return
	}

	available, err := fetchAvailable(client, *proxy)
	if err != nil {
		fmt.Fprintf(os.Stderr, "wallet: checking free-coin availability: %v\n", err)
		os.Exit(1)
	}

	if !shouldAttemptClaim(available) {
		fmt.Println("No free coins available right now (proxy allowance is 0).")
		return
	}

	cr, err := postClaim(client, *node, *user)
	if err != nil {
		fmt.Fprintf(os.Stderr, "wallet: claiming free coin: %v\n", err)
		os.Exit(1)
	}

	if cr.Granted {
		fmt.Printf("Claimed 1 free aicoin! height=%d hash=%s next_eligible_at=%s\n", cr.Height, cr.Hash, cr.NextEligibleAt)
		if err := printBalance(client, *node, *user); err != nil {
			fmt.Fprintf(os.Stderr, "wallet: %v\n", err)
			os.Exit(1)
		}
	} else {
		fmt.Printf("Not eligible yet - next free coin at %s\n", cr.NextEligibleAt)
	}
}

// --- pure decision/parsing logic (unit-tested without any network) ---

// availableResponse mirrors GET {proxy}/free-coins/available's body.
type availableResponse struct {
	Available int `json:"available"`
}

// claimResponse mirrors both the 200 and 429 bodies of
// POST {node}/free-coins/claim.
type claimResponse struct {
	Granted        bool   `json:"granted"`
	Height         int    `json:"height"`
	Hash           string `json:"hash"`
	NextEligibleAt string `json:"next_eligible_at"`
}

// balanceResponse mirrors GET {node}/balance/{user}'s body.
type balanceResponse struct {
	UserID  string  `json:"user_id"`
	Balance float64 `json:"balance"`
}

// shouldAttemptClaim is the wallet's core decision: attempt the faucet
// claim iff the proxy currently allows at least one free coin.
func shouldAttemptClaim(available int) bool {
	return available > 0
}

func parseAvailable(body []byte) (availableResponse, error) {
	var r availableResponse
	if err := json.Unmarshal(body, &r); err != nil {
		return availableResponse{}, err
	}
	return r, nil
}

func parseClaim(body []byte) (claimResponse, error) {
	var r claimResponse
	if err := json.Unmarshal(body, &r); err != nil {
		return claimResponse{}, err
	}
	return r, nil
}

func parseBalance(body []byte) (balanceResponse, error) {
	var r balanceResponse
	if err := json.Unmarshal(body, &r); err != nil {
		return balanceResponse{}, err
	}
	return r, nil
}

// --- network glue ---

func fetchAvailable(client *http.Client, proxyBase string) (int, error) {
	resp, err := client.Get(strings.TrimRight(proxyBase, "/") + "/free-coins/available")
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, err
	}
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("proxy returned status %d: %s", resp.StatusCode, string(body))
	}
	r, err := parseAvailable(body)
	if err != nil {
		return 0, err
	}
	return r.Available, nil
}

func postClaim(client *http.Client, nodeBase, userID string) (claimResponse, error) {
	reqBody, err := json.Marshal(map[string]string{"user_id": userID})
	if err != nil {
		return claimResponse{}, err
	}
	resp, err := client.Post(strings.TrimRight(nodeBase, "/")+"/free-coins/claim", "application/json", strings.NewReader(string(reqBody)))
	if err != nil {
		return claimResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return claimResponse{}, err
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusTooManyRequests {
		return claimResponse{}, fmt.Errorf("node returned status %d: %s", resp.StatusCode, string(body))
	}
	return parseClaim(body)
}

func fetchBalance(client *http.Client, nodeBase, userID string) (balanceResponse, error) {
	resp, err := client.Get(strings.TrimRight(nodeBase, "/") + "/balance/" + userID)
	if err != nil {
		return balanceResponse{}, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return balanceResponse{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return balanceResponse{}, fmt.Errorf("node returned status %d: %s", resp.StatusCode, string(body))
	}
	return parseBalance(body)
}

func printBalance(client *http.Client, nodeBase, userID string) error {
	br, err := fetchBalance(client, nodeBase, userID)
	if err != nil {
		return fmt.Errorf("fetching balance: %w", err)
	}
	fmt.Printf("Balance for %s: %v\n", br.UserID, br.Balance)
	return nil
}
