package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The one rule these hold to: a value written after $$ never appears in anything sent to the proxy,
// and does appear in what lands on disk.

func TestEverythingAfterTheMarkerIsWithheld(t *testing.T) {
	vault := newSecretVault()

	sent := vault.redact("put $$sk-live-abc123 in .env as STRIPE_KEY")

	if strings.Contains(sent, "sk-live-abc123") {
		t.Fatalf("the secret is still in what would be sent: %q", sent)
	}
	if !strings.Contains(sent, "{{SECRET_1}}") {
		t.Fatalf("expected a reference in its place: %q", sent)
	}
	if !strings.HasPrefix(sent, "put ") {
		t.Fatalf("only the tail of the line is a secret: %q", sent)
	}
}

func TestTheMarkerTakesTheRestOfItsLineAndNoMore(t *testing.T) {
	vault := newSecretVault()

	sent := vault.redact("first line\nexport TOKEN=$$abc123\nthird line")

	if strings.Contains(sent, "abc123") {
		t.Fatalf("secret leaked: %q", sent)
	}
	for _, kept := range []string{"first line", "third line", "export TOKEN="} {
		if !strings.Contains(sent, kept) {
			t.Errorf("%q should have survived redaction: %q", kept, sent)
		}
	}
}

func TestTheSameSecretTwiceIsOneSecret(t *testing.T) {
	// Two names for one value would tell the model there are two values, which is itself something
	// it does not need to know.
	vault := newSecretVault()
	first := vault.redact("set it to $$hunter2")
	second := vault.redact("and again $$hunter2")

	if vault.count() != 1 {
		t.Fatalf("expected one secret, got %d", vault.count())
	}
	if !strings.Contains(first, "{{SECRET_1}}") || !strings.Contains(second, "{{SECRET_1}}") {
		t.Fatalf("both should use the same reference: %q / %q", first, second)
	}
}

func TestAMarkerWithNothingAfterItIsNotASecret(t *testing.T) {
	vault := newSecretVault()
	if got := vault.redact("nothing follows this $$"); vault.count() != 0 {
		t.Fatalf("an empty tail is not a secret: %q", got)
	}
	if got := vault.redact("nor this $$   \nnext line"); vault.count() != 0 {
		t.Fatalf("whitespace is not a secret either: %q", got)
	}
}

func TestTheMarkerIsTakenLiterallyEvenInProse(t *testing.T) {
	// The cost of a marker with no escape: "what does $$ mean in a shell?" withholds the rest of
	// the question. That is the rule working — it fails towards withholding, which is the right
	// direction for a rule about secrets — but it is a real edge, so it is pinned rather than
	// discovered.
	vault := newSecretVault()
	sent := vault.redact("what does $$ mean in a shell?")
	if vault.count() != 1 || strings.Contains(sent, "mean in a shell") {
		t.Fatalf("the rest of the line should have been withheld: %q", sent)
	}
}

func TestTheValueComesBackOnlyOnThisSide(t *testing.T) {
	vault := newSecretVault()
	vault.redact("the key is $$sk-live-abc123")

	// What the model sends back references the secret; what gets written has it.
	written := vault.reveal("STRIPE_KEY={{SECRET_1}}\n")
	if written != "STRIPE_KEY=sk-live-abc123\n" {
		t.Fatalf("the value should be substituted on applying, got %q", written)
	}
}

func TestAPlanIsShownWithReferencesAndAppliedWithValues(t *testing.T) {
	// A plan is printed to the terminal, and scrollback is forever — so the plan shows the
	// reference and only the file gets the value.
	root := t.TempDir()
	vault := newSecretVault()
	vault.redact("use $$sk-live-abc123")
	answer := "```aicoin-actions\n[{\"op\":\"write\",\"path\":\".env\",\"content\":\"KEY={{SECRET_1}}\"}]\n```"

	planned, err := planActions(root, mustParse(t, answer))
	if err != nil {
		t.Fatal(err)
	}
	if shown := describe(planned, vault); strings.Contains(shown, "sk-live-abc123") {
		t.Fatalf("the plan must not print the value: %q", shown)
	}
	if secretsInPlan(planned, vault) != 1 {
		t.Fatal("the plan should report that a withheld value goes into it")
	}
	// The size shown is the size that lands — "KEY=sk-live-abc123", not the shorter line with the
	// reference in it. The content is never printed either way, so the size can tell the truth.
	if !strings.Contains(describe(planned, vault), "18 bytes") {
		t.Errorf("the plan should size the file as written: %s", describe(planned, vault))
	}
	if err := applyActions(root, revealPlan(planned, vault)); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(filepath.Join(root, ".env"))
	if err != nil || string(body) != "KEY=sk-live-abc123" {
		t.Fatalf("the file should have the real value, got %q / %v", body, err)
	}
}

func TestACommandCarriesTheValueTooButOnlyWhenItRuns(t *testing.T) {
	root := t.TempDir()
	vault := newSecretVault()
	vault.redact("token $$abc123")
	planned, err := planActions(root, []action{{Op: "run", Command: "echo {{SECRET_1}} > out.txt"}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(describe(planned, vault), "abc123") {
		t.Fatal("the command shown for approval must not contain the value")
	}
	if err := applyActions(root, revealPlan(planned, vault)); err != nil {
		t.Fatal(err)
	}
	body, _ := os.ReadFile(filepath.Join(root, "out.txt"))
	if strings.TrimSpace(string(body)) != "abc123" {
		t.Fatalf("the command should have run with the real value, got %q", body)
	}
}

func TestTheModelIsToldWhatTheReferencesAreForOnlyWhenThereAreSome(t *testing.T) {
	vault := newSecretVault()
	if vault.protocol() != "" {
		t.Fatal("an ordinary call should carry none of this")
	}
	vault.redact("key $$abc")
	note := vault.protocol()
	if !strings.Contains(note, "{{SECRET_1}}") {
		t.Error("the note should name the reference")
	}
	if !strings.Contains(note, "will not be told") || !strings.Contains(note, "do not ask") {
		t.Errorf("the note should be plain that the value is withheld: %s", note)
	}
	if strings.Contains(note, "abc") {
		t.Error("the note itself must not carry the value")
	}
}

func mustParse(t *testing.T, answer string) []action {
	t.Helper()
	actions, ok := parseActions(answer)
	if !ok {
		t.Fatal("expected an actions block")
	}
	return actions
}
