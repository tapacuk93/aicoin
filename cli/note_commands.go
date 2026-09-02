package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// `aicoin note ...` — load the purse while online, then pay and accept with no network at all.

const noteUsage = `aicoin note — coins that change hands offline

  aicoin note load <amount>     turn balance into notes and put them in the purse (needs a network)
  aicoin note list              what the purse is carrying
  aicoin note pay <amount>      hand over notes worth exactly that — no network
  aicoin note accept <note>     check a note you were given and keep it — no network
  aicoin note sync              redeem what you accepted, and see what became of what you paid
  aicoin note reclaim           take back notes nobody accepted
  aicoin note verify <note>     check a note without keeping it
`

func cmdNote(args []string) error {
	if len(args) == 0 {
		fmt.Fprint(os.Stderr, noteUsage)
		return nil
	}
	sub := args[0]
	rest := args[1:]
	switch sub {
	case "load":
		return cmdNoteLoad(rest)
	case "list":
		return cmdNoteList(rest)
	case "pay":
		return cmdNotePay(rest)
	case "accept":
		return cmdNoteAccept(rest, true)
	case "verify":
		return cmdNoteAccept(rest, false)
	case "sync":
		return cmdNoteSync(rest)
	case "reclaim":
		return cmdNoteReclaim(rest)
	case "help", "-h", "--help":
		fmt.Print(noteUsage)
		return nil
	default:
		return fmt.Errorf("no such note command %q\n\n%s", sub, noteUsage)
	}
}

func cmdNoteLoad(args []string) error {
	fs := flag.NewFlagSet("note load", flag.ExitOnError)
	url, walletPath := common(fs)
	denoms := fs.String("d", "", "denominations to mint, comma-separated (default: a spread of 50/25/10/5/1)")
	days := fs.Int("days", 30, "how long the notes stay redeemable")
	if err := parse(fs, args); err != nil {
		return err
	}
	positionals := positional(fs)
	if len(positionals) != 1 {
		return fmt.Errorf("usage: aicoin note load <amount>")
	}
	total, err := strconv.ParseFloat(positionals[0], 64)
	if err != nil || total <= 0 {
		return fmt.Errorf("amount must be a positive number")
	}

	var amounts []float64
	if *denoms != "" {
		for _, piece := range strings.Split(*denoms, ",") {
			value, convErr := strconv.ParseFloat(strings.TrimSpace(piece), 64)
			if convErr != nil || value <= 0 {
				return fmt.Errorf("denominations must be positive numbers")
			}
			amounts = append(amounts, value)
		}
	} else {
		amounts = denominations(total)
	}
	if len(amounts) == 0 {
		return fmt.Errorf("nothing to mint")
	}

	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	client := newClient(*url, 2*time.Minute)
	body, err := json.Marshal(map[string]any{"amounts": amounts, "ttl_seconds": *days * 86400})
	if err != nil {
		return err
	}
	response, err := client.signed(wallet, "POST", "/wallet/api/notes/issue", body)
	if err != nil {
		return err
	}
	var issued struct {
		Notes []heldNote `json:"notes"`
		Error string     `json:"error"`
	}
	if err := json.Unmarshal(response, &issued); err != nil {
		return err
	}

	p := loadPurse(*walletPath)
	p.Mine = append(p.Mine, issued.Notes...)
	// Cache the ledger's key while there is a network: without it, a note handed to this wallet
	// later can only be taken on trust.
	if keyBody, keyErr := client.get("/wallet/api/notes/key"); keyErr == nil {
		var key struct {
			PublicKey string `json:"public_key"`
		}
		if json.Unmarshal(keyBody, &key) == nil && key.PublicKey != "" {
			p.LedgerKey = key.PublicKey
		}
	}
	if err := p.save(); err != nil {
		return err
	}

	var minted float64
	for _, note := range issued.Notes {
		minted += note.Amount
	}
	fmt.Fprintf(os.Stderr, "%d note(s) worth %s aicoin are in the purse — they can be paid with no network\n",
		len(issued.Notes), formatCoins(minted))
	if issued.Error != "" {
		fmt.Fprintf(os.Stderr, "stopped early: %s\n", issued.Error)
	}
	if p.LedgerKey == "" {
		fmt.Fprintln(os.Stderr, "warning: could not cache the ledger's key, so notes you are given cannot be checked offline")
	}
	return nil
}

func cmdNoteList(args []string) error {
	fs := flag.NewFlagSet("note list", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	p := loadPurse(*walletPath)
	price, _ := newClient(*url, 10*time.Second).priceUSD()

	if len(p.Mine) == 0 && len(p.Received) == 0 {
		fmt.Println("the purse is empty — `aicoin note load 50` while you have a network")
		return nil
	}
	if len(p.Mine) > 0 {
		fmt.Printf("carrying %s aicoin%s in %d note(s):\n",
			formatCoins(p.total()), bracketed(usd(p.total(), price)), len(p.Mine))
		for _, note := range p.Mine {
			fmt.Printf("  %-6s %s  expires %s\n", formatCoins(note.Amount), note.Fingerprint,
				time.Unix(note.ExpiresAt, 0).Format("2 Jan"))
		}
	}
	if len(p.Received) > 0 {
		var pending float64
		for _, note := range p.Received {
			pending += note.Amount
		}
		fmt.Printf("\naccepted but not yet redeemed: %s aicoin in %d note(s) — `aicoin note sync`\n",
			formatCoins(pending), len(p.Received))
		for _, note := range p.Received {
			fmt.Printf("  %-6s %s  from %s…\n", formatCoins(note.Amount), note.Fingerprint, note.Issuer[:12])
		}
	}
	return nil
}

func cmdNotePay(args []string) error {
	fs := flag.NewFlagSet("note pay", flag.ExitOnError)
	_, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	positionals := positional(fs)
	if len(positionals) != 1 {
		return fmt.Errorf("usage: aicoin note pay <amount>")
	}
	amount, err := strconv.ParseFloat(positionals[0], 64)
	if err != nil || amount <= 0 {
		return fmt.Errorf("amount must be a positive number")
	}
	p := loadPurse(*walletPath)
	chosen, ok := p.pick(amount)
	if !ok {
		// Exact change or nothing: a note cannot be broken in half without a network, and handing
		// over a larger one would pay more than was owed.
		return fmt.Errorf("the purse cannot make exactly %s from what it is carrying (%s aicoin) — "+
			"`aicoin note load` for smaller notes", formatCoins(amount), formatCoins(p.total()))
	}
	p.removeMine(chosen)
	if err := p.save(); err != nil {
		return err
	}
	for _, note := range chosen {
		fmt.Println(note.Note)
	}
	fmt.Fprintf(os.Stderr, "\n%s aicoin in %d note(s). Fingerprint(s):", formatCoins(amount), len(chosen))
	for _, note := range chosen {
		fmt.Fprintf(os.Stderr, " %s", note.Fingerprint)
	}
	fmt.Fprintln(os.Stderr, "\nThe other side should see the same fingerprint(s). These are out of your purse now.")
	return nil
}

// cmdNoteAccept verifies a note offline and — when keeping it — puts it in the purse to redeem
// later. This is the receiver's whole experience at hand-off time: genuine, this much, from them.
func cmdNoteAccept(args []string, keep bool) error {
	name := "note verify"
	if keep {
		name = "note accept"
	}
	fs := flag.NewFlagSet(name, flag.ExitOnError)
	_, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	encoded := strings.Join(positional(fs), "")
	if encoded == "" {
		if piped, err := readStdin(); err == nil {
			encoded = piped
		}
	}
	if encoded == "" {
		return fmt.Errorf("usage: aicoin %s <note>", name)
	}

	p := loadPurse(*walletPath)
	accepted := 0
	for _, line := range strings.Fields(encoded) {
		payload, err := verifyNote(line, p.LedgerKey)
		if err != nil {
			fmt.Fprintf(os.Stderr, "✗ %v\n", err)
			continue
		}
		fingerprint := fingerprintOf(payload.ID)
		fmt.Fprintf(os.Stderr, "✓ genuine · %s aicoin · from %s… · %s\n",
			formatCoins(payload.Amount), payload.Issuer[:12], fingerprint)
		if !keep {
			continue
		}
		if p.holds(payload.ID) {
			fmt.Fprintln(os.Stderr, "  (already in your purse)")
			continue
		}
		p.Received = append(p.Received, heldNote{
			Note: strings.TrimSpace(line), Amount: payload.Amount, Fingerprint: fingerprint,
			Hash: hashOfNoteID(payload.ID), ExpiresAt: payload.Expires, Issuer: payload.Issuer,
			AcceptedAt: time.Now().Unix(),
		})
		accepted++
	}
	if !keep {
		return nil
	}
	if accepted == 0 {
		return fmt.Errorf("nothing was accepted")
	}
	if err := p.save(); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "kept %d note(s) — `aicoin note sync` when you have a network to make them yours\n", accepted)
	return nil
}

func cmdNoteSync(args []string) error {
	fs := flag.NewFlagSet("note sync", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	p := loadPurse(*walletPath)
	if len(p.Received) == 0 {
		fmt.Fprintln(os.Stderr, "nothing accepted is waiting to be redeemed")
		return nil
	}
	client := newClient(*url, 2*time.Minute)

	var kept []heldNote
	var credited float64
	for _, note := range p.Received {
		body, marshalErr := json.Marshal(map[string]string{"note": note.Note})
		if marshalErr != nil {
			kept = append(kept, note)
			continue
		}
		response, callErr := client.signed(wallet, "POST", "/wallet/api/notes/redeem", body)
		if callErr != nil {
			fmt.Fprintf(os.Stderr, "%s · %v — still in the purse\n", note.Fingerprint, callErr)
			kept = append(kept, note)
			continue
		}
		var result struct {
			Credited bool    `json:"credited"`
			Amount   float64 `json:"amount"`
			Balance  float64 `json:"balance"`
			Reason   string  `json:"reason"`
		}
		if json.Unmarshal(response, &result) != nil {
			kept = append(kept, note)
			continue
		}
		if result.Credited {
			credited += result.Amount
			fmt.Fprintf(os.Stderr, "✓ %s · %s aicoin credited\n", note.Fingerprint, formatCoins(result.Amount))
			continue
		}
		// The case this design cannot prevent and therefore states plainly: somebody handed the
		// same note to two people, and this one arrived second.
		switch result.Reason {
		case "redeemed":
			fmt.Fprintf(os.Stderr, "✗ %s · already redeemed by someone else — you were given a note that was spent\n",
				note.Fingerprint)
		case "expired", "unknown":
			fmt.Fprintf(os.Stderr, "✗ %s · %s\n", note.Fingerprint, result.Reason)
		default:
			fmt.Fprintf(os.Stderr, "✗ %s · %s\n", note.Fingerprint, result.Reason)
		}
	}
	p.Received = kept
	if err := p.save(); err != nil {
		return err
	}
	if credited > 0 {
		balance, balErr := client.balance(wallet.Address)
		if balErr == nil {
			price, _ := client.priceUSD()
			fmt.Fprintf(os.Stderr, "\n%s aicoin credited · balance %s%s\n",
				formatCoins(credited), formatCoins(balance), bracketed(usd(balance, price)))
		}
	}
	return nil
}

func cmdNoteReclaim(args []string) error {
	fs := flag.NewFlagSet("note reclaim", flag.ExitOnError)
	url, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	p := loadPurse(*walletPath)
	if len(p.Mine) == 0 {
		fmt.Fprintln(os.Stderr, "the purse is carrying nothing to reclaim")
		return nil
	}
	client := newClient(*url, 2*time.Minute)
	var kept []heldNote
	var back float64
	for _, note := range p.Mine {
		body, _ := json.Marshal(map[string]string{"note": note.Note})
		response, callErr := client.signed(wallet, "POST", "/wallet/api/notes/reclaim", body)
		if callErr != nil {
			fmt.Fprintf(os.Stderr, "%s · %v\n", note.Fingerprint, callErr)
			kept = append(kept, note)
			continue
		}
		var result struct {
			Reclaimed bool    `json:"reclaimed"`
			Amount    float64 `json:"amount"`
			Reason    string  `json:"reason"`
		}
		if json.Unmarshal(response, &result) != nil || !result.Reclaimed {
			fmt.Fprintf(os.Stderr, "%s · not reclaimed (%s)\n", note.Fingerprint, result.Reason)
			continue
		}
		back += result.Amount
	}
	p.Mine = kept
	if err := p.save(); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "%s aicoin back in the wallet\n", formatCoins(back))
	return nil
}
