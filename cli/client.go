package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client talks to one aicoin-proxy. Nothing here is stateful: the wallet signs each request that
// needs signing, and tokens are minted per call rather than stored.
type Client struct {
	BaseURL string
	HTTP    *http.Client
}

func newClient(baseURL string, timeout time.Duration) *Client {
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		HTTP:    &http.Client{Timeout: timeout},
	}
}

// apiError is a non-2xx from the proxy, carrying whatever it said. The proxy's errors are the
// useful part of its API — "insufficient aicoin balance" with a balance, "token expired", the
// specific reason a signature failed — so they are surfaced verbatim rather than flattened.
type apiError struct {
	Status int
	Body   string
}

func (e *apiError) Error() string {
	body := strings.TrimSpace(e.Body)
	var parsed struct {
		Error   string   `json:"error"`
		Balance *float64 `json:"balance"`
	}
	if json.Unmarshal([]byte(body), &parsed) == nil && parsed.Error != "" {
		if parsed.Balance != nil {
			return fmt.Sprintf("%s (balance %s)", parsed.Error, formatCoins(*parsed.Balance))
		}
		return parsed.Error
	}
	if body == "" {
		return fmt.Sprintf("HTTP %d", e.Status)
	}
	return fmt.Sprintf("HTTP %d: %s", e.Status, body)
}

func (c *Client) do(method, path string, body []byte, headers map[string]string) ([]byte, http.Header, error) {
	var reader io.Reader
	if len(body) > 0 {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, c.BaseURL+path, reader)
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for name, value := range headers {
		req.Header.Set(name, value)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	responseBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return responseBody, resp.Header, &apiError{Status: resp.StatusCode, Body: string(responseBody)}
	}
	return responseBody, resp.Header, nil
}

// get fetches one of the proxy's public, unauthenticated endpoints (/price, /health, a balance).
func (c *Client) get(path string) ([]byte, error) {
	body, _, err := c.do(http.MethodGet, path, nil, nil)
	return body, err
}

// signed performs a wallet-management action — claim, transfer, revoke — which requires a live
// signature over this exact request rather than a token.
func (c *Client) signed(w *Wallet, method, path string, body []byte) ([]byte, error) {
	responseBody, _, err := c.do(method, path, body, w.signLive(method, path, body))
	return responseBody, err
}

// withToken performs a call that spends coins, authenticated by a freshly minted API token. The
// token is not stored: it exists for this call and expires on its own, which is the cheapest form
// of revocation there is.
func (c *Client) withToken(w *Wallet, method, path string, body []byte, extra map[string]string) ([]byte, http.Header, error) {
	token, err := w.token(time.Hour)
	if err != nil {
		return nil, nil, err
	}
	headers := map[string]string{"X-Api-Key": token}
	for name, value := range extra {
		headers[name] = value
	}
	return c.do(method, path, body, headers)
}

func (c *Client) balance(address string) (float64, error) {
	body, err := c.get("/wallet/api/balance/" + address)
	if err != nil {
		return 0, err
	}
	var parsed struct {
		Balance float64 `json:"balance"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return 0, err
	}
	return parsed.Balance, nil
}

// formatCoins prints a balance the way a wallet reads it: whole coins when it is whole, and at most
// two decimals otherwise. Metered billing charges whole coins, so most balances are integers.
func formatCoins(value float64) string {
	if value == float64(int64(value)) {
		return fmt.Sprintf("%d", int64(value))
	}
	return strings.TrimRight(strings.TrimRight(fmt.Sprintf("%.2f", value), "0"), ".")
}
