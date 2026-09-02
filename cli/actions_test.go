package main

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// These guard the one part of this CLI that writes to disk on a model's say-so. Everything else it
// gets wrong costs a coin and some patience; this could cost a file.

func TestAnAnswerWithNoBlockIsJustAnAnswer(t *testing.T) {
	for _, answer := range []string{
		"Use `touch file.txt` to create an empty file.",
		"```bash\ntouch file.txt\n```",
		"```aicoin-actions\nnot json at all\n```",
		"```aicoin-actions\n[]\n```",
	} {
		if _, ok := parseActions(answer); ok {
			t.Errorf("should not have been read as actions: %q", answer)
		}
	}
}

func TestActionsAreReadOutOfTheBlock(t *testing.T) {
	answer := "```aicoin-actions\n[{\"op\":\"write\",\"path\":\"a.txt\",\"content\":\"hello\"}," +
		"{\"op\":\"delete\",\"path\":\"b.txt\"}]\n```"
	actions, ok := parseActions(answer)
	if !ok || len(actions) != 2 {
		t.Fatalf("expected 2 actions, got %v (ok=%v)", actions, ok)
	}
	if actions[0].Op != "write" || actions[0].Path != "a.txt" || actions[0].Content != "hello" {
		t.Fatalf("first action wrong: %+v", actions[0])
	}
}

func TestNothingEscapesTheWorkingDirectory(t *testing.T) {
	root := t.TempDir()
	for _, path := range []string{
		"../outside.txt",
		"nested/../../outside.txt",
		"/etc/passwd",
		"",
		".",
	} {
		_, err := planActions(root, []action{{Op: "write", Path: path, Content: "x"}})
		if err == nil {
			t.Errorf("%q should have been refused", path)
		}
	}
	// The same path written the long way round is still inside, and is fine.
	if _, err := planActions(root, []action{{Op: "write", Path: "nested/../ok.txt", Content: "x"}}); err != nil {
		t.Fatalf("a path that resolves inside the directory should be allowed: %v", err)
	}
}

func TestUnknownOperationsAndMissingTargetsAreRefused(t *testing.T) {
	root := t.TempDir()
	if _, err := planActions(root, []action{{Op: "chmod", Path: "a.txt"}}); err == nil {
		t.Fatal("only write and delete exist")
	}
	if _, err := planActions(root, []action{{Op: "delete", Path: "never-existed.txt"}}); err == nil {
		t.Fatal("deleting something that is not there should be refused, not silently ignored")
	}
	if err := os.Mkdir(filepath.Join(root, "adir"), 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := planActions(root, []action{{Op: "write", Path: "adir", Content: "x"}}); err == nil {
		t.Fatal("a directory is not a file to overwrite")
	}
}

func TestOnePathThatIsRefusedInvalidatesTheWholePlan(t *testing.T) {
	// Applying the safe half of a plan would leave the directory in a state the panel never
	// proposed and nobody approved.
	root := t.TempDir()
	_, err := planActions(root, []action{
		{Op: "write", Path: "fine.txt", Content: "x"},
		{Op: "write", Path: "../bad.txt", Content: "x"},
	})
	if err == nil {
		t.Fatal("expected the plan to be refused as a whole")
	}
	if _, statErr := os.Stat(filepath.Join(root, "fine.txt")); statErr == nil {
		t.Fatal("planning must not write anything")
	}
}

func TestThePlanSaysWhetherItCreatesOrOverwrites(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "existing.txt"), []byte("old contents"), 0o644); err != nil {
		t.Fatal(err)
	}
	planned, err := planActions(root, []action{
		{Op: "write", Path: "new.txt", Content: "hello"},
		{Op: "write", Path: "existing.txt", Content: "replacement"},
		{Op: "delete", Path: "existing.txt"},
	})
	if err != nil {
		t.Fatal(err)
	}
	text := describe(planned)
	if !strings.Contains(text, "create   new.txt") {
		t.Errorf("a new file should read as created: %s", text)
	}
	if !strings.Contains(text, "replace  existing.txt") || !strings.Contains(text, "was 12") {
		t.Errorf("overwriting an existing file must say so, and what it is losing: %s", text)
	}
	if !strings.Contains(text, "delete   existing.txt") {
		t.Errorf("a deletion should read as one: %s", text)
	}
}

func TestApplyWritesCreatesDirectoriesAndDeletes(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "gone.txt"), []byte("bye"), 0o644); err != nil {
		t.Fatal(err)
	}
	planned, err := planActions(root, []action{
		{Op: "write", Path: "nested/deep/file.txt", Content: "made it"},
		{Op: "delete", Path: "gone.txt"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := applyActions(planned); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(filepath.Join(root, "nested/deep/file.txt"))
	if err != nil || string(body) != "made it" {
		t.Fatalf("expected the file to have been written, got %q / %v", body, err)
	}
	if _, err := os.Stat(filepath.Join(root, "gone.txt")); !os.IsNotExist(err) {
		t.Fatal("the deletion did not happen")
	}
}

func TestNothingIsWrittenWithoutApproval(t *testing.T) {
	root := t.TempDir()
	answer := "```aicoin-actions\n[{\"op\":\"write\",\"path\":\"a.txt\",\"content\":\"x\"}]\n```"

	deliverAnswer(root, answer, false, func(string) bool { return false })
	if _, err := os.Stat(filepath.Join(root, "a.txt")); !os.IsNotExist(err) {
		t.Fatal("a declined plan must write nothing")
	}

	deliverAnswer(root, answer, false, func(string) bool { return true })
	if _, err := os.Stat(filepath.Join(root, "a.txt")); err != nil {
		t.Fatal("an approved plan should have been applied")
	}
}

func TestAutoSkipsTheQuestionEntirely(t *testing.T) {
	root := t.TempDir()
	answer := "```aicoin-actions\n[{\"op\":\"write\",\"path\":\"b.txt\",\"content\":\"x\"}]\n```"
	asked := false
	deliverAnswer(root, answer, true, func(string) bool { asked = true; return false })
	if asked {
		t.Fatal("-y means do not ask")
	}
	if _, err := os.Stat(filepath.Join(root, "b.txt")); err != nil {
		t.Fatal("with -y the plan should have been applied")
	}
}

// captureStderr runs f with stderr redirected, and returns what it wrote.
func captureStderr(t *testing.T, f func()) string {
	t.Helper()
	real := os.Stderr
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stderr = write
	done := make(chan string)
	go func() {
		var buf strings.Builder
		io.Copy(&buf, read)
		done <- buf.String()
	}()
	f()
	write.Close()
	os.Stderr = real
	return <-done
}

func TestFailuresFromAnEmptyWalletCollapseIntoOneLine(t *testing.T) {
	// The transcript that prompted this: three providers, three identical "insufficient balance"
	// lines, and the one thing worth reading — that the wallet is empty — said nowhere.
	result := &consortiumResult{}
	result.Errors = []struct {
		Stage    string `json:"stage"`
		Provider string `json:"provider"`
		Error    string `json:"error"`
	}{
		{Stage: "draft", Provider: "google", Error: "insufficient balance"},
		{Stage: "draft", Provider: "kimi", Error: "insufficient balance"},
		{Stage: "merge", Provider: "anthropic", Error: "insufficient balance"},
		{Stage: "review", Provider: "openai", Error: "upstream timed out"},
	}

	output := captureStderr(t, func() { reportFailures(result) })

	if strings.Count(output, "insufficient balance") > 0 {
		t.Errorf("the per-provider repetition should be gone: %s", output)
	}
	if !strings.Contains(output, "3 turn(s) went unmade") || !strings.Contains(output, "aicoin claim") {
		t.Errorf("one line should say how many turns were lost and what to do: %s", output)
	}
	// A failure that is genuinely per-provider still gets its own line.
	if !strings.Contains(output, "openai failed at the review turn: upstream timed out") {
		t.Errorf("a real provider failure must survive the collapsing: %s", output)
	}
}

func TestAnthropicRequestsCarryTheVersionHeader(t *testing.T) {
	// Without it the Messages API answers 400 and the call is wasted. The proxy sets it on its own
	// consortium turns; a request this CLI composes has to set it itself.
	if chatHeaders("anthropic")["anthropic-version"] == "" {
		t.Fatal("anthropic needs a version header")
	}
	if len(chatHeaders("openai")) != 0 || len(chatHeaders("kimi")) != 0 {
		t.Fatal("the OpenAI-compatible providers need nothing extra")
	}
}

func TestTheCoinMeterShowsTheWalletAndWhatHasGone(t *testing.T) {
	// While a call runs, the wallet is what there is to watch: the number itself, and — once it
	// moves — how much of it the call has taken.
	if got := coinMeterText(1000, 1000); got != "1000 aicoin" {
		t.Errorf("before anything is spent, just the balance: %q", got)
	}
	if got := coinMeterText(987, 1000); !strings.Contains(got, "987 aicoin") || !strings.Contains(got, "13") {
		t.Errorf("once it moves, the balance and what went: %q", got)
	}
	// A balance read that failed leaves the previous figure standing rather than showing a zero,
	// so a rising number is never invented either.
	if got := coinMeterText(1000, 990); strings.Contains(got, "−") {
		t.Errorf("a balance above the start is not a negative spend: %q", got)
	}
}
