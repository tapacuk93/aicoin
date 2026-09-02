package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Acting on the working directory.
//
// A panel that can see the files but cannot touch them answers "create an empty file" with
// instructions for typing `touch` yourself, which is not what anyone meant. So the answer may
// instead be a block of file operations, which this CLI shows you and — only with your say-so —
// applies.
//
// The proxy knows nothing about any of this: it has no filesystem, and a proxy that could write to
// one would be a much more dangerous thing than a proxy that forwards calls. The protocol lives in
// the context the client sends and the acting lives in the client, which is the only side that has
// a directory in the first place.

// actionProtocol is appended to the context of every directory-aware call. It is deliberately
// specific about *whole contents*: a model asked for a patch will happily invent one against a
// version of the file it half-remembers.
const actionProtocol = `=== Acting on the working directory ===
If the request asks for files to be created, changed or deleted, do not explain how to do it —
reply with exactly one fenced block, and nothing outside it:

` + "```aicoin-actions" + `
[
  {"op": "write",  "path": "relative/path.txt", "content": "the file's entire new contents"},
  {"op": "delete", "path": "relative/path.txt"}
]
` + "```" + `

Paths are relative to the working directory above; anything outside it is refused. "write" creates
the file or replaces it whole, so give the complete intended contents rather than a patch or an
excerpt. List only what the request actually asked to change.

If the request is a question rather than an instruction, answer it normally and emit no block.`

// action is one file operation the panel proposed.
type action struct {
	Op      string `json:"op"`
	Path    string `json:"path"`
	Content string `json:"content"`
}

var actionBlock = regexp.MustCompile("(?s)```aicoin-actions\\s*(.*?)```")

// parseActions pulls the operations out of an answer, and reports whether the answer was one. An
// answer with no block is an ordinary answer and is printed as-is.
func parseActions(answer string) ([]action, bool) {
	match := actionBlock.FindStringSubmatch(answer)
	if match == nil {
		return nil, false
	}
	var actions []action
	if err := json.Unmarshal([]byte(strings.TrimSpace(match[1])), &actions); err != nil {
		return nil, false
	}
	if len(actions) == 0 {
		return nil, false
	}
	return actions, true
}

// resolveAction turns an action's path into an absolute one inside root, or refuses it.
//
// The check is on the cleaned, resolved path rather than on the text of it: "../../etc/passwd" and
// "a/../../b" and "/etc/passwd" all have to fail, and only one of them looks suspicious.
func resolveAction(root string, a action) (string, error) {
	if strings.TrimSpace(a.Path) == "" {
		return "", fmt.Errorf("an action with no path")
	}
	if filepath.IsAbs(a.Path) {
		return "", fmt.Errorf("%s: absolute paths are refused; actions stay inside the working directory", a.Path)
	}
	absoluteRoot, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	full := filepath.Clean(filepath.Join(absoluteRoot, a.Path))
	if full != absoluteRoot && !strings.HasPrefix(full, absoluteRoot+string(os.PathSeparator)) {
		return "", fmt.Errorf("%s: outside the working directory", a.Path)
	}
	if full == absoluteRoot {
		return "", fmt.Errorf("%s: that is the working directory itself", a.Path)
	}
	return full, nil
}

// plannedAction is one validated operation, with what it will do to what is already there.
type plannedAction struct {
	action
	Full     string
	Existing bool
	OldSize  int64
}

// planActions validates every operation and describes what applying them would do. It returns an
// error if any one of them is unsafe: a plan is applied as a whole, so one bad path invalidates it
// rather than being quietly skipped.
func planActions(root string, actions []action) ([]plannedAction, error) {
	var planned []plannedAction
	for _, a := range actions {
		if a.Op != "write" && a.Op != "delete" {
			return nil, fmt.Errorf("unknown operation %q", a.Op)
		}
		full, err := resolveAction(root, a)
		if err != nil {
			return nil, err
		}
		entry := plannedAction{action: a, Full: full}
		if info, statErr := os.Stat(full); statErr == nil {
			if info.IsDir() {
				return nil, fmt.Errorf("%s: is a directory", a.Path)
			}
			entry.Existing = true
			entry.OldSize = info.Size()
		} else if a.Op == "delete" {
			return nil, fmt.Errorf("%s: nothing there to delete", a.Path)
		}
		planned = append(planned, entry)
	}
	return planned, nil
}

// describe renders the plan for a human to approve. Replacements and deletions are named as such:
// the difference between creating a file and overwriting one that already has something in it is
// the whole reason this is confirmed rather than applied.
func describe(planned []plannedAction) string {
	var out strings.Builder
	for _, entry := range planned {
		switch {
		case entry.Op == "delete":
			out.WriteString(fmt.Sprintf("  delete   %s (%d bytes)\n", entry.Path, entry.OldSize))
		case entry.Existing:
			out.WriteString(fmt.Sprintf("  replace  %s (%d bytes, was %d)\n",
				entry.Path, len(entry.Content), entry.OldSize))
		default:
			out.WriteString(fmt.Sprintf("  create   %s (%d bytes)\n", entry.Path, len(entry.Content)))
		}
	}
	return out.String()
}

// deliverAnswer prints an ordinary answer, or — when the panel replied with operations — shows
// what it proposes and applies it once approved.
//
// Approval is the point. The panel writes whole files, sometimes over ones that already have
// something in them, and it is wrong often enough to make an unattended `rm`/overwrite a bad
// trade. `-y` (or `/auto` in a session) is there for people who have decided otherwise, and a
// non-interactive run without it prints the plan and does nothing, because there is nobody to ask.
func deliverAnswer(root, answer string, auto bool, confirm func(string) bool) {
	actions, ok := parseActions(answer)
	if !ok {
		fmt.Println(answer)
		return
	}
	planned, err := planActions(root, actions)
	if err != nil {
		// A refused plan is worth seeing in full: it is usually a path that wandered outside the
		// directory, and the answer itself may still be useful.
		fmt.Println(answer)
		fmt.Fprintf(os.Stderr, "aicoin: nothing applied — %v\n", err)
		return
	}
	fmt.Fprintf(os.Stderr, "the panel proposes %d change(s) in %s:\n%s", len(planned), root, describe(planned))
	if !auto && !confirm("apply? [y/N] ") {
		fmt.Fprintln(os.Stderr, "nothing was written")
		return
	}
	if err := applyActions(planned); err != nil {
		fmt.Fprintf(os.Stderr, "aicoin: %v\n", err)
		return
	}
	fmt.Fprintf(os.Stderr, "applied %d change(s)\n", len(planned))
}

// applyActions carries out an approved plan.
func applyActions(planned []plannedAction) error {
	for _, entry := range planned {
		if entry.Op == "delete" {
			if err := os.Remove(entry.Full); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(entry.Full), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(entry.Full, []byte(entry.Content), 0o644); err != nil {
			return err
		}
	}
	return nil
}
