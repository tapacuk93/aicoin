package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The directory block is sent to every panelist on every round, so what it includes — and what it
// refuses to include — is a spend decision as much as a correctness one.

func fixture(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	write := func(path, body string) {
		full := filepath.Join(root, path)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("main.go", "package main // TOP LEVEL SOURCE")
	write("cli/handler.go", "package cli // NESTED SOURCE")
	write("README.md", "# readme body")
	write("build/generated.go", "package build // DERIVED")
	write("node_modules/dep/index.js", "module.exports = 1")
	write(".git/config", "[core]")
	write(".env", "SECRET=1")
	write("logo.png", "\x89PNG\x00binary")
	return root
}

func TestListingCoversTheTreeButNotItsDerivedOrHiddenParts(t *testing.T) {
	gathered, err := gatherDir(fixture(t), nil, 40000)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"main.go", "cli/handler.go", "README.md"} {
		if !strings.Contains(gathered.Text, want) {
			t.Errorf("listing should name %s", want)
		}
	}
	for _, unwanted := range []string{"build/", "node_modules", ".git", ".env", "logo.png"} {
		if strings.Contains(gathered.Text, unwanted) {
			t.Errorf("listing should not name %s — it is derived, huge, secret or binary", unwanted)
		}
	}
	if gathered.FileCount != 0 {
		t.Errorf("contents are opt-in; got %d files included with no -f", gathered.FileCount)
	}
}

func TestIncludeGlobsPullInContents(t *testing.T) {
	root := fixture(t)

	gathered, err := gatherDir(root, []string{"*.go"}, 40000)
	if err != nil {
		t.Fatal(err)
	}
	// A bare name or pattern matches anywhere in the tree: that is what someone typing "*.go"
	// in a repo root means.
	if !strings.Contains(gathered.Text, "TOP LEVEL SOURCE") || !strings.Contains(gathered.Text, "NESTED SOURCE") {
		t.Fatal("-f '*.go' should include both source files' contents")
	}
	if strings.Contains(gathered.Text, "readme body") {
		t.Fatal("-f '*.go' should not have pulled in the README")
	}
	if gathered.FileCount != 2 {
		t.Fatalf("expected 2 files included, got %d", gathered.FileCount)
	}
}

func TestPathPatternsMatchTheWholePathAndDirectoryPrefixes(t *testing.T) {
	root := fixture(t)

	byPath, err := gatherDir(root, []string{"cli/*.go"}, 40000)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(byPath.Text, "NESTED SOURCE") || strings.Contains(byPath.Text, "TOP LEVEL SOURCE") {
		t.Fatal("a pattern with a separator should match the path, not the base name")
	}

	byDir, err := gatherDir(root, []string{"cli/"}, 40000)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(byDir.Text, "NESTED SOURCE") {
		t.Fatal("naming a directory should include what is under it")
	}
}

func TestTheBudgetIsRespectedAndSaysSo(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "big.txt"), []byte(strings.Repeat("x", 5000)), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "small.txt"), []byte("tiny"), 0o644); err != nil {
		t.Fatal(err)
	}

	gathered, err := gatherDir(root, []string{"*.txt"}, 1200)
	if err != nil {
		t.Fatal(err)
	}
	if gathered.Chars > 1200 {
		t.Fatalf("the budget is the point: got %d chars for a budget of 1200", gathered.Chars)
	}
	if !gathered.Truncated {
		t.Fatal("a call that dropped content must report that it did")
	}
	if strings.Contains(gathered.Text, strings.Repeat("x", 5000)) {
		t.Fatal("the oversized file should not have been included whole")
	}
}

func TestReadTextFileRefusesBinaryAndOversized(t *testing.T) {
	root := t.TempDir()
	binary := filepath.Join(root, "blob.dat")
	if err := os.WriteFile(binary, []byte{'a', 0x00, 'b'}, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := readTextFile(binary); ok {
		t.Fatal("a NUL byte means binary: it wastes budget and tells the panel nothing")
	}
	huge := filepath.Join(root, "huge.txt")
	if err := os.WriteFile(huge, []byte(strings.Repeat("x", maxFileBytes+1)), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, ok := readTextFile(huge); ok {
		t.Fatal("a file past the per-file cap should be refused")
	}
}

func TestCoinBarShowsTheShareOfTheWalletThatWent(t *testing.T) {
	bar := coinBar(20, 5, 15, 0)
	if !strings.Contains(bar, "15 aicoin spent") || !strings.Contains(bar, "5 left") {
		t.Fatalf("got %q", bar)
	}
	if strings.Count(bar, "◆") == 0 || strings.Count(bar, "◆") == 10 {
		t.Fatalf("three quarters of the wallet should be a partly filled bar, got %q", bar)
	}
	// Nothing spent and nothing known: still a readable line, not a bar of zero width.
	if plain := coinBar(0, 0, 0, 0); strings.Contains(plain, "◆") {
		t.Fatalf("got %q", plain)
	}
}
