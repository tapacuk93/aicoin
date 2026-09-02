package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/hex"
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
  aicoin note request           your address and a fresh nonce, for whoever is paying you
  aicoin note accept <note>     check a note you were given and keep it — no network
  aicoin note sync              redeem what you accepted, and see what became of what you paid
  aicoin note reclaim           take back notes nobody accepted
  aicoin note verify <note>     check a note without keeping it
  aicoin note replay <ref>      hand over a note you already paid — a double-spend, on purpose
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
	case "request":
		return cmdNoteRequest(rest)
	case "accept":
		return cmdNoteAccept(rest, true)
	case "verify":
		return cmdNoteAccept(rest, false)
	case "sync":
		return cmdNoteSync(rest)
	case "replay":
		return cmdNoteReplay(rest)
	case "reclaim":
		return cmdNoteReclaim(rest)
	case "help", "-h", "--help":
		fmt.Print(noteUsage)
		return nil
	default:
		return fmt.Errorf("no such note command %q\n\n%s", sub, noteUsage)
	}
}

// cmdNoteRequest is the receiver's half of a hand-off: their address, and a nonce nobody else has
// seen. A payer cannot prepare a payment for them without it, and a payment made with it is no use
// to anybody else.
func cmdNoteRequest(args []string) error {
	fs := flag.NewFlagSet("note request", flag.ExitOnError)
	_, walletPath := common(fs)
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	nonce := make([]byte, 16)
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	p := loadPurse(*walletPath)
	p.Nonces = append(p.Nonces, hex.EncodeToString(nonce))
	if err := p.save(); err != nil {
		return err
	}
	fmt.Printf("%s %s\n", wallet.Address, hex.EncodeToString(nonce))
	fmt.Fprintln(os.Stderr, "give this to whoever is paying you: `aicoin note pay <amount> -to <address> -nonce <nonce>`")
	return nil
}

func cmdNoteLoad(args []string) error {
	fs := flag.NewFlagSet("note load", flag.ExitOnError)
	url, walletPath := common(fs)
	denoms := fs.String("d", "", "denominations to mint, comma-separated (default: a spread of 50/25/10/5/1)")
	payee := fs.String("for", "", "make these notes out to one wallet — only they can redeem them")
	claimed := fs.Bool("claimed", false, "require a claim to redeem: holding the string is not enough")
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
	request := map[string]any{"amounts": amounts, "ttl_seconds": *days * 86400}
	if *payee != "" {
		if len(*payee) != 64 {
			return fmt.Errorf("a payee is a 64-character wallet address")
		}
		request["payee"] = strings.ToLower(*payee)
	}
	if *claimed {
		request["claimed"] = true
	}
	body, err := json.Marshal(request)
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
	if fetched := fetchLedgerKey(client); fetched != "" {
		p.LedgerKey = fetched
	}
	if err := p.save(); err != nil {
		return err
	}

	var minted float64
	for _, note := range issued.Notes {
		minted += note.Amount
	}
	if *payee != "" {
		fmt.Fprintf(os.Stderr, "%d note(s) worth %s aicoin, made out to %s… — only they can redeem them,\n"+
			"so handing one to two people leaves the second with nothing rather than a race\n",
			len(issued.Notes), formatCoins(minted), (*payee)[:12])
	} else if *claimed {
		fmt.Fprintf(os.Stderr, "%d note(s) worth %s aicoin, redeemable only with a claim — pay with\n"+
			"`note pay <amount> -to <address> -nonce <nonce>` and a copy of the string is worth nothing\n",
			len(issued.Notes), formatCoins(minted))
	} else {
		fmt.Fprintf(os.Stderr, "%d bearer note(s) worth %s aicoin are in the purse — anyone holding one can "+
			"redeem it\n", len(issued.Notes), formatCoins(minted))
	}
	if issued.Error != "" {
		fmt.Fprintf(os.Stderr, "stopped early: %s\n", issued.Error)
	}
	if p.LedgerKey == "" {
		fmt.Fprintln(os.Stderr, "warning: could not cache the ledger's key, so notes you are given cannot be checked offline")
	}
	return nil
}

// fetchLedgerKey asks the proxy for the key notes are verified against, returning "" if it cannot
// be reached. Cached in the purse, because verification has to work when it cannot be.
func fetchLedgerKey(client *Client) string {
	body, err := client.get("/wallet/api/notes/key")
	if err != nil {
		return ""
	}
	var key struct {
		PublicKey string `json:"public_key"`
	}
	if json.Unmarshal(body, &key) != nil {
		return ""
	}
	return key.PublicKey
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
			bound := "bearer"
			if note.Payee != "" {
				bound = "to " + note.Payee[:12] + "…"
			}
			fmt.Printf("  %-6s %s  %-18s expires %s\n", formatCoins(note.Amount), note.Fingerprint,
				bound, time.Unix(note.ExpiresAt, 0).Format("2 Jan"))
		}
	}
	if len(p.Spent) > 0 {
		var paid float64
		for _, note := range p.Spent {
			paid += note.Amount
		}
		fmt.Printf("\nhanded over: %s aicoin in %d note(s)\n", formatCoins(paid), len(p.Spent))
		for _, note := range p.Spent {
			fmt.Printf("  %-6s %s  on %s\n", formatCoins(note.Amount), note.Fingerprint,
				time.Unix(note.HandedAt, 0).Format("2 Jan 15:04"))
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
	to := fs.String("to", "", "who is being paid — spends notes made out to them where possible")
	nonce := fs.String("nonce", "", "the nonce from their `note request` — binds these notes to them alone")
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
	chosen, ok := p.pickFor(amount, strings.ToLower(*to))
	if !ok {
		// Exact change or nothing: a note cannot be broken in half without a network, and handing
		// over a larger one would pay more than was owed.
		return fmt.Errorf("the purse cannot make exactly %s from what it is carrying (%s aicoin) — "+
			"`aicoin note load` for smaller notes", formatCoins(amount), formatCoins(p.total()))
	}
	// A claim binds each note to the person being paid: the payer signs their address and the
	// nonce they chose. Neither side could have produced it alone, so a copy of what is printed
	// below is no use to anybody but them.
	claims := map[string]string{}
	if *nonce != "" {
		if *to == "" {
			return fmt.Errorf("-nonce needs -to: a claim names who is being paid")
		}
		wallet, err := loadWallet(*walletPath)
		if err != nil {
			return err
		}
		for _, note := range chosen {
			payload, verifyErr := verifyNote(note.Note, p.LedgerKey)
			if payload == nil {
				return verifyErr
			}
			claims[note.Note] = hex.EncodeToString(
				ed25519.Sign(wallet.private(), []byte(claimMessage(payload.ID, strings.ToLower(*to), *nonce))))
		}
	}
	p.handOver(chosen)
	if err := p.save(); err != nil {
		return err
	}
	for _, note := range chosen {
		if claim, ok := claims[note.Note]; ok {
			fmt.Printf("%s %s %s\n", note.Note, *nonce, claim)
			continue
		}
		fmt.Println(note.Note)
	}
	fmt.Fprintf(os.Stderr, "\n%s aicoin in %d note(s). Fingerprint(s):", formatCoins(amount), len(chosen))
	for _, note := range chosen {
		fmt.Fprintf(os.Stderr, " %s", note.Fingerprint)
	}
	fmt.Fprintln(os.Stderr, "\nThe other side should see the same fingerprint(s). These are out of your purse now.")
	for _, note := range chosen {
		if note.Payee == "" {
			fmt.Fprintln(os.Stderr, "Some of these are bearer notes: whoever holds one can redeem it.")
			break
		}
	}
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
	url, walletPath := common(fs)
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

	// Who this wallet is, so a note made out to somebody else can be refused rather than kept: it
	// would never redeem, and storing it would only postpone the disappointment.
	me := ""
	if wallet, walletErr := loadWallet(*walletPath); walletErr == nil {
		me = wallet.Address
	}
	p := loadPurse(*walletPath)
	// A wallet's very first act can be accepting a note, and without the ledger's key it can only
	// take one on trust. Fetch it if there is a network — being offline is the case this whole
	// feature is for, so failing to reach the proxy is not an error, it just leaves the check
	// unmade and says so.
	if p.LedgerKey == "" {
		if fetched := fetchLedgerKey(newClient(*url, 10*time.Second)); fetched != "" {
			p.LedgerKey = fetched
			_ = p.save()
		}
	}
	// Whether the wallet that issued these notes is in debt — visible to anybody, since a balance
	// is public. Worth knowing before accepting: a wallet that has overspent cannot issue more,
	// which makes what you are holding likelier to be the last of what it had. Offline this cannot
	// be checked, and it says so rather than implying an all-clear.
	issuerDebt := map[string]float64{}
	checkedIssuers := newClient(*url, 5*time.Second)
	accepted := 0
	// A hand-off is either a bare note, or a note with the nonce and claim that bind it to this
	// wallet. Fields, so a pasted line of either shape works.
	fields := strings.Fields(encoded)
	for index := 0; index < len(fields); index++ {
		line := fields[index]
		nonce, claim := "", ""
		if index+2 < len(fields) && !strings.Contains(fields[index+1], ".") && !strings.Contains(fields[index+2], ".") {
			nonce, claim = fields[index+1], fields[index+2]
			index += 2
		}
		payload, err := verifyNote(line, p.LedgerKey)
		if err != nil {
			fmt.Fprintf(os.Stderr, "✗ %v\n", err)
			continue
		}
		fingerprint := fingerprintOf(payload.ID)
		if payload.Payee != "" && me != "" && !strings.EqualFold(payload.Payee, me) {
			// Genuine, and no use to this wallet: only the named payee can redeem it.
			fmt.Fprintf(os.Stderr, "✗ %s · genuine, but made out to %s… — not you\n",
				fingerprint, payload.Payee[:12])
			continue
		}
		if _, seen := issuerDebt[payload.Issuer]; !seen {
			if balance, balErr := checkedIssuers.balance(payload.Issuer); balErr == nil {
				issuerDebt[payload.Issuer] = balance
			}
		}
		binding := "bearer — whoever holds it can redeem it, including whoever else was handed a copy"
		if payload.Payee != "" {
			binding = "made out to you — nobody else can redeem it, so it cannot have been spent elsewhere"
		}
		if claim != "" {
			if me == "" {
				fmt.Fprintf(os.Stderr, "✗ %s · a claim was offered but this wallet has no address to check it against\n",
					fingerprint)
				continue
			}
			if !p.holdsNonce(nonce) {
				// Somebody else's nonce means somebody else's payment: this is a copy of a
				// hand-off that was not made to us.
				fmt.Fprintf(os.Stderr, "✗ %s · claimed with a nonce this wallet never issued — "+
					"this payment was made to somebody else\n", fingerprint)
				continue
			}
			if !verifyClaim(payload.Issuer, payload.ID, strings.ToLower(me), nonce, claim) {
				fmt.Fprintf(os.Stderr, "✗ %s · the claim is not the issuer signing this note over to you\n",
					fingerprint)
				continue
			}
			binding = "claimed for you — signed over to your address against the nonce you gave, " +
				"so a copy of this is no use to anyone else"
		}
		fmt.Fprintf(os.Stderr, "✓ genuine · %s aicoin · from %s… · %s\n   %s\n",
			formatCoins(payload.Amount), payload.Issuer[:12], fingerprint, binding)
		if balance, known := issuerDebt[payload.Issuer]; known && balance < 0 {
			// Not a reason to refuse — this note's coins left that wallet when it was issued, and
			// are waiting for whoever redeems first. It is a reason to redeem promptly, and to
			// think twice about the next one.
			fmt.Fprintf(os.Stderr, "   ! that wallet is %s aicoin in debt and can issue no more — "+
				"sync this one sooner rather than later\n", formatCoins(-balance))
		}
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
			Payee: payload.Payee, Nonce: nonce, Claim: claim, AcceptedAt: time.Now().Unix(),
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

// cmdNoteReplay hands over a note this wallet already paid — a deliberate double-spend.
//
// It exists because the defence needs an attacker to test it. A bearer note is a string, and `note
// pay` prints it to a terminal: anyone can scroll back and give the same one to a second person.
// This adds no capability that was not already there; it makes the one that is there explicit,
// repeatable, and available to the end-to-end tests, so "the second person is told it was already
// redeemed" is something this project demonstrates rather than asserts.
//
// It requires -yes, prints what it is doing, and says who it defrauds. Nothing here is a mode a
// wallet drifts into by accident.
func cmdNoteReplay(args []string) error {
	fs := flag.NewFlagSet("note replay", flag.ExitOnError)
	_, walletPath := common(fs)
	yes := fs.Bool("yes", false, "confirm that this is a deliberate double-spend")
	if err := parse(fs, args); err != nil {
		return err
	}
	positionals := positional(fs)
	if len(positionals) != 1 {
		return fmt.Errorf("usage: aicoin note replay <fingerprint> -yes")
	}
	p := loadPurse(*walletPath)
	note, found := p.findSpent(positionals[0])
	if !found {
		return fmt.Errorf("no note you have handed over matches %q — `aicoin note list` shows the receipts",
			positionals[0])
	}
	if !*yes {
		fmt.Fprintf(os.Stderr, "%s (%s aicoin) was handed over on %s.\n", note.Fingerprint,
			formatCoins(note.Amount), time.Unix(note.HandedAt, 0).Format("2 Jan 15:04"))
		fmt.Fprintln(os.Stderr, "Handing it to somebody else is a double-spend: whoever redeems second gets nothing,")
		fmt.Fprintln(os.Stderr, "and both attempts are recorded against this wallet. Pass -yes if that is what you want.")
		return fmt.Errorf("not replayed")
	}
	fmt.Println(note.Note)
	fmt.Fprintf(os.Stderr, "\n%s · %s aicoin · replayed — this note was already handed over on %s\n",
		note.Fingerprint, formatCoins(note.Amount), time.Unix(note.HandedAt, 0).Format("2 Jan 15:04"))
	fmt.Fprintln(os.Stderr, "Whoever redeems it second will be told it was already redeemed, and will have nothing.")
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
		request := map[string]string{"note": note.Note}
		if note.Claim != "" {
			request["nonce"] = note.Nonce
			request["claim"] = note.Claim
		}
		body, marshalErr := json.Marshal(request)
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
			Credited    bool    `json:"credited"`
			Compensated bool    `json:"compensated"`
			Amount      float64 `json:"amount"`
			Balance     float64 `json:"balance"`
			Reason      string  `json:"reason"`
			DoubleSpend *struct {
				Issuer string `json:"issuer"`
				NoteID string `json:"note_id"`
				Claims []struct {
					Payee string `json:"payee"`
					Claim string `json:"claim"`
				} `json:"claims"`
			} `json:"double_spend"`
		}
		if json.Unmarshal(response, &result) != nil {
			kept = append(kept, note)
			continue
		}
		if result.Credited {
			credited += result.Amount
			if result.Compensated {
				// The note was already spent — and this wallet was paid anyway, out of the wallet
				// that spent it twice. Worth distinguishing from an ordinary credit.
				fmt.Fprintf(os.Stderr, "✓ %s · %s aicoin — that note had been spent already; you were "+
					"compensated from the wallet that did it\n", note.Fingerprint, formatCoins(result.Amount))
				if result.DoubleSpend != nil {
					fmt.Fprintf(os.Stderr, "   %s… now owes it, and rates 0 until it is paid\n",
						result.DoubleSpend.Issuer[:12])
				}
				continue
			}
			fmt.Fprintf(os.Stderr, "✓ %s · %s aicoin credited\n", note.Fingerprint, formatCoins(result.Amount))
			continue
		}
		// The case this design cannot prevent and therefore states plainly: somebody handed the
		// same note to two people, and this one arrived second.
		switch result.Reason {
		case "redeemed":
			fmt.Fprintf(os.Stderr, "✗ %s · already redeemed by someone else — you were given a note that was spent\n",
				note.Fingerprint)
			if result.DoubleSpend != nil && len(result.DoubleSpend.Claims) == 2 {
				// Not an accusation, a proof: two claims on one note, signed by the same payer,
				// naming two different people. Nobody has to be believed.
				fmt.Fprintf(os.Stderr, "   proof of double-spend by %s…:\n", result.DoubleSpend.Issuer[:12])
				for _, c := range result.DoubleSpend.Claims {
					fmt.Fprintf(os.Stderr, "     signed over to %s… (%s)\n", c.Payee[:12], c.Claim)
				}
				fmt.Fprintln(os.Stderr, "   anyone can check those against the issuer's address; neither could be forged.")
			}
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
	includeSpent := fs.Bool("include-spent", false, "also try notes handed over that nobody redeemed")
	if err := parse(fs, args); err != nil {
		return err
	}
	wallet, err := loadWallet(*walletPath)
	if err != nil {
		return err
	}
	p := loadPurse(*walletPath)
	candidates := p.Mine
	if *includeSpent {
		// A note handed to somebody who never came back online is still the issuer's money, and
		// reclaiming it fails harmlessly ("redeemed") if they did.
		candidates = append(append([]heldNote(nil), p.Mine...), p.Spent...)
	}
	if len(candidates) == 0 {
		fmt.Fprintln(os.Stderr, "the purse is carrying nothing to reclaim")
		return nil
	}
	client := newClient(*url, 2*time.Minute)
	var kept []heldNote
	var back float64
	for _, note := range candidates {
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
	var keptMine []heldNote
	for _, note := range kept {
		if note.HandedAt == 0 {
			keptMine = append(keptMine, note)
		}
	}
	p.Mine = keptMine
	if err := p.save(); err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "%s aicoin back in the wallet\n", formatCoins(back))
	return nil
}
