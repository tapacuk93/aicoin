package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// What this CLI remembers about the models it has used, and the mode it is in.
//
// Single mode has to pick a model, and the honest way to pick is from what actually happened rather
// than from a leaderboard someone published. Two things are measurable from a consortium response
// and neither needs any judgement about answer quality: how many turns a model carried, and how
// many of them it failed. Everything else a reviewer might mean by "best" — was the answer good? —
// is not in the response and is not invented here.

// providerStats is one model's record. Counted from consortium responses and single calls alike.
type providerStats struct {
	// Turns is how many turns this model was asked for: drafts, merges, reviews, revisions.
	Turns int `json:"turns"`
	// Failures is how many of those did not come back — a timeout, an error status, a 2xx with no
	// text in it. Not counted: turns skipped because the wallet was empty, which say nothing about
	// the model.
	Failures int `json:"failures"`
	// Led is how many calls this model wrote the answer for, as lead or editor.
	Led int `json:"led"`
	// Reviews and Comments: how often it reviewed, and how often it had something to say.
	Reviews  int `json:"reviews"`
	Comments int `json:"comments"`
	// Coins is what this model has actually cost, from the proxy's own per-provider breakdown
	// rather than from dividing a total by the number of panelists.
	Coins int64 `json:"coins"`
}

// carried is the turns this model actually completed — the measure single mode ranks on.
func (p providerStats) carried() int {
	return p.Turns - p.Failures
}

func (p providerStats) reliability() float64 {
	if p.Turns == 0 {
		return 0
	}
	return float64(p.carried()) / float64(p.Turns)
}

// stats is the whole record, keyed by provider, plus the mode this CLI is in.
type stats struct {
	Mode           string                    `json:"mode"`            // "multi" (default) or "single"
	SingleProvider string                    `json:"single_provider"` // pinned by the user; empty means "whichever has carried most"
	Providers      map[string]*providerStats `json:"providers"`

	path string
}

const (
	modeMulti  = "multi"
	modeSingle = "single"
)

// statsPath keeps the record next to the wallet it belongs to, so a second wallet (a test one, a
// separate proxy) keeps its own history rather than polluting the first's.
func statsPath(walletPath string) string {
	return filepath.Join(filepath.Dir(walletPath), "stats.json")
}

func loadStats(walletPath string) *stats {
	path := statsPath(walletPath)
	s := &stats{Mode: modeMulti, Providers: map[string]*providerStats{}, path: path}
	data, err := os.ReadFile(path)
	if err != nil {
		return s
	}
	// A corrupt or half-written record is not worth an error: it is a convenience file, and losing
	// it costs a few calls' worth of history.
	if err := json.Unmarshal(data, s); err != nil {
		return &stats{Mode: modeMulti, Providers: map[string]*providerStats{}, path: path}
	}
	if s.Providers == nil {
		s.Providers = map[string]*providerStats{}
	}
	if s.Mode != modeSingle {
		s.Mode = modeMulti
	}
	s.path = path
	return s
}

func (s *stats) save() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.path, append(data, '\n'), 0o600)
}

func (s *stats) entryFor(provider string) *providerStats {
	if s.Providers[provider] == nil {
		s.Providers[provider] = &providerStats{}
	}
	return s.Providers[provider]
}

// recordConsortium folds one consortium response into the record.
//
// The turn counts are derived from the response rather than guessed: every panelist reviewed in
// every round, the editor merged and revised, and `errors` names who failed at what.
func (s *stats) recordConsortium(result *consortiumResult) {
	for _, provider := range result.Panel {
		entry := s.entryFor(provider)
		// One review per round, plus — for whoever wrote the answer — the draft and the revisions.
		entry.Turns += result.Rounds
		entry.Reviews += result.Rounds
	}
	if result.Editor != "" {
		editor := s.entryFor(result.Editor)
		editor.Led++
		// The draft (or merge), plus one revision per round that did not settle.
		editor.Turns++
	}
	for _, review := range result.Reviews {
		if !review.Clean {
			s.entryFor(review.Provider).Comments++
		}
	}
	for provider, coins := range result.Spend {
		s.entryFor(provider).Coins += coins
	}
	for _, failure := range result.Errors {
		// An empty wallet is not the model's fault and must not count against it: the turn was
		// never made.
		if strings.Contains(failure.Error, "insufficient balance") {
			continue
		}
		s.entryFor(failure.Provider).Failures++
	}
}

func (s *stats) recordSingle(provider string, ok bool, coins int64) {
	entry := s.entryFor(provider)
	entry.Turns++
	entry.Led++
	entry.Coins += coins
	if !ok {
		entry.Failures++
	}
}

// best returns the model single mode should use, and the reason in one clause.
//
// Ranked by turns carried — the model that has actually done the most work here — with reliability
// as the tiebreak. A pinned provider always wins; with no history at all there is nothing to
// measure, and the caller falls back to a provider that is at least configured.
func (s *stats) best() (provider string, why string) {
	if s.SingleProvider != "" {
		return s.SingleProvider, "pinned"
	}
	type ranked struct {
		name string
		providerStats
	}
	var candidates []ranked
	for name, entry := range s.Providers {
		if entry.carried() > 0 {
			candidates = append(candidates, ranked{name, *entry})
		}
	}
	if len(candidates) == 0 {
		return "", "nothing measured yet"
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].carried() != candidates[j].carried() {
			return candidates[i].carried() > candidates[j].carried()
		}
		if candidates[i].reliability() != candidates[j].reliability() {
			return candidates[i].reliability() > candidates[j].reliability()
		}
		return candidates[i].name < candidates[j].name
	})
	top := candidates[0]
	return top.name, fmt.Sprintf("carried %d turns here, %.0f%% of them without failing",
		top.carried(), top.reliability()*100)
}

// render is the table behind `aicoin ais`: which models have been used, what they cost, and what
// they failed — the evidence for whatever single mode picked.
func (s *stats) render(price float64) string {
	if len(s.Providers) == 0 {
		return "no model has been used yet — ask something first\n"
	}
	names := make([]string, 0, len(s.Providers))
	for name := range s.Providers {
		names = append(names, name)
	}
	// Ordered by what each has cost: this table is read to find out where the money went.
	sort.Slice(names, func(i, j int) bool {
		if s.Providers[names[i]].Coins != s.Providers[names[j]].Coins {
			return s.Providers[names[i]].Coins > s.Providers[names[j]].Coins
		}
		return s.Providers[names[i]].carried() > s.Providers[names[j]].carried()
	})
	var total int64
	for _, entry := range s.Providers {
		total += entry.Coins
	}
	var out strings.Builder
	out.WriteString(fmt.Sprintf("%-12s %8s %9s %7s %8s %6s %9s\n",
		"model", "aicoin", "usd", "share", "carried", "failed", "comments"))
	for _, name := range names {
		entry := s.Providers[name]
		share := ""
		if total > 0 {
			share = fmt.Sprintf("%.0f%%", float64(entry.Coins)/float64(total)*100)
		}
		out.WriteString(fmt.Sprintf("%-12s %8d %9s %7s %8d %6d %9d\n",
			name, entry.Coins, usd(float64(entry.Coins), price), share,
			entry.carried(), entry.Failures, entry.Comments))
	}
	out.WriteString(fmt.Sprintf("%-12s %8d %9s\n", "total", total, usd(float64(total), price)))
	chosen, why := s.best()
	if chosen != "" {
		out.WriteString(fmt.Sprintf("\nsingle mode would use %s — %s\n", chosen, why))
	}
	out.WriteString("\ncarried = turns completed; comments = reviews that found something.\n" +
		"None of it says whether an answer was good: that is not in what the proxy reports.\n")
	if price > 0 {
		out.WriteString(fmt.Sprintf("usd is at the current price of %s a coin — what these calls\n"+
			"have cost on average, not a market rate.\n", usd(1, price)))
	}
	return out.String()
}
