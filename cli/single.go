package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Single mode: one model, one call, the same directory and the same ability to change files.
//
// A consortium is one paid call per panelist per round. In a large directory that is the right
// order of magnitude for a question worth reviewing and the wrong one for "create an empty file" —
// which is how a wallet ends a session at zero. Single mode is the same client with the panel
// switched off: one call, one answer, still grounded in the directory and still able to propose
// changes.
//
// Which model it uses is not a matter of taste: it is whichever has carried the most turns here,
// measured from the consortium responses this CLI has already seen. See stats.go for what that
// does and does not claim.

// singleSystem is the brief for a lone model. It carries the directory and the action protocol —
// the two things that make an answer about this directory rather than about directories in general.
func singleSystem(background string) string {
	return "You are answering a request from a working directory you have been given. Be concrete and" +
		" correct, ground the answer in the material below rather than in general knowledge, and say" +
		" plainly where the material does not settle the question instead of filling the gap with" +
		" something plausible. Be brief unless the request calls for length.\n\n" + background
}

// chooseSingleProvider decides which model answers, and says why. `health` is the proxy's own list,
// so a model with no key configured is never chosen even if the local record likes it.
func chooseSingleProvider(record *stats, enabled []string) (provider string, why string, err error) {
	if len(enabled) == 0 {
		return "", "", fmt.Errorf("no provider is configured on this proxy")
	}
	usable := map[string]bool{}
	for _, name := range enabled {
		usable[name] = true
	}
	if candidate, reason := record.best(); candidate != "" && usable[candidate] {
		return candidate, reason, nil
	}
	// Nothing measured yet — or what was measured is not available here. Any configured chat
	// provider will do to start with, and the record will decide from the next call onwards.
	for _, name := range chatProviders {
		if usable[name] {
			return name, "no history yet — measuring from here", nil
		}
	}
	return enabled[0], "no history yet — measuring from here", nil
}

// chatProviders is the order the proxy itself puts its chat-capable providers in — the same list
// and the same order as ChatAdapter.CHAT_PROVIDERS on the other side, so a panel's default order
// and this CLI's fallback agree. Every entry needs a defaultModels entry to be usable.
var chatProviders = []string{"anthropic", "openai", "google", "mistral", "kimi"}

// pinProvider validates a model name the user asked to pin, and returns it normalised.
func pinProvider(name string) (string, error) {
	pinned := strings.ToLower(strings.TrimSpace(name))
	if !contains(chatProviders, pinned) {
		return "", fmt.Errorf("%q is not a model this proxy can chat with (%s)",
			pinned, strings.Join(chatProviders, ", "))
	}
	return pinned, nil
}

// enabledProviders asks the proxy which providers actually have a key.
func enabledProviders(client *Client) ([]string, error) {
	body, err := client.get("/health")
	if err != nil {
		return nil, err
	}
	var parsed struct {
		Providers []struct {
			Name    string `json:"name"`
			Enabled bool   `json:"enabled"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return nil, err
	}
	var enabled []string
	for _, provider := range parsed.Providers {
		if provider.Enabled && contains(chatProviders, provider.Name) {
			enabled = append(enabled, provider.Name)
		}
	}
	return enabled, nil
}

func contains(list []string, value string) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}

// askOne runs a single-model call: the directory and the action protocol as the system brief, the
// question as the message, and the same delivery — including proposed file changes — as a
// consortium answer gets.
// Returns what the call was charged, so a session can keep its running total.
func askOne(client *Client, wallet *Wallet, record *stats, provider, model, background, question string,
	root string, auto bool, confirm func(string) bool) (int64, error) {

	path, body, err := chatBody(provider, model, singleSystem(background), question, 4000)
	if err != nil {
		return 0, err
	}
	requestHeaders := chatHeaders(provider)
	requestHeaders["X-AI"] = provider
	startBalance, _ := client.balance(wallet.Address)
	price, _ := client.priceUSD()
	meter := startCoinMeter(client, wallet.Address, startBalance, price)
	responseBody, headers, err := client.withToken(wallet, "POST", path, body, requestHeaders)
	meter.finish()
	if err != nil {
		// Only count this against the model if the model is what failed. A refused wallet, an
		// expired token or a request this CLI got wrong never reached it, and marking those as its
		// failures would push single mode away from a model that has done nothing wrong.
		if isProviderFailure(err) {
			record.recordSingle(provider, false, 0)
			_ = record.save()
		}
		return 0, err
	}
	text := chatText(provider, responseBody)
	charged := headers.Get("X-Aicoin-Charged")
	coins, _ := strconv.ParseInt(charged, 10, 64)
	record.recordSingle(provider, text != "", coins)
	_ = record.save()
	if text == "" {
		// Paid for, and unreadable: better the raw body than nothing.
		fmt.Println(strings.TrimSpace(string(responseBody)))
	} else if root == "" {
		fmt.Println(text)
	} else {
		deliverAnswer(root, text, auto, confirm)
	}
	if charged == "" {
		return 0, nil
	}
	if balance, balErr := client.balance(wallet.Address); balErr == nil {
		fmt.Fprintf(os.Stderr, "\n%s · %s aicoin%s · %s left%s\n", provider, charged,
			bracketed(usd(float64(coins), price)), formatCoins(balance), bracketed(usd(balance, price)))
	} else {
		fmt.Fprintf(os.Stderr, "\n%s · %s aicoin%s\n", provider, charged, bracketed(usd(float64(coins), price)))
	}
	return coins, nil
}

// isProviderFailure separates "the model did not answer" from "we never got that far".
//
// 5xx and 429 are the provider: it was reached and it did not deliver. A transport error or a
// timeout is the same. Everything else in the 4xx range is this side — an empty wallet, a token
// that expired, a request built wrong — and says nothing about the model.
func isProviderFailure(err error) bool {
	var apiErr *apiError
	if errors.As(err, &apiErr) {
		return apiErr.Status == 429 || apiErr.Status >= 500
	}
	return true
}

func cmdSingle(args []string) error {
	fs := flag.NewFlagSet("single", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	record := loadStats(*walletPath)
	record.Mode = modeSingle
	if rest := positional(fs); len(rest) > 0 {
		pinned, err := pinProvider(rest[0])
		if err != nil {
			return err
		}
		record.SingleProvider = pinned
	} else {
		// Un-pin: an unqualified `aicoin single` means "whichever is carrying the work", not
		// "whatever I pinned three weeks ago".
		record.SingleProvider = ""
	}
	if err := record.save(); err != nil {
		return err
	}
	enabled, err := enabledProviders(newClient(*url, 30*time.Second))
	if err != nil {
		fmt.Fprintln(os.Stderr, "single mode on")
		return nil
	}
	provider, why, err := chooseSingleProvider(record, enabled)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "single mode on — %s (%s)\n", provider, why)
	fmt.Fprintln(os.Stderr, "one call per question instead of one per panelist per round. `aicoin multi` to go back.")
	return nil
}

func cmdMulti(args []string) error {
	fs := flag.NewFlagSet("multi", flag.ExitOnError)
	_, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	record := loadStats(*walletPath)
	record.Mode = modeMulti
	if err := record.save(); err != nil {
		return err
	}
	fmt.Fprintln(os.Stderr, "consortium mode on — every model answers, then reviews the answer until nobody objects")
	return nil
}

// cmdAis prints which models have been used, what each cost, and what each failed.
func cmdAis(args []string) error {
	fs := flag.NewFlagSet("ais", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	record := loadStats(*walletPath)
	price, _ := newClient(*url, 30*time.Second).priceUSD()
	fmt.Print(record.render(price))
	fmt.Fprintf(os.Stderr, "\nmode: %s\n", record.Mode)
	return nil
}
