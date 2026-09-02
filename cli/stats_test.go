package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// What single mode picks, and why. The claim these make is narrow on purpose: the record says how
// much work a model carried and how much of it it failed, and nothing at all about whether the
// answers were any good — that is not in what the proxy reports, so it is not measured here.

func consortiumFixture(panel []string, editor string, rounds int) *consortiumResult {
	return &consortiumResult{Panel: panel, Editor: editor, Rounds: rounds, Settled: true}
}

func TestSingleModePicksWhicheverCarriedTheMostWork(t *testing.T) {
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))

	// Two calls led by anthropic, with kimi only ever reviewing.
	record.recordConsortium(consortiumFixture([]string{"anthropic", "kimi"}, "anthropic", 2))
	record.recordConsortium(consortiumFixture([]string{"anthropic", "kimi"}, "anthropic", 2))

	provider, why := record.best()
	if provider != "anthropic" {
		t.Fatalf("expected anthropic, got %s (%s)", provider, why)
	}
	if !strings.Contains(why, "carried") {
		t.Fatalf("the reason should say what was measured, got %q", why)
	}
}

func TestFailedTurnsCountAgainstAModel(t *testing.T) {
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	result := consortiumFixture([]string{"anthropic", "kimi"}, "kimi", 3)
	// Kimi led, so it has the most turns — but timed out on most of them.
	result.Errors = []struct {
		Stage    string `json:"stage"`
		Provider string `json:"provider"`
		Error    string `json:"error"`
	}{
		{Stage: "review", Provider: "kimi", Error: "upstream timed out"},
		{Stage: "review", Provider: "kimi", Error: "upstream timed out"},
		{Stage: "revise", Provider: "kimi", Error: "upstream timed out"},
	}
	record.recordConsortium(result)

	provider, _ := record.best()
	if provider != "anthropic" {
		t.Fatalf("a model that fails its turns has not carried them; got %s", provider)
	}
}

func TestAnEmptyWalletIsNotHeldAgainstAModel(t *testing.T) {
	// Turns that were never made say nothing about the model that would have made them.
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	result := consortiumFixture([]string{"anthropic"}, "anthropic", 1)
	result.Errors = []struct {
		Stage    string `json:"stage"`
		Provider string `json:"provider"`
		Error    string `json:"error"`
	}{{Stage: "draft", Provider: "anthropic", Error: "insufficient balance"}}
	record.recordConsortium(result)

	if record.Providers["anthropic"].Failures != 0 {
		t.Fatal("an unmade turn is not a failure")
	}
}

func TestAPinnedProviderWins(t *testing.T) {
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	record.recordConsortium(consortiumFixture([]string{"anthropic"}, "anthropic", 3))
	record.SingleProvider = "kimi"

	provider, why := record.best()
	if provider != "kimi" || why != "pinned" {
		t.Fatalf("a pinned provider should override the measurement, got %s (%s)", provider, why)
	}
}

func TestWithNoHistoryThereIsNothingToClaim(t *testing.T) {
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	if provider, why := record.best(); provider != "" || !strings.Contains(why, "nothing measured") {
		t.Fatalf("an empty record should say so rather than guess: %s / %s", provider, why)
	}
	// ...and the caller falls back to something the proxy actually has a key for.
	provider, why, err := chooseSingleProvider(record, []string{"kimi", "google"})
	if err != nil || provider != "google" {
		t.Fatalf("expected the first configured chat provider in canonical order, got %s (%s) %v", provider, why, err)
	}
}

func TestAMeasuredProviderThisProxyCannotCallIsNotChosen(t *testing.T) {
	// The record travels with the wallet; the keys belong to the proxy. A model that carried work
	// against another deployment is no use here.
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	record.recordConsortium(consortiumFixture([]string{"mistral"}, "mistral", 3))

	provider, _, err := chooseSingleProvider(record, []string{"anthropic"})
	if err != nil || provider != "anthropic" {
		t.Fatalf("expected the configured provider, got %s %v", provider, err)
	}
	if _, _, err := chooseSingleProvider(record, nil); err == nil {
		t.Fatal("with nothing configured there is nothing to choose")
	}
}

func TestModeAndHistorySurviveARestart(t *testing.T) {
	wallet := filepath.Join(t.TempDir(), "wallet.json")
	record := loadStats(wallet)
	record.Mode = modeSingle
	record.recordConsortium(consortiumFixture([]string{"anthropic", "kimi"}, "anthropic", 1))
	if err := record.save(); err != nil {
		t.Fatal(err)
	}

	reloaded := loadStats(wallet)
	if reloaded.Mode != modeSingle {
		t.Fatal("the mode is a preference, not a flag for one command")
	}
	if reloaded.Providers["anthropic"].carried() == 0 {
		t.Fatal("the record should have survived")
	}
}

func TestAnUnreadableRecordIsNotFatal(t *testing.T) {
	// It is a convenience file. Losing it costs a few calls' worth of history, which is not worth
	// refusing to run over.
	dir := t.TempDir()
	wallet := filepath.Join(dir, "wallet.json")
	if err := os.WriteFile(filepath.Join(dir, "stats.json"), []byte("{ not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	record := loadStats(wallet)
	if record.Mode != modeMulti || len(record.Providers) != 0 {
		t.Fatal("a corrupt record should read as an empty one")
	}
}

func TestStatsTableNamesWhatItMeasured(t *testing.T) {
	record := loadStats(filepath.Join(t.TempDir(), "wallet.json"))
	record.recordConsortium(consortiumFixture([]string{"anthropic", "kimi"}, "anthropic", 1))
	table := record.render()
	for _, want := range []string{"anthropic", "kimi", "carried", "single mode would use"} {
		if !strings.Contains(table, want) {
			t.Errorf("the table should mention %q:\n%s", want, table)
		}
	}
	if !strings.Contains(table, "whether an answer was good") {
		t.Errorf("the table should say what it does not measure:\n%s", table)
	}
}

func TestOnlyTheProviderSFailuresCountAgainstIt(t *testing.T) {
	// An empty wallet, an expired token, or a request this CLI built wrong never reached the
	// model. Counting those as its failures would steer single mode away from a model that has
	// done nothing wrong — which is exactly what a missing anthropic-version header did.
	for _, err := range []error{
		&apiError{Status: 402, Body: `{"error":"insufficient aicoin balance"}`},
		&apiError{Status: 401, Body: `{"error":"invalid API token"}`},
		&apiError{Status: 400, Body: `{"error":"anthropic-version: header is required"}`},
	} {
		if isProviderFailure(err) {
			t.Errorf("%v is this side's problem, not the model's", err)
		}
	}
	for _, err := range []error{
		&apiError{Status: 500, Body: "upstream exploded"},
		&apiError{Status: 429, Body: "slow down"},
		&apiError{Status: 504, Body: `{"error":"upstream timed out"}`},
		errors.New("connection refused"),
	} {
		if !isProviderFailure(err) {
			t.Errorf("%v is the model failing to answer", err)
		}
	}
}

func TestEveryChatProviderHasAModelToCallItWith(t *testing.T) {
	// Single mode picks from this list and then needs a model id for whatever it picked; a name in
	// one and not the other is a provider that can be chosen and cannot be called.
	for _, provider := range chatProviders {
		if defaultModels[provider] == "" {
			t.Errorf("%s is on the panel list with no default model", provider)
		}
	}
	for provider := range defaultModels {
		if !contains(chatProviders, provider) {
			t.Errorf("%s has a model but is not on the panel list", provider)
		}
	}
}
