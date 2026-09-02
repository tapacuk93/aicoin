package main

import (
	"fmt"
	"strings"
	"sync"
)

// Secrets: text that must not leave this machine.
//
// Everything after `$$` on a line is a secret. It is taken out before anything is sent — out of the
// question, out of the file contents pulled in with -f, out of the session history — and replaced
// with a reference like {{SECRET_1}}. The model is told the reference stands for a value it will
// never see, and that using the reference is how it puts the value somewhere: this CLI substitutes
// the real text back in on this side, at the moment a file is written or a command is run.
//
// So `aicoin "put $$sk-live-abc123 in .env as STRIPE_KEY"` sends "put {{SECRET_1}} in .env as
// STRIPE_KEY", gets back a file containing {{SECRET_1}}, and writes the key. The proxy sees the
// reference, every panelist sees the reference, and the transcript of the call — which is stored in
// the shared record and passed to four models over several rounds — has the reference in it too.
//
// The vault lives in memory for one command or one session and is never written anywhere: not to
// the stats file, not to the history, not to the terminal. The plan you approve shows the
// reference, not the value, because a plan is printed and scrollback is forever.

const secretMarker = "$$"

// secretVault holds this run's secrets and the references that stand in for them.
type secretVault struct {
	mu      sync.Mutex
	values  []string // index i is {{SECRET_i+1}}
	byValue map[string]string
}

func newSecretVault() *secretVault {
	return &secretVault{byValue: map[string]string{}}
}

// redact replaces everything after each `$$` marker with a reference, and remembers the value.
//
// Line by line, because the rule is "to the end of the line": a marker in the middle of a file's
// contents takes the rest of that line and nothing more.
func (v *secretVault) redact(text string) string {
	if text == "" || !strings.Contains(text, secretMarker) {
		return text
	}
	lines := strings.Split(text, "\n")
	for i, line := range lines {
		index := strings.Index(line, secretMarker)
		if index < 0 {
			continue
		}
		secret := strings.TrimSpace(line[index+len(secretMarker):])
		if secret == "" {
			continue
		}
		lines[i] = line[:index] + v.reference(secret)
	}
	return strings.Join(lines, "\n")
}

// reference returns the placeholder for a value, reusing it when the same secret appears twice —
// the same key pasted into two questions is one secret, and giving it two names would only tell the
// model there are two.
func (v *secretVault) reference(secret string) string {
	v.mu.Lock()
	defer v.mu.Unlock()
	if existing, ok := v.byValue[secret]; ok {
		return existing
	}
	v.values = append(v.values, secret)
	name := fmt.Sprintf("{{SECRET_%d}}", len(v.values))
	v.byValue[secret] = name
	return name
}

// reveal puts the real values back, on this side, at the last moment.
func (v *secretVault) reveal(text string) string {
	v.mu.Lock()
	defer v.mu.Unlock()
	for i, secret := range v.values {
		text = strings.ReplaceAll(text, fmt.Sprintf("{{SECRET_%d}}", i+1), secret)
	}
	return text
}

// used reports how many references appear in the text, so applying a plan can say that a secret
// went into it without saying which, or what.
func (v *secretVault) used(text string) int {
	v.mu.Lock()
	defer v.mu.Unlock()
	count := 0
	for i := range v.values {
		if strings.Contains(text, fmt.Sprintf("{{SECRET_%d}}", i+1)) {
			count++
		}
	}
	return count
}

func (v *secretVault) count() int {
	v.mu.Lock()
	defer v.mu.Unlock()
	return len(v.values)
}

// protocol tells the model what the references are and how to use them. Only sent once there is a
// secret to explain, so an ordinary call carries none of this.
func (v *secretVault) protocol() string {
	count := v.count()
	if count == 0 {
		return ""
	}
	names := make([]string, count)
	for i := range names {
		names[i] = fmt.Sprintf("{{SECRET_%d}}", i+1)
	}
	return "=== Withheld values ===\n" +
		"The text above contains " + strings.Join(names, ", ") + ". Each stands for a value that was" +
		" deliberately withheld from you — a key, a password, a token — and you will not be told what" +
		" any of them are.\n\n" +
		"Write the reference exactly as it appears wherever the value belongs: in a file's contents," +
		" in a command, anywhere. It is substituted for the real value on the user's machine, after" +
		" you are done and before anything is written or run. Do not guess at a value, do not invent" +
		" a placeholder of your own, and do not ask what it is — using the reference is how the value" +
		" gets where it needs to go."
}
