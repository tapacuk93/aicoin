package main

import (
	"regexp"
	"strconv"
	"strings"
)

// Telling a subcommand from a question.
//
// Typing `single` and watching it go to the panel as a question — eight seconds of spinner and a
// paid call — is the failure this exists to stop. A line that is exactly a subcommand, with
// arguments that fit that subcommand, is a subcommand. Anything else is a question, and the two
// escapes settle the rest: a backslash forces a question, backticks (or a slash) force a command.
//
// The rule is deliberately conservative in one direction only. "single" is a command; "single out
// the slowest handler" is a question, because those trailing words are not something `single` could
// take. When in doubt it stays a question — a question costs a call, while a mistaken command could
// change the mode or the files under you.

// commandArity says what shape of arguments a subcommand accepts, which is what makes a bare word
// safe to read as a command: the words after it have to fit.
type commandArity int

const (
	// noArgs: the word alone is the command. "reset" is a command; "reset the counter" is a question.
	noArgs commandArity = iota
	// optionalProvider: nothing, or one model name this proxy knows.
	optionalProvider
	// optionalPanel: nothing, or one comma-separated list of model names.
	optionalPanel
	// oneNumber: exactly one number.
	oneNumber
	// paths: one or more path-like words (a glob, a directory, something with a dot in it).
	paths
)

// commands are every subcommand and the arguments it will accept. Aliases share a shape.
var commands = map[string]commandArity{
	"exit": noArgs, "quit": noArgs, "q": noArgs,
	"help": noArgs, "?": noArgs,
	"files": noArgs, "balance": noArgs, "claim": noArgs, "reset": noArgs,
	"v": noArgs, "auto": noArgs, "multi": noArgs, "ais": noArgs, "stats": noArgs,
	"single": optionalProvider,
	"panel":  optionalPanel,
	"rounds": oneNumber,
	"f":      paths,
}

// pathLike matches a word that is plausibly a file, directory or glob rather than a word of prose.
// A bare word is not enough: `f why is this failing` should stay a question.
var pathLike = regexp.MustCompile(`[./*?\[\]]`)

// classifyLine decides what a line typed into the session is.
//
// Returns the command line (without its marker) and true for a subcommand, or the text to ask and
// false for a question.
func classifyLine(line string) (string, bool) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" {
		return "", false
	}
	// A backslash escapes: `\single` asks the panel about the word "single".
	if strings.HasPrefix(trimmed, `\`) {
		return strings.TrimSpace(trimmed[1:]), false
	}
	// Backticks force the other way, for a command whose arguments this would not otherwise
	// recognise: `f some odd name`.
	if inner, ok := backtickCommand(trimmed); ok {
		return strings.TrimPrefix(inner, "/"), true
	}
	if strings.HasPrefix(trimmed, "/") {
		return strings.TrimSpace(trimmed[1:]), true
	}
	if looksLikeCommand(trimmed) {
		return trimmed, true
	}
	return trimmed, false
}

// looksLikeCommand reports whether a bare line is a subcommand: a known word, followed by arguments
// that word could actually take.
func looksLikeCommand(line string) bool {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return false
	}
	arity, known := commands[strings.ToLower(fields[0])]
	if !known {
		return false
	}
	args := fields[1:]
	switch arity {
	case noArgs:
		return len(args) == 0
	case optionalProvider:
		return len(args) == 0 || (len(args) == 1 && contains(ChatProviders, strings.ToLower(args[0])))
	case optionalPanel:
		if len(args) == 0 {
			return true
		}
		if len(args) > 1 {
			return false
		}
		for _, name := range strings.Split(args[0], ",") {
			if !contains(ChatProviders, strings.ToLower(strings.TrimSpace(name))) {
				return false
			}
		}
		return true
	case oneNumber:
		if len(args) != 1 {
			return false
		}
		_, err := strconv.Atoi(args[0])
		return err == nil
	case paths:
		if len(args) == 0 {
			return true // `f` alone clears the includes
		}
		for _, arg := range args {
			if !pathLike.MatchString(arg) {
				return false
			}
		}
		return true
	}
	return false
}
