package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHistoryCarriesTheNewestExchangesAndSaysWhatItDropped(t *testing.T) {
	// History is sent to every panelist on every round of every turn, so it cannot simply grow.
	// A follow-up question is about the recent exchanges, which is why the oldest go first.
	state := &sessionState{}
	state.history = append(state.history, exchange{
		Question: "the oldest question",
		Answer:   strings.Repeat("old ", sessionHistoryBudget/4),
	})
	state.history = append(state.history, exchange{Question: "the newest question", Answer: "the newest answer"})

	text := state.historyText()

	if !strings.Contains(text, "the newest question") || !strings.Contains(text, "the newest answer") {
		t.Fatal("the most recent exchange must always be carried")
	}
	if strings.Contains(text, "the oldest question") {
		t.Fatal("an exchange past the budget should have been dropped")
	}
	if !strings.Contains(text, "omitted") {
		t.Fatal("a turn given a trimmed history must be told it was trimmed")
	}
}

func TestHistoryIsEmptyBeforeAnythingIsAsked(t *testing.T) {
	state := &sessionState{}
	if state.historyText() != "" {
		t.Fatal("a fresh session should send no history block at all")
	}
}

func TestHistoryReadsInTheOrderItHappened(t *testing.T) {
	state := &sessionState{history: []exchange{
		{Question: "first", Answer: "one"},
		{Question: "second", Answer: "two"},
	}}
	text := state.historyText()
	if strings.Index(text, "first") > strings.Index(text, "second") {
		t.Fatal("exchanges must read oldest to newest, like the panel's own record")
	}
}

func TestOnlyPathLikeArgumentsOpenASession(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "cli"), 0o755); err != nil {
		t.Fatal(err)
	}
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(root); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(cwd)

	if !looksLikeDir(".") || !looksLikeDir("..") {
		t.Fatal("`aicoin .` is the whole point")
	}
	if !looksLikeDir("./cli") {
		t.Fatal("a path with a separator naming a real directory should open a session")
	}
	// The ambiguous case, decided towards the question: a one-word argument that happens to match
	// a directory name is far more likely to be a short question than a request for a session.
	if looksLikeDir("cli") {
		t.Fatal("a bare name must stay a question, even when a directory of that name exists")
	}
	if looksLikeDir("why does this fail?") || looksLikeDir("./nope") {
		t.Fatal("neither a question nor a missing path is a session")
	}
}

func TestSummaryNamesWhoLedTheCall(t *testing.T) {
	led := howItWent(&consortiumResult{
		Settled: true, Rounds: 2, Calls: 6, Panel: []string{"anthropic", "kimi"},
		Editor: "anthropic", Mode: "lead",
	})
	if !strings.Contains(led, "led by anthropic") {
		t.Fatalf("got %q", led)
	}
	panel := howItWent(&consortiumResult{
		StoppedReason: "round_limit", Rounds: 3, Calls: 13, Panel: []string{"anthropic"},
		Editor: "anthropic", Mode: "panel",
	})
	if !strings.Contains(panel, "drafted by the panel") || !strings.Contains(panel, "round_limit") {
		t.Fatalf("got %q", panel)
	}
}
