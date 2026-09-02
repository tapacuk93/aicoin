package main

import "testing"

// The line between a subcommand and a question. Getting this wrong in one direction sends "single"
// to the panel and charges for it; in the other, it swallows a question. When the shape is not
// exactly a command's, it stays a question.

func TestABareCommandWordIsACommand(t *testing.T) {
	for _, line := range []string{"single", "multi", "ais", "help", "exit", "balance", "reset", " single "} {
		text, isCommand := classifyLine(line)
		if !isCommand {
			t.Errorf("%q should be a subcommand, got the question %q", line, text)
		}
	}
}

func TestArgumentsThatFitTheCommandKeepItACommand(t *testing.T) {
	for _, line := range []string{
		"single kimi",
		"panel anthropic,kimi",
		"rounds 2",
		"f *.go",
		"f cli/main.go README.md",
		"f",
	} {
		if _, isCommand := classifyLine(line); !isCommand {
			t.Errorf("%q should be a subcommand", line)
		}
	}
}

func TestWordsThatDoNotFitMakeItAQuestion(t *testing.T) {
	// This is the whole point: a command word at the start does not make a sentence a command.
	for _, line := range []string{
		"single out the slowest handler",
		"multi region deployment: is it worth it?",
		"reset the counter in main.go",
		"rounds of review — how many are useful?",
		"panel discussion notes",
		"single gpt7",              // not a model this proxy knows
		"rounds many",              // not a number
		"f why is this failing",    // not paths
		"help me name this method", // help takes nothing
	} {
		text, isCommand := classifyLine(line)
		if isCommand {
			t.Errorf("%q should have stayed a question, got the command %q", line, text)
		}
	}
}

func TestTheEscapesSettleItEitherWay(t *testing.T) {
	// A backslash forces a question, for the day you want to ask about the word itself.
	text, isCommand := classifyLine(`\single`)
	if isCommand || text != "single" {
		t.Fatalf(`\single should be the question "single", got %q (command=%v)`, text, isCommand)
	}
	// Backticks and a slash force a command, for arguments this would not otherwise recognise.
	for _, line := range []string{"`f some odd name`", "/f some odd name"} {
		text, isCommand := classifyLine(line)
		if !isCommand || text != "f some odd name" {
			t.Errorf("%q should be a command, got %q (command=%v)", line, text, isCommand)
		}
	}
}

func TestAnUnknownWordIsAlwaysAQuestion(t *testing.T) {
	for _, line := range []string{"deploy", "what is an aicoin?", "create empty file"} {
		if _, isCommand := classifyLine(line); isCommand {
			t.Errorf("%q is not a subcommand", line)
		}
	}
}
