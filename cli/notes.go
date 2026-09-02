package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// The purse: notes this wallet is carrying, and notes it has been handed.
//
// A note is coins that already left the balance, wrapped in a string the ledger signed. Loading the
// purse is the only step that needs a network. After that a payment is one person showing a string
// and the other reading it — neither side talks to anything — and the receiver can still check that
// the string is genuine, because their app cached the ledger's public key the last time it was
// online.
//
// What nobody can check offline is whether the note has already been given to somebody else. That
// is a fact about the ledger and the ledger is not there. `note sync` is where it becomes known,
// and the answer is first-come: whoever redeems first has the coins.

type heldNote struct {
	Note        string  `json:"note"`
	Amount      float64 `json:"amount"`
	Fingerprint string  `json:"fingerprint"`
	Hash        string  `json:"hash"`
	ExpiresAt   int64   `json:"expires_at"`
	Issuer      string  `json:"issuer,omitempty"`
	// Payee is the one wallet that can redeem this note, or empty for a bearer note anyone can.
	// A bound note cannot be double-spent: hand it to two people and only the named one can use it.
	Payee      string `json:"payee,omitempty"`
	AcceptedAt int64  `json:"accepted_at,omitempty"`
	HandedAt   int64  `json:"handed_at,omitempty"`
}

type purse struct {
	// LedgerKey is the ledger's note-signing public key, cached so verification works with no
	// network. Without it a received note can only be taken on trust.
	LedgerKey string     `json:"ledger_key"`
	Mine      []heldNote `json:"mine"`
	Received  []heldNote `json:"received"`
	// Spent is what this wallet has handed over: receipts, not money. Keeping them is ordinary
	// wallet hygiene — a receiver who loses their phone before syncing leaves coins stranded, and
	// the issuer needs the string to reclaim them — and it is also, unavoidably, what makes a
	// second hand-off possible. See `note replay`: the capability is inherent to a bearer note,
	// which is printed to a terminal the moment it is paid.
	Spent []heldNote `json:"spent"`

	path string
}

// pursePath names the purse after the wallet it belongs to, not after the directory it sits in.
// Two wallets in one directory — a test wallet beside the real one, two people on one machine —
// otherwise shared a purse, which for a file full of bearer notes means each could spend the
// other's money.
func pursePath(walletPath string) string {
	return sidecarPath(walletPath, "purse")
}

// sidecarPath turns ~/.aicoin/wallet.json into ~/.aicoin/wallet.purse.json.
func sidecarPath(walletPath, kind string) string {
	base := strings.TrimSuffix(filepath.Base(walletPath), filepath.Ext(walletPath))
	if base == "" {
		base = "wallet"
	}
	return filepath.Join(filepath.Dir(walletPath), base+"."+kind+".json")
}

func loadPurse(walletPath string) *purse {
	p := &purse{path: pursePath(walletPath)}
	data, err := os.ReadFile(p.path)
	if err == nil {
		// A corrupt purse is not silently discarded the way the stats file is: it holds money.
		if err := json.Unmarshal(data, p); err != nil {
			fmt.Fprintf(os.Stderr, "aicoin: %s is unreadable (%v) — it has been left alone\n", p.path, err)
		}
		p.path = pursePath(walletPath)
	}
	return p
}

func (p *purse) save() error {
	if err := os.MkdirAll(filepath.Dir(p.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	// 0600: every note in here is spendable by whoever can read it.
	return os.WriteFile(p.path, append(data, '\n'), 0o600)
}

func (p *purse) total() float64 {
	var sum float64
	for _, note := range p.Mine {
		sum += note.Amount
	}
	return sum
}

// notePayload is the part of a note the ledger signed.
type notePayload struct {
	Version int     `json:"v"`
	ID      string  `json:"id"`
	Amount  float64 `json:"amt"`
	Issuer  string  `json:"iss"`
	Expires int64   `json:"exp"`
	Payee   string  `json:"pay"`
}

// verifyNote checks a note against the ledger's public key — the whole offline half of this
// feature. It proves the note is genuine and worth what it says. It cannot prove nobody else has
// it: that is what sync is for.
func verifyNote(encoded, ledgerKeyHex string) (*notePayload, error) {
	trimmed := strings.TrimSpace(encoded)
	dot := strings.Index(trimmed, ".")
	if dot <= 0 || dot == len(trimmed)-1 {
		return nil, fmt.Errorf("that is not a note")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(trimmed[:dot])
	if err != nil {
		return nil, fmt.Errorf("that is not a note")
	}
	var payload notePayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("that is not a note")
	}
	if ledgerKeyHex == "" {
		return &payload, fmt.Errorf("no ledger key cached — run `aicoin note load` once while online to fetch it")
	}
	key, err := hex.DecodeString(ledgerKeyHex)
	if err != nil || len(key) != ed25519.PublicKeySize {
		return &payload, fmt.Errorf("the cached ledger key is unusable")
	}
	signature, err := base64.RawURLEncoding.DecodeString(trimmed[dot+1:])
	if err != nil {
		return &payload, fmt.Errorf("the signature is malformed")
	}
	if !ed25519.Verify(key, []byte(trimmed[:dot]), signature) {
		return &payload, fmt.Errorf("the ledger did not sign this note")
	}
	if payload.Expires > 0 && time.Now().Unix() > payload.Expires {
		return &payload, fmt.Errorf("this note expired on %s",
			time.Unix(payload.Expires, 0).Format("2 Jan 2006"))
	}
	return &payload, nil
}

// hashOfNoteID is the ledger's key for a note: the hash of the secret, never the secret. Asking
// after a note's state uses this, so a status check hands nobody anything spendable.
func hashOfNoteID(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:])
}

// fingerprintOf is the six characters two people read to each other to confirm the note arrived as
// it left. From the hash, not the id, so saying it aloud gives nothing away.
func fingerprintOf(id string) string {
	h := strings.ToUpper(hashOfNoteID(id))
	return h[0:2] + "-" + h[2:4] + "-" + h[4:6]
}

// holds reports whether this note is already in the purse, so accepting the same one twice is a
// no-op rather than a double entry that then fails to redeem.
func (p *purse) holds(id string) bool {
	hash := hashOfNoteID(id)
	for _, note := range p.Received {
		if note.Hash == hash {
			return true
		}
	}
	return false
}

// denominations turns an amount into notes worth carrying: a few large, several small, so a later
// payment can be made exactly without breaking anything.
func denominations(total float64) []float64 {
	ladder := []float64{50, 25, 10, 5, 1}
	var notes []float64
	remaining := total
	for _, step := range ladder {
		for remaining >= step && len(notes) < 40 {
			// Keep at least a little back for the smaller denominations, so a purse loaded with
			// 100 is not one note that cannot pay for anything under 100.
			if step >= 25 && remaining-step < total*0.25 {
				break
			}
			notes = append(notes, step)
			remaining -= step
		}
	}
	for remaining >= 1 && len(notes) < 50 {
		notes = append(notes, 1)
		remaining--
	}
	return notes
}

// pickFor chooses notes adding up to exactly the amount, preferring ones already made out to this
// payee — those are the ones that cannot be double-spent, so they are the ones to spend when the
// payee is known. Bearer notes are used only to make up a difference the bound ones cannot.
func (p *purse) pickFor(amount float64, payee string) ([]heldNote, bool) {
	if payee == "" {
		return p.pick(amount)
	}
	var bound, bearer []heldNote
	for _, note := range p.Mine {
		switch note.Payee {
		case payee:
			bound = append(bound, note)
		case "":
			bearer = append(bearer, note)
		}
		// Notes made out to somebody else are no use here and are left where they are.
	}
	if chosen, ok := (&purse{Mine: bound}).pick(amount); ok {
		return chosen, true
	}
	return (&purse{Mine: append(bound, bearer...)}).pick(amount)
}

// pick chooses notes adding up to exactly the amount, largest first. Exactly, because a note cannot
// be broken in half offline: paying 25 with a 50 would hand over twice what was owed.
func (p *purse) pick(amount float64) ([]heldNote, bool) {
	sorted := append([]heldNote(nil), p.Mine...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i].Amount > sorted[j].Amount })
	var chosen []heldNote
	remaining := amount
	for _, note := range sorted {
		if note.Amount <= remaining+1e-9 {
			chosen = append(chosen, note)
			remaining -= note.Amount
		}
		if remaining <= 1e-9 {
			return chosen, true
		}
	}
	return nil, false
}

// handOver moves notes from what this wallet is carrying to what it has paid out. They stop being
// spendable here and become receipts.
func (p *purse) handOver(notes []heldNote) {
	gone := map[string]bool{}
	now := time.Now().Unix()
	for _, note := range notes {
		gone[note.Hash] = true
	}
	var kept []heldNote
	for _, note := range p.Mine {
		if !gone[note.Hash] {
			kept = append(kept, note)
			continue
		}
		note.HandedAt = now
		p.Spent = append(p.Spent, note)
	}
	p.Mine = kept
}

// findSpent looks up a receipt by fingerprint or by the head of its hash.
func (p *purse) findSpent(reference string) (heldNote, bool) {
	needle := strings.ToUpper(strings.TrimSpace(reference))
	for _, note := range p.Spent {
		if strings.EqualFold(note.Fingerprint, needle) ||
			strings.HasPrefix(strings.ToUpper(note.Hash), strings.ReplaceAll(needle, "-", "")) {
			return note, true
		}
	}
	return heldNote{}, false
}
