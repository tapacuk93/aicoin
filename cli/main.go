// Command aicoin is the command-line wallet and client for an aicoin-proxy.
//
// It does everything the browser wallet page does — create a wallet, check a balance, claim from
// the faucet, transfer, issue and revoke API tokens — plus the two things a terminal is actually
// better at: making AI calls through the proxy, and running a consortium call, where every
// configured model answers one request and then reviews the answer until nobody objects.
//
// The wallet file is the wallet. It holds an Ed25519 seed, it is written 0600, and there is no
// server-side copy: lose it and the coins are gone.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const defaultProxyURL = "https://proxy.aicoin.oeaio.com"

const usage = `aicoin — command-line wallet and AI client for an aicoin-proxy

  aicoin "<question>"              ask the whole panel: every model answers, then they
                                   review the answer until nobody objects. Files in the
                                   current directory are listed for them; -f adds contents.
  aicoin .                         open a session on this directory: ask, follow up, and
                                   the panel remembers the exchange. /help once inside.

  Wallet
    aicoin new [-force]              create a wallet (refuses to overwrite without -force)
    aicoin show                      address and balance
    aicoin import -key <hex>         adopt an existing key
    aicoin export                    print the private key (prompts unless -y)
    aicoin balance [address]         a balance; yours if no address is given
    aicoin claim                     take the free-coin faucet's grant
    aicoin send <address> <amount>   transfer coins to another wallet
    aicoin token [-days N]           issue an API token to use elsewhere
    aicoin revoke                    invalidate every token issued so far

  Calls (these spend coins)
    aicoin consortium [flags] <prompt>         the same thing, spelled out
      -f <glob>       include a file's contents (repeatable: -f "*.go" -f README.md)
      -dir <path>     which directory the panel sees (default: this one; -dir "" for none)
      -providers a,b  narrow the panel      -rounds N   cap the review rounds
      -v              show each round's comments        -json  the raw response
      -y              apply proposed file changes without asking first
    aicoin ask [-ai p] [-model m] <prompt>     one model, one answer
    aicoin call -ai <p> <path> [-data <json>]  raw pass-through to a provider's own API
    aicoin session [dir]                       the same as "aicoin ."

  Mode
    aicoin single [provider]         one model per question instead of the whole panel;
                                     with no name, whichever has carried the most work here
    aicoin multi                     back to the panel
    aicoin ais                       which models have been used, what they cost, what they failed

  Proxy
    aicoin price                     what one aicoin currently costs
    aicoin health                    which providers are configured and healthy

Common flags: -url <proxy>  (or $AICOIN_PROXY_URL, default ` + defaultProxyURL + `)
              -wallet <path> (or $AICOIN_WALLET, default ~/.aicoin/wallet.json)
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	command := os.Args[1]
	args := os.Args[2:]

	var err error
	switch command {
	case "new":
		err = cmdNew(args)
	case "show":
		err = cmdShow(args)
	case "import":
		err = cmdImport(args)
	case "export":
		err = cmdExport(args)
	case "balance":
		err = cmdBalance(args)
	case "claim":
		err = cmdClaim(args)
	case "send":
		err = cmdSend(args)
	case "token":
		err = cmdToken(args)
	case "revoke":
		err = cmdRevoke(args)
	case "price":
		err = cmdPrice(args)
	case "health":
		err = cmdHealth(args)
	case "ask":
		err = cmdAsk(args)
	case "consortium":
		err = cmdConsortium(args)
	case "call":
		err = cmdCall(args)
	case "help", "-h", "--help":
		fmt.Print(usage)
		return
	case "session":
		err = cmdSession(args)
	case "single":
		err = cmdSingle(args)
	case "multi":
		err = cmdMulti(args)
	case "ais", "stats":
		err = cmdAis(args)
	default:
		if looksLikeDir(command) {
			// `aicoin .` — open a session on that directory rather than asking a question whose
			// text happens to be a path.
			err = cmdSession(os.Args[1:])
			break
		}
		// No command word: the whole line is a question for the panel. `aicoin "why does this
		// build fail?"` is the thing this CLI is for, and making people type `consortium` first
		// only buys them a longer way to say it.
		err = cmdConsortium(os.Args[1:])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "aicoin: "+err.Error())
		os.Exit(1)
	}
}

// stringList collects a flag given more than once: -f "*.go" -f README.md.
type stringList []string

func (l *stringList) String() string { return strings.Join(*l, ",") }

func (l *stringList) Set(value string) error {
	// A comma-separated single use is what people try first, so accept both forms.
	for _, part := range strings.Split(value, ",") {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			*l = append(*l, trimmed)
		}
	}
	return nil
}

// common registers the flags every command shares and returns accessors for them.
func common(fs *flag.FlagSet) (url *string, wallet *string) {
	defaultURL := os.Getenv("AICOIN_PROXY_URL")
	if defaultURL == "" {
		defaultURL = defaultProxyURL
	}
	defaultWallet := os.Getenv("AICOIN_WALLET")
	if defaultWallet == "" {
		home, err := os.UserHomeDir()
		if err == nil {
			defaultWallet = filepath.Join(home, ".aicoin", "wallet.json")
		}
	}
	url = fs.String("url", defaultURL, "aicoin-proxy base URL")
	wallet = fs.String("wallet", defaultWallet, "wallet file")
	return url, wallet
}

// parse reads flags that appear anywhere, not only before the first positional argument. Go's
// flag package stops at the first non-flag word, which for this CLI means `aicoin consortium "..."
// -v` would silently ignore -v — a flag that appears to have been accepted and did nothing is
// worse than one that errors. positional() returns the words that were not flags.
func parse(fs *flag.FlagSet, args []string) error {
	fs.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: aicoin %s [flags] <args>\n", fs.Name())
		fs.PrintDefaults()
	}
	var rest []string
	for {
		if err := fs.Parse(args); err != nil {
			return err
		}
		if fs.NArg() == 0 {
			break
		}
		rest = append(rest, fs.Arg(0))
		args = fs.Args()[1:]
	}
	positionals[fs] = rest
	return nil
}

// positionals holds each command's non-flag arguments, gathered by parse.
var positionals = map[*flag.FlagSet][]string{}

func positional(fs *flag.FlagSet) []string {
	return positionals[fs]
}

func cmdNew(args []string) error {
	fs := flag.NewFlagSet("new", flag.ExitOnError)
	url, walletPath := common(fs)
	force := fs.Bool("force", false, "overwrite an existing wallet file")
	if err := parse(fs, args); err != nil {
		return err
	}
	if _, err := os.Stat(*walletPath); err == nil && !*force {
		// Overwriting a wallet destroys every coin in it, irrecoverably: there is no server-side
		// copy of the key and no recovery phrase.
		return fmt.Errorf("a wallet already exists at %s — `aicoin show` to see it, -force to replace it "+
			"(which destroys the old key and any coins it holds)", *walletPath)
	}
	wallet, err := newWallet()
	if err != nil {
		return err
	}
	if err := wallet.save(*walletPath); err != nil {
		return err
	}
	fmt.Println(wallet.Address)
	fmt.Fprintf(os.Stderr, "wallet written to %s (keep it: it is the only copy of the key)\n", *walletPath)
	fmt.Fprintf(os.Stderr, "claim your first coins with: aicoin claim -url %s\n", *url)
	return nil
}

func cmdShow(args []string) error {
	fs := flag.NewFlagSet("show", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	fmt.Printf("address  %s\n", wallet.Address)
	balance, err := newClient(*url, 30*time.Second).balance(wallet.Address)
	if err != nil {
		fmt.Printf("balance  unknown (%v)\n", err)
		return nil
	}
	fmt.Printf("balance  %s aicoin\n", formatCoins(balance))
	return nil
}

func cmdImport(args []string) error {
	fs := flag.NewFlagSet("import", flag.ExitOnError)
	_, walletPath := common(fs)
	key := fs.String("key", "", "private key or seed, hex")
	force := fs.Bool("force", false, "overwrite an existing wallet file")
	if err := parse(fs, args); err != nil {
		return err
	}
	if *key == "" {
		return fmt.Errorf("-key is required")
	}
	if _, err := os.Stat(*walletPath); err == nil && !*force {
		return fmt.Errorf("a wallet already exists at %s — -force to replace it", *walletPath)
	}
	wallet, err := walletFromSeed(*key)
	if err != nil {
		return err
	}
	if err := wallet.save(*walletPath); err != nil {
		return err
	}
	fmt.Println(wallet.Address)
	return nil
}

func cmdExport(args []string) error {
	fs := flag.NewFlagSet("export", flag.ExitOnError)
	_, walletPath := common(fs)
	yes := fs.Bool("y", false, "print the key without asking")
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	if !*yes {
		// Printing a key puts it in the terminal's scrollback and the shell's history of whatever
		// reads it. Worth one deliberate keystroke.
		fmt.Fprint(os.Stderr, "This prints the private key for "+wallet.Address+
			".\nAnyone who sees it owns the wallet. Continue? [y/N] ")
		var answer string
		fmt.Scanln(&answer)
		if !strings.EqualFold(strings.TrimSpace(answer), "y") {
			return fmt.Errorf("cancelled")
		}
	}
	fmt.Println(wallet.Seed)
	return nil
}

func cmdBalance(args []string) error {
	fs := flag.NewFlagSet("balance", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	args = positional(fs)
	address := ""
	if len(args) > 0 {
		address = args[0]
	}
	if address == "" {
		wallet, err := loadWallet(*walletPath)
		if err != nil {
			return err
		}
		address = wallet.Address
	}
	balance, err := newClient(*url, 30*time.Second).balance(address)
	if err != nil {
		return err
	}
	fmt.Println(formatCoins(balance))
	return nil
}

func cmdClaim(args []string) error {
	fs := flag.NewFlagSet("claim", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	amount, balance, err := claimCoins(newClient(*url, 30*time.Second), wallet)
	if err != nil {
		return err
	}
	fmt.Printf("claimed %s aicoin — balance %s\n", formatCoins(amount), formatCoins(balance))
	return nil
}

// claimCoins takes the faucet's grant and reports what it granted and what the wallet then holds.
// Shared with a session's /claim, which is what an empty wallet mid-session wants.
func claimCoins(client *Client, wallet *Wallet) (amount float64, balance float64, err error) {
	body, callErr := client.signed(wallet, "POST", "/wallet/api/claim", nil)
	var parsed struct {
		Granted        bool    `json:"granted"`
		Amount         float64 `json:"amount"`
		Reason         string  `json:"reason"`
		NextEligibleAt string  `json:"next_eligible_at"`
	}
	// A refused claim is a 429 whose body says why — a cooldown that has not elapsed, or a pool
	// with nothing left in it. Neither is an error worth a stack of JSON at the user.
	if json.Unmarshal(body, &parsed) == nil && !parsed.Granted && parsed.Reason != "" {
		switch parsed.Reason {
		case "cooldown":
			return 0, 0, fmt.Errorf("already claimed recently — next claim allowed at %s", parsed.NextEligibleAt)
		case "pool_exhausted":
			return 0, 0, fmt.Errorf("the free-coin pool is empty right now")
		default:
			return 0, 0, fmt.Errorf("claim refused: %s", parsed.Reason)
		}
	}
	if callErr != nil {
		return 0, 0, callErr
	}
	// The claim response says what was granted, not what the wallet now holds; the balance is one
	// more read, and it is the number the user actually wanted.
	balance, balanceErr := client.balance(wallet.Address)
	if balanceErr != nil {
		return parsed.Amount, parsed.Amount, nil
	}
	return parsed.Amount, balance, nil
}

func cmdSend(args []string) error {
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	args = positional(fs)
	if len(args) != 2 {
		return fmt.Errorf("usage: aicoin send <address> <amount>")
	}
	to := args[0]
	amount, err := strconv.ParseFloat(args[1], 64)
	if err != nil || amount <= 0 {
		return fmt.Errorf("amount must be a positive number")
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	payload, err := json.Marshal(map[string]any{"to_user_id": to, "amount": amount})
	if err != nil {
		return err
	}
	if _, err := newClient(*url, 30*time.Second).signed(wallet, "POST", "/wallet/api/transfer", payload); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "sent %s aicoin to %s\n", formatCoins(amount), to)
	return nil
}

func cmdToken(args []string) error {
	fs := flag.NewFlagSet("token", flag.ExitOnError)
	_, walletPath := common(fs)
	days := fs.Int("days", 7, "how long the token stays valid")
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	token, err := wallet.token(time.Duration(*days) * 24 * time.Hour)
	if err != nil {
		return err
	}
	fmt.Println(token)
	fmt.Fprintf(os.Stderr, "valid %d days — send it as X-Api-Key. It can spend this wallet's coins "+
		"on AI calls, but cannot transfer them. `aicoin revoke` invalidates it.\n", *days)
	return nil
}

func cmdRevoke(args []string) error {
	fs := flag.NewFlagSet("revoke", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	if _, err := newClient(*url, 30*time.Second).signed(wallet, "POST", "/wallet/api/revoke-tokens", nil); err != nil {
		return err
	}
	fmt.Fprintln(os.Stderr, "every token issued before now is now invalid")
	return nil
}

func cmdPrice(args []string) error {
	fs := flag.NewFlagSet("price", flag.ExitOnError)
	url, _ := common(fs)
	asJSON := fs.Bool("json", false, "print the proxy's response verbatim")
	if err := parse(fs, args); err != nil {
		return err
	}
	body, err := newClient(*url, 30*time.Second).get("/price")
	if err != nil {
		return err
	}
	if *asJSON {
		fmt.Println(strings.TrimSpace(string(body)))
		return nil
	}
	var parsed struct {
		PriceUSD      float64 `json:"price_usd"`
		TotalSpendUSD float64 `json:"total_spend_usd"`
		WeightedTotal float64 `json:"weighted_total"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return err
	}
	fmt.Printf("1 aicoin ≈ $%.6f  (from $%.2f of recorded calls, weighted %.1f)\n",
		parsed.PriceUSD, parsed.TotalSpendUSD, parsed.WeightedTotal)
	return nil
}

func cmdHealth(args []string) error {
	fs := flag.NewFlagSet("health", flag.ExitOnError)
	url, _ := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	body, err := newClient(*url, 30*time.Second).get("/health")
	if err != nil {
		return err
	}
	var parsed struct {
		Providers []struct {
			Name        string `json:"name"`
			Enabled     bool   `json:"enabled"`
			Healthy     bool   `json:"healthy"`
			RateLimited bool   `json:"rateLimited"`
			OverBudget  bool   `json:"overBudget"`
		} `json:"providers"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return err
	}
	for _, provider := range parsed.Providers {
		state := "ok"
		switch {
		case !provider.Enabled:
			state = "no key configured"
		case provider.RateLimited:
			state = "rate limited"
		case provider.OverBudget:
			state = "over budget"
		}
		fmt.Printf("%-12s %s\n", provider.Name, state)
	}
	return nil
}

// chatBody mirrors the proxy's own per-provider chat shapes (see ChatAdapter). `ask` sends a
// provider's real request to a provider's real path — the proxy forwards it untouched — so the
// shape has to be that provider's, not a shape of this CLI's invention.
func chatBody(provider, model, system, prompt string, maxTokens int) (path string, body []byte, err error) {
	switch provider {
	case "anthropic":
		request := map[string]any{
			"model":      model,
			"max_tokens": maxTokens,
			"messages":   []map[string]string{{"role": "user", "content": prompt}},
		}
		if system != "" {
			request["system"] = system
		}
		body, err = json.Marshal(request)
		return "/v1/messages", body, err
	case "google":
		request := map[string]any{
			"contents":         []map[string]any{{"role": "user", "parts": []map[string]string{{"text": prompt}}}},
			"generationConfig": map[string]any{"maxOutputTokens": maxTokens},
		}
		if system != "" {
			request["systemInstruction"] = map[string]any{"parts": []map[string]string{{"text": system}}}
		}
		body, err = json.Marshal(request)
		return "/v1beta/models/" + model + ":generateContent", body, err
	default:
		// OpenAI-compatible: OpenAI itself, Mistral, Kimi. OpenAI's newer models take the cap
		// under its newer name and reject the old one.
		capField := "max_tokens"
		if provider == "openai" {
			capField = "max_completion_tokens"
		}
		messages := []map[string]string{}
		if system != "" {
			messages = append(messages, map[string]string{"role": "system", "content": system})
		}
		messages = append(messages, map[string]string{"role": "user", "content": prompt})
		body, err = json.Marshal(map[string]any{
			"model":    model,
			capField:   maxTokens,
			"messages": messages,
		})
		return "/v1/chat/completions", body, err
	}
}

// chatHeaders are the headers a provider requires beyond auth, which the proxy forwards untouched.
// Anthropic rejects a Messages API call without a version header outright — the proxy's own
// consortium turns set it, and a request this CLI composes has to set it too.
func chatHeaders(provider string) map[string]string {
	headers := map[string]string{}
	if provider == "anthropic" {
		headers["anthropic-version"] = "2023-06-01"
	}
	return headers
}

// chatText pulls the assistant's words out of a provider's own response shape.
func chatText(provider string, body []byte) string {
	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		return ""
	}
	switch provider {
	case "anthropic":
		blocks, _ := parsed["content"].([]any)
		var out strings.Builder
		for _, blockAny := range blocks {
			block, _ := blockAny.(map[string]any)
			if block["type"] == "text" {
				text, _ := block["text"].(string)
				out.WriteString(text)
			}
		}
		return out.String()
	case "google":
		candidates, _ := parsed["candidates"].([]any)
		if len(candidates) == 0 {
			return ""
		}
		candidate, _ := candidates[0].(map[string]any)
		content, _ := candidate["content"].(map[string]any)
		parts, _ := content["parts"].([]any)
		var out strings.Builder
		for _, partAny := range parts {
			part, _ := partAny.(map[string]any)
			if thought, _ := part["thought"].(bool); thought {
				continue // the model's scratchpad, not its answer
			}
			text, _ := part["text"].(string)
			out.WriteString(text)
		}
		return out.String()
	default:
		choices, _ := parsed["choices"].([]any)
		if len(choices) == 0 {
			return ""
		}
		choice, _ := choices[0].(map[string]any)
		message, _ := choice["message"].(map[string]any)
		text, _ := message["content"].(string)
		return text
	}
}

var defaultModels = map[string]string{
	"anthropic": "claude-sonnet-5",
	"openai":    "gpt-5",
	"google":    "gemini-3.5-flash",
	"mistral":   "mistral-large-latest",
	"kimi":      "kimi-k2.6",
}

func cmdAsk(args []string) error {
	fs := flag.NewFlagSet("ask", flag.ExitOnError)
	url, walletPath := common(fs)
	provider := fs.String("ai", "anthropic", "provider to ask (anthropic|openai|google|mistral|kimi)")
	model := fs.String("model", "", "model id (default: this provider's current one)")
	maxTokens := fs.Int("max-tokens", 4000, "output cap")
	if err := parse(fs, args); err != nil {
		return err
	}
	prompt := strings.TrimSpace(strings.Join(positional(fs), " "))
	if prompt == "" {
		if piped, err := readStdin(); err == nil {
			prompt = piped
		}
	}
	if prompt == "" {
		return fmt.Errorf("usage: aicoin ask [-ai provider] <prompt>  (or pipe the prompt in)")
	}
	chosenModel := *model
	if chosenModel == "" {
		chosenModel = defaultModels[*provider]
		if chosenModel == "" {
			return fmt.Errorf("no default model for %q — pass -model", *provider)
		}
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	path, body, err := chatBody(*provider, chosenModel, "", prompt, *maxTokens)
	if err != nil {
		return err
	}
	requestHeaders := chatHeaders(*provider)
	requestHeaders["X-AI"] = *provider
	responseBody, headers, err := newClient(*url, 5*time.Minute).
		withToken(wallet, "POST", path, body, requestHeaders)
	if err != nil {
		return err
	}
	text := chatText(*provider, responseBody)
	if text == "" {
		// Better the raw body than nothing: an answer this CLI can't read is still an answer the
		// wallet paid for.
		fmt.Println(strings.TrimSpace(string(responseBody)))
	} else {
		fmt.Println(text)
	}
	reportCharge(newClient(*url, 30*time.Second), wallet, headers.Get("X-Aicoin-Charged"))
	return nil
}

// reportCharge says what a single call took and what the wallet has left. The charge is in the
// response header; the remainder costs one more read, which is worth it — "1 aicoin" alone does
// not tell anyone whether they are about to run out.
func reportCharge(client *Client, wallet *Wallet, charged string) {
	if charged == "" {
		return
	}
	if balance, err := client.balance(wallet.Address); err == nil {
		fmt.Fprintf(os.Stderr, "%s aicoin · %s left\n", charged, formatCoins(balance))
		return
	}
	fmt.Fprintf(os.Stderr, "%s aicoin\n", charged)
}

// consortiumResult is the proxy's /consortium response. Shared with the interactive session,
// which shows the same numbers turn by turn.
type consortiumResult struct {
	Answer        string   `json:"answer"`
	Settled       bool     `json:"settled"`
	StoppedReason string   `json:"stopped_reason"`
	Rounds        int      `json:"rounds"`
	Panel         []string `json:"panel"`
	Editor        string   `json:"editor"`
	Mode          string   `json:"mode"`
	Calls         int      `json:"calls"`
	CoinsCharged  int64    `json:"coins_charged"`
	// Spend breaks the total down by provider. Only the proxy can: it settles each turn against
	// that provider's own reported usage.
	Spend   map[string]int64 `json:"spend"`
	Reviews []struct {
		Round    int    `json:"round"`
		Provider string `json:"provider"`
		Clean    bool   `json:"clean"`
		Comments string `json:"comments"`
	} `json:"reviews"`
	Errors []struct {
		Stage    string `json:"stage"`
		Provider string `json:"provider"`
		Error    string `json:"error"`
	} `json:"errors"`
}

func parseConsortium(body []byte) (*consortiumResult, error) {
	var result consortiumResult
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// howItWent is the one-line summary printed after a consortium call: whether it settled, how many
// rounds and calls it took, and which shape it ran in.
func howItWent(result *consortiumResult) string {
	outcome := "stopped: " + result.StoppedReason
	if result.Settled {
		outcome = "settled — a whole round with no comments"
	}
	shape := "editor " + result.Editor
	switch result.Mode {
	case "lead":
		shape = "led by " + result.Editor
	case "panel":
		shape = "drafted by the panel, merged by " + result.Editor
	}
	return fmt.Sprintf("%s | %d round(s), %d calls | panel %s, %s",
		outcome, result.Rounds, result.Calls, strings.Join(result.Panel, ","), shape)
}

func cmdConsortium(args []string) error {
	fs := flag.NewFlagSet("consortium", flag.ExitOnError)
	url, walletPath := common(fs)
	providers := fs.String("providers", "", "comma-separated panel (default: every configured model)")
	editor := fs.String("editor", "", "which model merges and revises (default: the first panelist)")
	rounds := fs.Int("rounds", 0, "cap the review rounds (may only lower the proxy's own cap)")
	context := fs.String("context", "", "extra background every panelist sees; @file reads a file")
	dir := fs.String("dir", ".", "directory the panel is shown; empty for none")
	var include stringList
	fs.Var(&include, "f", "include this file's contents (glob; repeatable)")
	budget := fs.Int("budget", 40000, "how many characters of directory context to send at most")
	mode := fs.String("mode", "auto", "auto|lead|panel — who writes the first answer")
	auto := fs.Bool("y", false, "apply proposed file changes without asking")
	verbose := fs.Bool("v", false, "also print each round's comments")
	asJSON := fs.Bool("json", false, "print the proxy's response verbatim")
	if err := parse(fs, args); err != nil {
		return err
	}
	prompt := strings.TrimSpace(strings.Join(positional(fs), " "))
	if prompt == "" {
		if piped, err := readStdin(); err == nil {
			prompt = piped
		}
	}
	if prompt == "" {
		return fmt.Errorf("usage: aicoin consortium [flags] <prompt>  (or pipe the prompt in)")
	}
	background, err := maybeFile(*context)
	if err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	record := loadStats(*walletPath)

	// What the panel is told about where this was run. The listing goes by default because a
	// question asked inside a project is nearly always about that project; contents are opt-in,
	// because every character of them is billed to every panelist on every round.
	root := *dir
	if root != "" {
		gathered, dirErr := gatherDir(root, include, *budget)
		if dirErr != nil {
			return dirErr
		}
		// The protocol goes with the directory: a panel that can see files should be able to
		// propose changes to them rather than describe the changes it would make.
		background = strings.TrimSpace(gathered.Text + "\n\n" + actionProtocol + "\n\n" + background)
		note := fmt.Sprintf("context: %d files listed", gathered.Listed)
		if gathered.FileCount > 0 {
			note += fmt.Sprintf(", %d included in full", gathered.FileCount)
		}
		if gathered.Truncated {
			note += " (trimmed to fit)"
		}
		fmt.Fprintf(os.Stderr, "%s, %d chars\n", note, gathered.Chars)
	}

	// `aicoin single` switches this CLI to one model per question. An explicit -mode means the
	// caller wants a consortium regardless, so it wins.
	if record.Mode == modeSingle && *mode == "auto" {
		client := newClient(*url, 10*time.Minute)
		provider := ""
		why := "pinned for this call"
		if *providers != "" {
			provider = strings.TrimSpace(strings.Split(*providers, ",")[0])
		}
		if provider == "" {
			enabled, healthErr := enabledProviders(client)
			if healthErr != nil {
				return healthErr
			}
			provider, why, err = chooseSingleProvider(record, enabled)
			if err != nil {
				return err
			}
		}
		model := defaultModels[provider]
		if model == "" {
			return fmt.Errorf("no default model for %q", provider)
		}
		fmt.Fprintf(os.Stderr, "single mode · %s (%s)\n", provider, why)
		_, err = askOne(client, wallet, record, provider, model, background, prompt, root, *auto, confirmOnStdin)
		return err
	}

	request := map[string]any{"prompt": prompt}
	if background != "" {
		request["context"] = background
	}
	if *providers != "" {
		var panel []string
		for _, name := range strings.Split(*providers, ",") {
			if trimmed := strings.TrimSpace(name); trimmed != "" {
				panel = append(panel, trimmed)
			}
		}
		request["providers"] = panel
	}
	if *editor != "" {
		request["editor"] = *editor
	}
	if *rounds > 0 {
		request["max_rounds"] = *rounds
	}
	if *mode != "" && *mode != "auto" {
		request["mode"] = *mode
	}
	body, err := json.Marshal(request)
	if err != nil {
		return err
	}

	client := newClient(*url, 30*time.Minute)
	// The balance before, so the cost of this call can be stated afterwards rather than left for
	// the user to work out from two `aicoin show`s.
	balanceBefore, balanceErr := client.balance(wallet.Address)

	// Nothing comes back until every round is done, so the wallet is what there is to watch: it
	// drops as each turn settles, which shows both that the call is moving and what it is costing.
	meter := startCoinMeter(client, wallet.Address, balanceBefore)
	// Long, because it is: a full consortium is one call per panelist per round, run to
	// completion before anything comes back.
	responseBody, _, err := client.withToken(wallet, "POST", "/consortium", body, nil)
	meter.finish()
	if err != nil {
		return err
	}
	if *asJSON {
		fmt.Println(strings.TrimSpace(string(responseBody)))
		return nil
	}
	parsed, err := parseConsortium(responseBody)
	if err != nil {
		fmt.Println(strings.TrimSpace(string(responseBody)))
		return nil
	}

	// The answer alone on stdout, so `aicoin consortium ... > answer.md` is the answer and nothing
	// else. Everything about how it got there goes to stderr — including anything it proposes to
	// write, which is shown and confirmed rather than printed.
	if root == "" {
		fmt.Println(parsed.Answer)
	} else {
		deliverAnswer(root, parsed.Answer, *auto, confirmOnStdin)
	}

	if *verbose {
		for _, review := range parsed.Reviews {
			if review.Clean {
				fmt.Fprintf(os.Stderr, "\nround %d — %s: no comments\n", review.Round, review.Provider)
				continue
			}
			fmt.Fprintf(os.Stderr, "\nround %d — %s:\n%s\n", review.Round, review.Provider, review.Comments)
		}
	}
	reportFailures(parsed)
	record.recordConsortium(parsed)
	_ = record.save()
	fmt.Fprintf(os.Stderr, "\n%s\n", howItWent(parsed))
	if balanceErr == nil {
		balanceAfter, afterErr := client.balance(wallet.Address)
		if afterErr == nil {
			fmt.Fprintln(os.Stderr, coinBar(balanceBefore, balanceAfter, parsed.CoinsCharged))
			return nil
		}
	}
	fmt.Fprintf(os.Stderr, "%s aicoin spent\n", formatCoins(float64(parsed.CoinsCharged)))
	return nil
}

// reportFailures prints what went wrong during a call, with one exception: a wallet that ran out
// mid-call fails every remaining turn for the same reason, and printing that reason once per turn
// buries the one line that matters under a list of duplicates.
func reportFailures(result *consortiumResult) {
	brokeCount := 0
	for _, failure := range result.Errors {
		if strings.Contains(failure.Error, "insufficient balance") {
			brokeCount++
			continue
		}
		fmt.Fprintf(os.Stderr, "! %s failed at the %s turn: %s\n", failure.Provider, failure.Stage, failure.Error)
	}
	if brokeCount > 0 {
		fmt.Fprintf(os.Stderr, "! the wallet ran out mid-call — %d turn(s) went unmade. `aicoin claim` for the faucet.\n",
			brokeCount)
	}
}

// confirmOnStdin asks a yes/no question, when there is somebody to ask. Piped input is not
// somebody: reading the answer from a pipe would consume whatever came next.
func confirmOnStdin(prompt string) bool {
	if !stdinIsTTY() {
		fmt.Fprintln(os.Stderr, "(not a terminal — nothing applied; pass -y to apply without asking)")
		return false
	}
	fmt.Fprint(os.Stderr, prompt)
	var answer string
	fmt.Scanln(&answer)
	return strings.EqualFold(strings.TrimSpace(answer), "y")
}

func cmdCall(args []string) error {
	fs := flag.NewFlagSet("call", flag.ExitOnError)
	url, walletPath := common(fs)
	provider := fs.String("ai", "", "provider to route to (required)")
	data := fs.String("data", "", "request body; @file reads a file, empty reads stdin")
	method := fs.String("method", "POST", "HTTP method")
	if err := parse(fs, args); err != nil {
		return err
	}
	args = positional(fs)
	if *provider == "" || len(args) != 1 {
		return fmt.Errorf("usage: aicoin call -ai <provider> <path> [-data <json>]")
	}
	path := args[0]
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	payload, err := maybeFile(*data)
	if err != nil {
		return err
	}
	if payload == "" && *method == "POST" {
		if piped, err := readStdin(); err == nil {
			payload = piped
		}
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	responseBody, headers, err := newClient(*url, 5*time.Minute).
		withToken(wallet, *method, path, []byte(payload), map[string]string{"X-AI": *provider})
	if err != nil {
		return err
	}
	fmt.Println(strings.TrimSpace(string(responseBody)))
	if charged := headers.Get("X-Aicoin-Charged"); charged != "" {
		fmt.Fprintf(os.Stderr, "%s aicoin\n", charged)
	}
	return nil
}

// looksLikeDir reports whether an argument is meant as a directory rather than as a question.
//
// Deliberately narrow. "." and ".." obviously are; so is anything with a separator or a leading ~.
// A bare existing name is not — `aicoin cli` in a repo that happens to contain a cli/ directory is
// far more likely to be a (very short) question than a request to open a session, and guessing
// wrong there would silently swallow the question.
func looksLikeDir(arg string) bool {
	if arg == "." || arg == ".." {
		return true
	}
	if !strings.ContainsRune(arg, os.PathSeparator) && !strings.HasPrefix(arg, "~") {
		return false
	}
	expanded := arg
	if strings.HasPrefix(arg, "~") {
		if home, err := os.UserHomeDir(); err == nil {
			expanded = filepath.Join(home, strings.TrimPrefix(arg, "~"))
		}
	}
	info, err := os.Stat(expanded)
	return err == nil && info.IsDir()
}

func cmdSession(args []string) error {
	fs := flag.NewFlagSet("session", flag.ExitOnError)
	url, walletPath := common(fs)
	providers := fs.String("providers", "", "comma-separated panel (default: every configured model)")
	editor := fs.String("editor", "", "which model leads (default: the first panelist)")
	rounds := fs.Int("rounds", 0, "cap the review rounds")
	var include stringList
	fs.Var(&include, "f", "include this file's contents in every question (glob; repeatable)")
	budget := fs.Int("budget", 40000, "how many characters of directory context to send at most")
	verbose := fs.Bool("v", false, "show each round's comments")
	auto := fs.Bool("y", false, "apply proposed file changes without asking")
	if err := parse(fs, args); err != nil {
		return err
	}
	dir := "."
	if rest := positional(fs); len(rest) > 0 {
		dir = rest[0]
	}
	if strings.HasPrefix(dir, "~") {
		if home, err := os.UserHomeDir(); err == nil {
			dir = filepath.Join(home, strings.TrimPrefix(dir, "~"))
		}
	}
	return runSession(dir, *url, *walletPath, &sessionState{
		includes:  include,
		providers: *providers,
		editor:    *editor,
		rounds:    *rounds,
		budget:    *budget,
		verbose:   *verbose,
		auto:      *auto,
	})
}

// maybeFile resolves an @path argument to that file's contents, leaving anything else alone.
func maybeFile(value string) (string, error) {
	if !strings.HasPrefix(value, "@") {
		return value, nil
	}
	data, err := os.ReadFile(strings.TrimPrefix(value, "@"))
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// readStdin returns piped input, and nothing when stdin is a terminal — so a command with no
// argument prints its usage instead of hanging on a keyboard that was never going to type.
func readStdin() (string, error) {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeCharDevice != 0 {
		return "", fmt.Errorf("stdin is a terminal")
	}
	data, err := io.ReadAll(os.Stdin)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(data)), nil
}
